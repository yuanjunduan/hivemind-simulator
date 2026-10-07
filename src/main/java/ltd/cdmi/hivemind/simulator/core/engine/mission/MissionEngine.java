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

package ltd.cdmi.hivemind.simulator.core.engine.mission;

import java.time.Instant;

/**
 * 任务引擎接口（实施方案 §2.7）。
 *
 * <p>W1 提供本接口与轻量实现 {@link lite.LiteMissionEngine}（状态机骨架 + 统一进度推进）；
 * W2 场景引擎经统一意图 API 驱动，{@code advance} 由 {@code ClockScheduler} 以 FIXED_RATE
 * 按逻辑时间挂接（W1 测试手动调用）。</p>
 *
 * <p>DJI 6 步序列（current_step）与统一状态/阶段的双向映射由 adapter/dji 的
 * {@code DjiMissionStepMapper} 承担；本接口只使用统一语义，不出现厂商枚举。</p>
 */
public interface MissionEngine {

    /**
     * 加载任务计划：重置进度并进入 {@link MissionState#PREPARING}（已加载可重新 load 覆盖）。
     *
     * @param plan 任务计划（不可变）
     */
    void load(MissionPlan plan);

    /** 开始执行（PREPARING → EXECUTING；其他状态忽略）。 */
    void start();

    /** 暂停（EXECUTING → PAUSED；阶段保留；其他状态忽略）。 */
    void pause();

    /** 恢复（PAUSED → EXECUTING；阶段保留；其他状态忽略）。 */
    void resume();

    /** 取消（活动态 → CANCELED；终态；IDLE/终态忽略）。 */
    void cancel();

    /**
     * 推进进度（由调度按逻辑时间驱动；W1 测试手动调用）。
     * <p>非 EXECUTING 状态调用不做任何事（幂等）；阶段链：
     * EXECUTING（按航点推进）→ RETURNING → LANDING → COMPLETED。</p>
     *
     * @param logicalNow 当前逻辑时间（用于事件时间戳与进度语义）
     */
    void advance(Instant logicalNow);

    /** 当前快照（不可变，任意时刻可调用）。 */
    MissionSnapshot snapshot();
}
