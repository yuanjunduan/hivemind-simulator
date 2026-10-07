// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.hivemind.simulator.core.statemachine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DJI 字段 → 生命周期状态映射表全组合单测（TC-CORE-020~029，实施方案 §2.5.2 推导表）。
 * <p>纯函数验证：输入快照组合 → 状态与"是否命中组合"，不依赖 Spring 上下文与真实时钟。</p>
 */
class DjiStateMapperTest {

    /**
     * READY 基线：开机、在线、MQTT 已连、在舱、已激活、mode=0、无任务、未充电。
     */
    private static DjiStateMapper.Input baseline() {
        return new DjiStateMapper.Input(true, true, true, true, true, 0,
                false, false, false, false);
    }

    /** 以基线为模板派生变体（逐字段覆盖，避免十参数构造散布各用例） */
    private static DjiStateMapper.Input variant(Boolean powered, Boolean online, Boolean mqtt,
                                                Boolean inDock, Boolean activated, Integer mode,
                                                Boolean missionActive, Boolean paused, Boolean failed,
                                                Boolean charging) {
        DjiStateMapper.Input b = baseline();
        return new DjiStateMapper.Input(
                powered != null ? powered : b.powered(),
                online != null ? online : b.online(),
                mqtt != null ? mqtt : b.mqttConnected(),
                inDock != null ? inDock : b.droneInDock(),
                activated != null ? activated : b.droneActivated(),
                mode != null ? mode : b.droneModeCode(),
                missionActive != null ? missionActive : b.missionActive(),
                paused != null ? paused : b.missionPaused(),
                failed != null ? failed : b.missionFailed(),
                charging != null ? charging : b.charging());
    }

    // ==================== 优先级前缀（020~021） ====================

    @DisplayName("TC-CORE-020：未开机 → CREATED（优先级最高，即使 MQTT 断开）")
    @Test
    void createdWhenPoweredOff() {
        assertEquals(LifecycleState.CREATED,
                DjiStateMapper.map(variant(false, false, false, null, false, null, null, null, null, null)).state());
        // 未开机优先于 MQTT 断连
        assertEquals(LifecycleState.CREATED,
                DjiStateMapper.map(variant(false, true, false, null, null, null, null, null, null, null)).state());
    }

    @DisplayName("TC-CORE-021：MQTT 断连 → NETWORK_LOST（优先于未上线）；重连后回落 OFFLINE；未上线 → OFFLINE")
    @Test
    void offlineAndNetworkLost() {
        // 开机、未上线、MQTT 已连 → OFFLINE
        assertEquals(LifecycleState.OFFLINE,
                DjiStateMapper.map(variant(null, false, null, null, null, null, null, null, null, null)).state());
        // MQTT 断连（在线记录仍在）→ NETWORK_LOST
        assertEquals(LifecycleState.NETWORK_LOST,
                DjiStateMapper.map(variant(null, null, false, null, null, null, null, null, null, null)).state());
        // 断连且未上线 → 仍 NETWORK_LOST（链路优先）
        assertEquals(LifecycleState.NETWORK_LOST,
                DjiStateMapper.map(variant(null, false, false, null, null, null, null, null, null, null)).state());
        // 重连成功（mqtt=true）→ 回落正常判定（未上线 → OFFLINE）
        assertEquals(LifecycleState.OFFLINE,
                DjiStateMapper.map(variant(null, false, true, null, null, null, null, null, null, null)).state());
    }

    // ==================== 待机三态（022） ====================

    @DisplayName("TC-CORE-022：mode=0 在舱未充电 → READY；在舱充电 → CHARGING；舱外 → LANDED")
    @Test
    void standbyTristate() {
        assertTrue(DjiStateMapper.map(baseline()).documented());
        assertEquals(LifecycleState.READY, DjiStateMapper.map(baseline()).state());
        assertEquals(LifecycleState.CHARGING,
                DjiStateMapper.map(variant(null, null, null, null, null, null, null, null, null, true)).state());
        assertEquals(LifecycleState.LANDED,
                DjiStateMapper.map(variant(null, null, null, false, null, null, null, null, null, null)).state());
    }

    // ==================== 起飞准备（023） ====================

