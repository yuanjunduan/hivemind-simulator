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
 * 电池信息（统一模型）。
 * <p>chargeState 统一为 0=未充电, 1=充电中, 2=充满（与 DJI drone_charge_state 对齐）；
 * 电压统一毫伏，避免上层处理 V/mV 单位差异。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class BatteryInfo {

    /** 电量百分比 (0-100) */
    private final int percent;

    /** 电压 (mV) */
    private final int voltageMv;

    /** 电池温度 (℃) */
    private final int temperatureC;

    /** 充电状态：0=未充电, 1=充电中, 2=充满 */
    private final int chargeState;
}
