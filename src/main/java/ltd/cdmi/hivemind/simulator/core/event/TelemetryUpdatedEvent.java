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
import java.util.Objects;

import lombok.Getter;

import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * 遥测快照更新事件（实施方案 §2.4）。
 * <p>发布时机：每次 OSD 投影后（0.5Hz 逻辑上限，ADR-5 报文封顶对事件同样适用）。
 * 快照为不可变的 {@link UnifiedDevice}，订阅方可安全跨线程持有。</p>
 */
@Getter
public final class TelemetryUpdatedEvent extends SimulationEvent {

    /** 统一设备快照（不可变） */
    private final UnifiedDevice device;

    public TelemetryUpdatedEvent(String deviceId, Instant occurredAtInstant, UnifiedDevice device) {
        super(deviceId, occurredAtInstant);
        this.device = Objects.requireNonNull(device, "device");
    }
}
