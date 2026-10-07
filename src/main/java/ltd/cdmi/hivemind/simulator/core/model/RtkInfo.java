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
 * RTK 信息（统一模型，可为空）。
 * <p>rtkState 为统一解算状态（0=无固定解, 1=固定解）；DJI 侧原始状态码进 vendorExt。
 * baseStationSn 记录 RTK 基站序列号（未模拟基站时为 null）。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class RtkInfo {

    /** RTK 解算状态：0=无固定解, 1=固定解 */
    private final int rtkState;

    /** RTK 基站序列号（可为 null） */
    private final String baseStationSn;
}
