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

/**
 * 航点（统一模型）。
 * <p>speed=0 表示沿用航线巡航速度；actions 为统一动作名列表（如 PHOTO/HOVER），
 * 厂商特有动作编码由 Adapter 在转换时解释，不进本模型。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class Waypoint {

    /** 航点序号（从 0 开始） */
    private final int index;

    /** 经度 */
    private final double longitude;

    /** 纬度 */
    private final double latitude;

    /** 相对起飞点高度 (m) */
    private final double altitude;

    /** 到达速度 (m/s)，0=沿用巡航速度 */
    private final double speed;

    /** 航点动作（统一语义名列表，可为空） */
    private final List<String> actions;
}
