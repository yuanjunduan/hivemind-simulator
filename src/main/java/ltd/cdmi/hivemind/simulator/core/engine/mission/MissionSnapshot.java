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
 * 任务快照（不可变；实施方案 §2.7：state / percent / currentWaypoint / stepRef）。
 *
 * @param state           统一任务状态
 * @param phase           统一任务阶段（未加载时为 null）
 * @param percent         统一进度 0~100（执行阶段按航点均分，返航 90、降落 95、完成 100）
 * @param currentWaypoint 当前执行到的航点序号（-1=未开始）
 * @param stepRef         厂商步骤引用（DJI current_step 值；统一侧引擎不产生时为 null，由 adapter 投影填充）
 */
public record MissionSnapshot(MissionState state, MissionPhase phase, int percent,
                              int currentWaypoint, Integer stepRef) {
}
