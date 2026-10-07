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
 * 姿态信息（统一模型）。
 * <p>单位：度。pitch 俯仰、roll 横滚、yaw 偏航（0=正北，顺时针为正），
 * 与 DJI OSD attitude_head 语义一致。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class Attitude {

    /** 俯仰角 (°) */
    private final double pitch;

    /** 横滚角 (°) */
    private final double roll;

    /** 偏航角 (°) */
    private final double yaw;
}
