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

import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;

/**
 * 设备生命周期状态变化事件（实施方案 §2.4）。
 * <p>发布时机：状态机（T1.5 {@code DeviceStateMachine}）只读推导出状态变化时。
 * 观察者先行（ADR-8）：本事件为只读推导结果的通知，不拦截也不修改现有写路径。</p>
 */
@Getter
public final class DeviceLifecycleChangedEvent extends SimulationEvent {

    /** 变化前状态 */
    private final LifecycleState from;

    /** 变化后状态 */
    private final LifecycleState to;

    public DeviceLifecycleChangedEvent(String deviceId, Instant occurredAtInstant,
                                       LifecycleState from, LifecycleState to) {
        super(deviceId, occurredAtInstant);
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
    }
}
