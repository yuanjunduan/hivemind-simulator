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

package ltd.cdmi.hivemind.simulator.adapter.dji;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionPhase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DJI 6 步序列 ↔ 统一任务阶段映射表单测（TC-ADP-020~026，实施方案 §2.7）。
 * <p>覆盖：Dock1 / Dock2-3 两套序列的全组合映射、同步骤值型号语义差异、
 * 未命中兜底、percent 钳位、反向映射（阶段→步骤）与序列防御拷贝。</p>
 */
class DjiMissionStepMapperTest {

    @DisplayName("TC-ADP-020：Dock2/3 序列全映射——7/24/26/27/29/35 → PREPARING..COMPLETED（5/20/60/80/90/100）")
    @Test
    void mapsDock2And3Sequence() {
        int[] steps = {7, 24, 26, 27, 29, 35};
        MissionPhase[] phases = {MissionPhase.PREPARING, MissionPhase.EXECUTING, MissionPhase.RETURNING,
                MissionPhase.LANDING, MissionPhase.LANDING, MissionPhase.COMPLETED};
        int[] percents = {5, 20, 60, 80, 90, 100};

        for (int i = 0; i < steps.length; i++) {
            DjiMissionStepMapper.StepMapping mapping = DjiMissionStepMapper.mapStep(DockModel.DOCK2, steps[i]);
            assertNotNull(mapping, "Dock2 step=" + steps[i] + " 应命中");
            assertEquals(i, mapping.index(), "统一步序");
            assertEquals(phases[i], mapping.phase(), "统一阶段");
            assertEquals(percents[i], mapping.percent(), "展现进度");

            DjiMissionStepMapper.StepMapping dock3 = DjiMissionStepMapper.mapStep(DockModel.DOCK3, steps[i]);
            assertNotNull(dock3, "Dock3 step=" + steps[i] + " 应命中");
            assertEquals(mapping, dock3, "Dock2/Dock3 映射一致");
        }
    }

    @DisplayName("TC-ADP-021：Dock1 序列全映射——7/22/24/25/27/33 → 同阶段链（step 值整体 -2 偏移）")
    @Test
    void mapsDock1Sequence() {
        int[] steps = {7, 22, 24, 25, 27, 33};
        MissionPhase[] phases = {MissionPhase.PREPARING, MissionPhase.EXECUTING, MissionPhase.RETURNING,
                MissionPhase.LANDING, MissionPhase.LANDING, MissionPhase.COMPLETED};
        int[] percents = {5, 20, 60, 80, 90, 100};

        for (int i = 0; i < steps.length; i++) {
            DjiMissionStepMapper.StepMapping mapping = DjiMissionStepMapper.mapStep(DockModel.DOCK1, steps[i]);
            assertNotNull(mapping, "Dock1 step=" + steps[i] + " 应命中");
            assertEquals(i, mapping.index(), "统一步序");
            assertEquals(phases[i], mapping.phase(), "统一阶段");
            assertEquals(percents[i], mapping.percent(), "展现进度");
        }
    }

    @DisplayName("TC-ADP-022：同步骤值型号语义差异——24：Dock1=RETURNING(60) vs Dock2/3=EXECUTING(20)；25 仅 Dock1")
    @Test
    void sameStepValueDiffersByDockModel() {
        DjiMissionStepMapper.StepMapping dock1 = DjiMissionStepMapper.mapStep(DockModel.DOCK1, 24);
        assertEquals(MissionPhase.RETURNING, dock1.phase());
        assertEquals(60, dock1.percent());

        DjiMissionStepMapper.StepMapping dock2 = DjiMissionStepMapper.mapStep(DockModel.DOCK2, 24);
        assertEquals(MissionPhase.EXECUTING, dock2.phase());
        assertEquals(20, dock2.percent());

        // 25=飞行器降落机场：仅 Dock1 序列包含（Dock2 文档跳过 step 25）
        assertNotNull(DjiMissionStepMapper.mapStep(DockModel.DOCK1, 25));
        assertNull(DjiMissionStepMapper.mapStep(DockModel.DOCK2, 25), "Dock2 不含 step 25");
    }

