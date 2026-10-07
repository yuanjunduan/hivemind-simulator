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

package ltd.cdmi.hivemind.simulator.core.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import lombok.Getter;

/**
 * 故障注入事件（实施方案 §2.4）。
 * <p>发布时机：故障注入器执行后（W2 场景引擎）。{@code params} 为故障类型相关参数
 * （如断网时长、GPS 漂移幅度），防御性拷贝为不可变 Map。</p>
 */
@Getter
public final class FaultInjectedEvent extends SimulationEvent {

    /** 故障类型（场景引擎定义的故障码，如 GPS_LOSS / NETWORK_DROP） */
    private final String faultType;

    /** 故障参数（不可变；缺省为空 Map） */
    private final Map<String, Object> params;

    /** 注入目标（设备 ID 或子系统标识） */
    private final String target;

    public FaultInjectedEvent(String deviceId, Instant occurredAtInstant,
                              String faultType, Map<String, Object> params, String target) {
        super(deviceId, occurredAtInstant);
        this.faultType = Objects.requireNonNull(faultType, "faultType");
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.target = target;
    }
}
