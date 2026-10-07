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

package ltd.cdmi.hivemind.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.EventMethod;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.core.clock.SimulationClockImpl;
import ltd.cdmi.hivemind.simulator.core.engine.flight.lite.LiteFlightEngine;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionPlan;
import ltd.cdmi.hivemind.simulator.core.model.Waypoint;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加速模式端到端任务流集成测试（TC-INT-001，T1.10 / 实施方案 §3.1）。
 *
 * <p><b>验收映射</b>：T1.10 要求"10x 下 30 分钟任务 &lt;3 分钟完成；报文频率 ≤ cap；平台侧无异常"。
 * 本测试以模拟器内置 6 步任务流（DOCK3 序列 {@code {7,24,26,27,29,35}}，3s/步 → 18s 逻辑时长）
 * 为代表：10x 加速下应约 1.8s 物理完成（时长与倍率线性对应——30 分钟任务的理论完成时长
 * ≈ 3 分钟）。若加速未生效则需 18s 物理，测试以 8s 轮询上限 + 5s 物理耗时断言保证区分度
 * （不做 3 分钟慢测）。</p>
 *
 * <p><b>覆盖点</b>：{@link SimulationClockImpl}(10.0) + {@link ClockScheduler}（20ms 物理 tick，
 * 测试手动驱动）驱动的 {@link WaylineTaskSimulator#startMissionInternal} 全流程——
 * 6 次 FIXED_RATE 进度触发（逻辑 3s 间隔自动缩至物理 300ms）、离散事件不降采样
 * （flighttask_progress ≥6 条含 100% + return_home_info）、任务完成后状态复位。</p>
 */
class AcceleratedMissionFlowTest {

    /** 加速倍率（与 T1.10 验收一致） */
    private static final double SPEED = 10.0;

    /** 物理 tick 周期（毫秒） */
    private static final long TICK_MILLIS = 20;

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null,
                null,
                null
        );
    }

    private RuntimeConfig testRuntimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
    }

    @DisplayName("TC-INT-001：10x 加速下 18s 逻辑任务流约 1.8s 物理完成，离散事件不降采样")
    @Test
    void acceleratedMissionCompletesWithinPhysicalBudget() throws Exception {
        // given：10x 仿真时钟 + 20ms 物理 tick 统一调度器（手动驱动，不启后台线程）
        SimulationClockImpl clock = new SimulationClockImpl(SPEED);
        ClockScheduler scheduler = new ClockScheduler(clock,
                new ClockProperties(null, new ClockProperties.Scheduler(TICK_MILLIS, 5), null));

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = new DeviceState();
        state.setOnline(true);
        WaylineTaskSimulator wayline = new WaylineTaskSimulator(
                testProps(), mqtt, state, new ObjectMapper(),
                Mockito.mock(ServiceCommandHandler.class),
                Mockito.mock(MediaUploadSimulator.class),
                testRuntimeConfig(),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(), scheduler, new LiteFlightEngine());
        wayline.init();

        // when：经内部意图入口启动内置任务流（6 步 × 3s 逻辑 = 18s 逻辑）
        long startedNanos = System.nanoTime();
        Map<String, Object> reply = wayline.startMissionInternal(new MissionPlan(
                "intent-acc-test", List.of(
                        Waypoint.builder().index(0).longitude(104.07).latitude(30.67)
                                .altitude(50.0).speed(10.0).build(),
                        Waypoint.builder().index(1).longitude(104.08).latitude(30.68)
                                .altitude(60.0).speed(10.0).build()),
                10.0, Map.of()));
        assertEquals(0, reply.get("result"), "内部入口应返回 result=0");
        assertTrue(wayline.isMissionActive(), "启动后任务应处于激活状态");

        // 手动驱动物理 tick 直至完成；8s 物理上限（10x 下预期 ≈1.8s，未加速需 18s）
        long deadlineNanos = System.nanoTime() + 8_000_000_000L;
        while (wayline.isMissionActive() && System.nanoTime() < deadlineNanos) {
            scheduler.tick();
            Thread.sleep(TICK_MILLIS);
        }
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;

        // then：加速生效——完成时间远低于未加速的 18s
        assertFalse(wayline.isMissionActive(), "10x 加速下任务应完成（未加速需 18s 物理，超 8s 上限）");
        assertTrue(elapsedMillis < 5_000,
                "18s 逻辑任务在 10x 下应约 1.8s 物理完成，实际 " + elapsedMillis + "ms");

        // 离散事件不降采样（ADR-5）：6 步进度事件齐全 + 完成事件
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mqtt, Mockito.atLeast(6)).publish(Mockito.anyString(), payloads.capture());
        long progressEvents = payloads.getAllValues().stream()
                .filter(p -> p.contains(EventMethod.FLIGHTTASK_PROGRESS.methodName())).count();
        assertEquals(6, progressEvents, "6 步任务应发 6 条 flighttask_progress（不降采样）");
        assertTrue(payloads.getAllValues().stream().anyMatch(p -> p.contains("\"percent\":100")),
                "应包含 100% 进度报文");
        assertTrue(payloads.getAllValues().stream()
                        .anyMatch(p -> p.contains(EventMethod.RETURN_HOME_INFO.methodName())),
                "任务完成应发 return_home_info 事件");

        // 任务完成后归舱复位（completeTask → resetDroneToHomeState）
        assertTrue(state.isDroneInDock(), "任务完成后飞行器应归舱");
        assertEquals(0, state.getDroneModeCode(), "归舱后飞行器模式应为待机(0)");
        assertEquals(0, state.getDockModeCode(), "归舱后机场模式应为待机(0)");
    }
}