    @DisplayName("TC-CORE-023：mode∈{1,2} 且已激活 → ARMED；未激活却处于起飞准备模式 → 未命中（兜底 ONLINE）")
    @Test
    void armedAndUnmappedPrepare() {
        DjiStateMapper.Mapping armed1 = DjiStateMapper.map(variant(null, null, null, null, true, 1, null, null, null, null));
        assertTrue(armed1.documented());
        assertEquals(LifecycleState.ARMED, armed1.state());

        DjiStateMapper.Mapping armed2 = DjiStateMapper.map(variant(null, null, null, null, true, 2, null, null, null, null));
        assertTrue(armed2.documented());
        assertEquals(LifecycleState.ARMED, armed2.state());

        // 未激活（休眠）却是起飞准备模式：组合矛盾 → 未命中
        DjiStateMapper.Mapping unmapped = DjiStateMapper.map(variant(null, null, null, null, false, 1, null, null, null, null));
        assertFalse(unmapped.documented());
        assertEquals(LifecycleState.ONLINE, unmapped.state());
    }

    // ==================== 起飞/任务/飞行（024~026） ====================

    @DisplayName("TC-CORE-024：mode=4（自动起飞）→ TAKING_OFF")
    @Test
    void takingOff() {
        assertEquals(LifecycleState.TAKING_OFF,
                DjiStateMapper.map(variant(null, null, null, null, null, 4, null, null, null, null)).state());
    }

    @DisplayName("TC-CORE-025：mode=5（航线飞行）且有任务 → MISSION")
    @Test
    void mission() {
        DjiStateMapper.Mapping m = DjiStateMapper.map(variant(null, null, null, null, null, 5, true, null, null, null));
        assertTrue(m.documented());
        assertEquals(LifecycleState.MISSION, m.state());
    }

    @DisplayName("TC-CORE-026：mode=5 无任务 → FLYING；3/6/7/8/15/16/17 飞行模式 → FLYING")
    @Test
    void flying() {
        assertEquals(LifecycleState.FLYING,
                DjiStateMapper.map(variant(null, null, null, null, null, 5, false, null, null, null)).state());
        for (int mode : new int[] {3, 6, 7, 8, 15, 16, 17}) {
            DjiStateMapper.Mapping m = DjiStateMapper.map(variant(null, null, null, null, null, mode, null, null, null, null));
            assertTrue(m.documented(), "mode=" + mode + " 应命中飞行中映射");
            assertEquals(LifecycleState.FLYING, m.state(), "mode=" + mode);
        }
    }

    // ==================== 任务标记（027） ====================

    @DisplayName("TC-CORE-027：暂停标记 → PAUSED；失败标记优先于暂停 → FAILED")
    @Test
    void pausedAndFailed() {
        // mode=5 飞行中已暂停 → PAUSED（任务标记优先于飞行模式细分）
        assertEquals(LifecycleState.PAUSED,
                DjiStateMapper.map(variant(null, null, null, null, null, 5, true, true, null, null)).state());
        // 失败与暂停同时存在 → FAILED 优先
        assertEquals(LifecycleState.FAILED,
                DjiStateMapper.map(variant(null, null, null, null, null, 5, true, true, true, null)).state());
    }

    // ==================== 返航/降落/异常（028~029） ====================

    @DisplayName("TC-CORE-028：mode∈{9,13} → RETURNING（13 从方案表，M-2 推断项）")
    @Test
    void returning() {
        for (int mode : new int[] {9, 13}) {
            DjiStateMapper.Mapping m = DjiStateMapper.map(variant(null, null, null, null, null, mode, null, null, null, null));
            assertTrue(m.documented());
            assertEquals(LifecycleState.RETURNING, m.state(), "mode=" + mode);
        }
    }

    @DisplayName("TC-CORE-029：mode∈{10,11,12} → LANDING；mode=14 → OFFLINE；未知模式 → 未命中（兜底 ONLINE）")
    @Test
    void landingAndUnknown() {
        for (int mode : new int[] {10, 11, 12}) {
            DjiStateMapper.Mapping m = DjiStateMapper.map(variant(null, null, null, null, null, mode, null, null, null, null));
            assertTrue(m.documented());
            assertEquals(LifecycleState.LANDING, m.state(), "mode=" + mode);
        }
        // 飞行器未连接（异常态）
        DjiStateMapper.Mapping disconnected = DjiStateMapper.map(variant(null, null, null, null, null, 14, null, null, null, null));
        assertTrue(disconnected.documented());
        assertEquals(LifecycleState.OFFLINE, disconnected.state());
        // 未知模式码（如新增枚举）：未命中 → 兜底 ONLINE
        DjiStateMapper.Mapping unknown = DjiStateMapper.map(variant(null, null, null, null, null, 99, null, null, null, null));
        assertFalse(unknown.documented());
        assertEquals(LifecycleState.ONLINE, unknown.state());
    }
}
