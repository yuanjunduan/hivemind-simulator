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

import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionPhase;

/**
 * DJI 6 步序列 ↔ 统一任务阶段映射表（实施方案 §2.7）。
 *
 * <p>现有 flighttask_progress 的 {@code progress.current_step} 是 DJI 展现层枚举
 * （Dock 型号间存在偏移），本映射器是其唯一事实源；DJI 特有步骤值不进 core，
 * 统一侧只使用 {@link MissionPhase} 与 0~100 进度。</p>
 *
 * <p>型号差异（依据 [Dock1/Dock2/Dock3 wayline.html] current_step 枚举）：
 * Dock2/3 比 Dock1 多"图传远程对频""起飞机场检查降落机场准备状态"两步，
 * step 值整体偏移 +2（Dock1 的 24=进入返航检查，在 Dock2/3 下 24=触发执行航线）。</p>
 *
 * <pre>
 * 序列（Dock1）：   7=开机检查+开盖 → 22=触发执行航线 → 24=进入返航检查 → 25=降落机场 → 27=退出工作模式 → 33=通知结果
 * 序列（Dock2/3）： 7=开机检查+开盖 → 24=触发执行航线 → 26=进入返航检查 → 27=降落机场 → 29=退出工作模式 → 35=通知结果
 * 统一阶段：        PREPARING(5%)  → EXECUTING(20%)  → RETURNING(60%)   → LANDING(80%) → LANDING(90%)   → COMPLETED(100%)
 * </pre>
 */
public final class DjiMissionStepMapper {

    private DjiMissionStepMapper() {
    }

    /** Dock1 任务步骤序列（序号语义：0=开机检查 → 5=通知任务结果） */
    private static final int[] SEQUENCE_DOCK1 = {7, 22, 24, 25, 27, 33};
    /** Dock2/Dock3 任务步骤序列（step 值整体 +2，不含"航线执行中(23)"以保证三版本 index 语义一致） */
    private static final int[] SEQUENCE_DOCK2_3 = {7, 24, 26, 27, 29, 35};
    /** 6 步对应的展现进度（各 Dock 版本通用，与步骤序列一一对应） */
    private static final int[] PERCENTS = {5, 20, 60, 80, 90, 100};
    /** 6 步对应的统一阶段（index 对齐；index 4 为降落收尾"退出工作模式"，仍属 LANDING） */
    private static final MissionPhase[] PHASES = {
            MissionPhase.PREPARING, MissionPhase.EXECUTING, MissionPhase.RETURNING,
            MissionPhase.LANDING, MissionPhase.LANDING, MissionPhase.COMPLETED};

    /**
     * 单步映射结果。
     *
     * @param index   统一步序（0~5，两套序列同构）
     * @param phase   统一任务阶段
     * @param percent 展现进度（5/20/60/80/90/100）
     */
    public record StepMapping(int index, MissionPhase phase, int percent) {
    }

    /**
     * 按 Dock 型号返回 6 步步骤序列（防御拷贝，调用方可安全修改）。
     *
     * @param dockType Dock 型号（null 视为 Dock2/3 序列）
     */
    public static int[] sequenceOf(DockModel dockType) {
        return (dockType == DockModel.DOCK1 ? SEQUENCE_DOCK1 : SEQUENCE_DOCK2_3).clone();
    }

    /** 步序总数（两套序列均为 6 步） */
    public static int stepCount() {
        return PERCENTS.length;
    }

    /**
     * 按统一步序 index 返回展现进度（钳位到 [0, 5]，供取消/越界等边界场景复用）。
     *
     * @param index 统一步序
     */
    public static int percentAt(int index) {
        return PERCENTS[Math.max(0, Math.min(index, PERCENTS.length - 1))];
    }

    /**
     * DJI current_step → 统一映射（型号相关：同一步骤值在不同 Dock 下语义不同）。
     *
     * @param dockType Dock 型号
     * @param djiStep  current_step 原始值
     * @return 命中的映射；非本型号的步骤值返回 null（调用方决定诊断/兜底）
     */
    public static StepMapping mapStep(DockModel dockType, int djiStep) {
        int[] seq = sequenceOf(dockType);
        for (int i = 0; i < seq.length; i++) {
            if (seq[i] == djiStep) {
                return new StepMapping(i, PHASES[i], PERCENTS[i]);
            }
        }
        return null;
    }

    /**
     * 统一阶段 → DJI current_step（取该阶段首个步骤；LANDING 取"降落机场"档）。
     *
     * @param dockType Dock 型号
     * @param phase    统一任务阶段
     * @return 命中的步骤值；无对应（phase 为 null）返回 -1
     */
    public static int stepOf(DockModel dockType, MissionPhase phase) {
        if (phase == null) {
            return -1;
        }
        int[] seq = sequenceOf(dockType);
        for (int i = 0; i < PHASES.length; i++) {
            if (PHASES[i] == phase) {
                return seq[i];
            }
        }
        return -1;
    }
}
