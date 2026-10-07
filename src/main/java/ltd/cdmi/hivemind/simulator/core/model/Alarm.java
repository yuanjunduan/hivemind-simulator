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

import java.time.Instant;
import java.util.Map;

/**
 * 告警模型（统一模型）。
 * <p>code 为统一告警码（如 LOW_BATTERY/GPS_LOST/RTK_LOST），level 为统一等级
 * （1=提示, 2=警告, 3=严重）；DJI HMS 原始字段（hms_id/module/hms_key...）放 vendorExt。
 * raisedAt 为逻辑时间（来自 SimulationClock），不是墙钟时间。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class Alarm {

    /** 告警 ID（统一唯一标识） */
    private final String alarmId;

    /** 统一告警码 */
    private final String code;

    /** 告警等级：1=提示, 2=警告, 3=严重 */
    private final int level;

    /** 告警描述 */
    private final String message;

    /** 关联设备 ID（UnifiedDevice.deviceId） */
    private final String deviceId;

    /** 发生时间（逻辑时间） */
    private final Instant raisedAt;

    /** 厂商扩展字段（如 DJI HMS 原始结构） */
    private final Map<String, Object> vendorExt;
}
