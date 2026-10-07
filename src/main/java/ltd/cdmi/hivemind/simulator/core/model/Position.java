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

/**
 * 位置信息（统一模型）。
 * <p>统一为 WGS-84 经纬度与米制高度：altitude 为相对起飞点高度，elevation 为海拔，
 * 与 DJI OSD 的 height/elevation 语义一致，避免上层做厂商换算。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class Position {

    /** 经度 */
    private final double longitude;

    /** 纬度 */
    private final double latitude;

    /** 相对起飞点高度 (m) */
    private final double altitude;

    /** 海拔高度 (m) */
    private final double elevation;
}
