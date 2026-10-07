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

import ltd.cdmi.hivemind.simulator.core.model.Alarm;

/**
 * 告警触发事件（实施方案 §2.4）。
 * <p>发布时机：HMS/故障触发（如低电量、RTK 异常）。携带统一模型的 {@link Alarm}，
 * 订阅方可直接透传至平台侧告警通道。</p>
 */
@Getter
public final class AlarmRaisedEvent extends SimulationEvent {

    /** 告警内容（统一模型，不可变） */
    private final Alarm alarm;

    public AlarmRaisedEvent(String deviceId, Instant occurredAtInstant, Alarm alarm) {
        super(deviceId, occurredAtInstant);
        this.alarm = Objects.requireNonNull(alarm, "alarm");
    }
}
