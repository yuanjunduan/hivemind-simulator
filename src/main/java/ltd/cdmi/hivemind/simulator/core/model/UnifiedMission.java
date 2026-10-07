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

package ltd.cdmi.hivemind.simulator.core.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;
import java.util.Map;

/**
 * 统一任务模型（快照不可变）。
 * <p>state 为统一任务状态名（IDLE/PREPARING/EXECUTING/PAUSED/COMPLETED/CANCELED/FAILED），
 * 与 core/engine/mission 的 MissionState 枚举名一致；此处用字符串避免 model → engine 反向依赖。
 * DJI 特有步骤（如 {7,24,26,27,29,35}）与原始进度放 vendorExt，由 Adapter 解释。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class UnifiedMission {

    /** 任务 ID（厂商内唯一） */
    private final String missionId;

    /** 任务名称 */
    private final String name;

    /** 统一任务状态名（见类注释） */
    private final String state;

    /** 任务进度 (0-100) */
    private final int percent;

    /** 当前执行到的航点序号（-1=未开始） */
    private final int currentWaypointIndex;

    /** 航线航点列表 */
    private final List<Waypoint> waypoints;

    /** 厂商扩展字段（如 dji.step / dji.percent 原始值） */
    private final Map<String, Object> vendorExt;
}
