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

/**
 * 统一任务状态（实施方案 §2.7）。
 *
 * <p>任务全生命周期：{@link #IDLE}（未加载）→ {@link #PREPARING}（已加载/准备中）
 * → {@link #EXECUTING}（执行中）↔ {@link #PAUSED}（暂停）→ {@link #COMPLETED}（完成）；
 * 异常分支 {@link #CANCELED}（取消）/{@link #FAILED}（失败）。</p>
 *
 * <p>枚举名与 {@code UnifiedMission.state} 字符串同源（平台侧按名解释，不做 ordinal 约定）。</p>
 */
public enum MissionState {
    IDLE, PREPARING, EXECUTING, PAUSED, COMPLETED, CANCELED, FAILED;

    /** 终态（不可再迁移；再次 load 可覆盖重置） */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED || this == FAILED;
    }
}
