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
 * GPS 定位信息（统一模型）。
 * <p>positionState：0=未定位, 1=GPS 定位, 2=RTK 固定解（DJI position_state 枚举语义，
 * 跨厂商通用；其他厂商的解算状态差异进 vendorExt 由 Adapter 解释）。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class GpsInfo {

    /** 定位状态：0=未定位, 1=GPS 定位, 2=RTK 固定解 */
    private final int positionState;

    /** 卫星数量 */
    private final int satellites;
}