    @DisplayName("TC-ADP-023：未命中步骤值（23/99/0/-1）→ null（调用方决定诊断/兜底）")
    @Test
    void unmappedStepsReturnNull() {
        for (int step : new int[]{23, 99, 0, -1}) {
            assertNull(DjiMissionStepMapper.mapStep(DockModel.DOCK1, step), "Dock1 step=" + step);
            assertNull(DjiMissionStepMapper.mapStep(DockModel.DOCK2, step), "Dock2 step=" + step);
        }
    }

    @DisplayName("TC-ADP-024：percentAt 钳位到 [0,5]（取消/越界等边界场景复用，与改造前 min 保护一致）")
    @Test
    void percentAtClampsIndex() {
        assertEquals(5, DjiMissionStepMapper.percentAt(-5));
        assertEquals(5, DjiMissionStepMapper.percentAt(0));
        assertEquals(20, DjiMissionStepMapper.percentAt(1));
        assertEquals(60, DjiMissionStepMapper.percentAt(2));
        assertEquals(80, DjiMissionStepMapper.percentAt(3));
        assertEquals(90, DjiMissionStepMapper.percentAt(4));
        assertEquals(100, DjiMissionStepMapper.percentAt(5));
        assertEquals(100, DjiMissionStepMapper.percentAt(6));
        assertEquals(100, DjiMissionStepMapper.percentAt(99));
        assertEquals(6, DjiMissionStepMapper.stepCount());
    }

    @DisplayName("TC-ADP-025：反向映射 stepOf——阶段取首个步骤；null 阶段 → -1")
    @Test
    void reverseLooksUpFirstStepOfPhase() {
        assertEquals(7, DjiMissionStepMapper.stepOf(DockModel.DOCK2, MissionPhase.PREPARING));
        assertEquals(24, DjiMissionStepMapper.stepOf(DockModel.DOCK2, MissionPhase.EXECUTING));
        assertEquals(26, DjiMissionStepMapper.stepOf(DockModel.DOCK2, MissionPhase.RETURNING));
        assertEquals(27, DjiMissionStepMapper.stepOf(DockModel.DOCK2, MissionPhase.LANDING), "LANDING 取降落机场档");
        assertEquals(35, DjiMissionStepMapper.stepOf(DockModel.DOCK2, MissionPhase.COMPLETED));

        assertEquals(7, DjiMissionStepMapper.stepOf(DockModel.DOCK1, MissionPhase.PREPARING));
        assertEquals(22, DjiMissionStepMapper.stepOf(DockModel.DOCK1, MissionPhase.EXECUTING));
        assertEquals(24, DjiMissionStepMapper.stepOf(DockModel.DOCK1, MissionPhase.RETURNING));
        assertEquals(25, DjiMissionStepMapper.stepOf(DockModel.DOCK1, MissionPhase.LANDING));
        assertEquals(33, DjiMissionStepMapper.stepOf(DockModel.DOCK1, MissionPhase.COMPLETED));

        assertEquals(-1, DjiMissionStepMapper.stepOf(DockModel.DOCK1, null));
    }

    @DisplayName("TC-ADP-026：sequenceOf 防御拷贝（修改返回值不影响内部）；null 型号按 Dock2/3 序列")
    @Test
    void sequenceOfReturnsDefensiveCopy() {
        int[] dock2 = DjiMissionStepMapper.sequenceOf(DockModel.DOCK2);
        assertArrayEquals(new int[]{7, 24, 26, 27, 29, 35}, dock2);
        dock2[0] = 999; // 修改拷贝
        assertArrayEquals(new int[]{7, 24, 26, 27, 29, 35},
                DjiMissionStepMapper.sequenceOf(DockModel.DOCK2), "内部常量不应被外部修改");

        assertArrayEquals(new int[]{7, 22, 24, 25, 27, 33},
                DjiMissionStepMapper.sequenceOf(DockModel.DOCK1));
        assertArrayEquals(new int[]{7, 24, 26, 27, 29, 35},
                DjiMissionStepMapper.sequenceOf(null), "null 型号按 Dock2/3 序列（与改造前 stepSequence 一致）");
    }
}
