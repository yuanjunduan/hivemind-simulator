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

/**
 * 仿真事件基类（实施方案 §2.4）。
 * <p>所有仿真内事件携带两个公共字段：</p>
 * <ul>
 *   <li>{@code deviceId}——事件关联设备（{@code vendor:deviceSn} 约定，见
 *       {@code UnifiedDevice.deviceIdOf}）；</li>
 *   <li>{@code occurredAtInstant}——事件发生时刻，必须是<b>逻辑时间</b>（来自
 *       {@link ltd.cdmi.hivemind.simulator.core.clock.SimulationClock}，由
 *       {@link EventBus#logicalNow()} 取），禁止使用墙钟——这是时间加速与确定性回放的前提。</li>
 * </ul>
 * <p>事件对象不可变；发布经 {@link EventBus}（Spring {@code ApplicationEventPublisher} 门面）。</p>
 */
@Getter
public abstract class SimulationEvent {

    /** 事件关联设备（vendor:deviceSn） */
    private final String deviceId;

    /** 事件发生时刻（逻辑时间；来自仿真时钟而非墙钟） */
    private final Instant occurredAtInstant;

    protected SimulationEvent(String deviceId, Instant occurredAtInstant) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.occurredAtInstant = Objects.requireNonNull(occurredAtInstant, "occurredAtInstant");
    }
}
