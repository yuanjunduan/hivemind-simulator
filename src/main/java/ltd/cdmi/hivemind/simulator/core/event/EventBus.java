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

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.clock.SimulationClock;

/**
 * 仿真事件总线（实施方案 §2.4）——Spring {@link ApplicationEventPublisher} 的轻量门面。
 * <p>零新依赖：订阅方使用标准 {@code @EventListener}；本门面存在的意义是集中约束
 * "事件时间戳必须来自逻辑时钟"（{@link #logicalNow()}）并为未来切换异步发布保留替换点。</p>
 *
 * <p>订阅方约定（§2.4）：W1 只有 TelemetryWebSocketHandler 与诊断日志订阅；
 * W2 起场景引擎、E2E 编排器订阅。当前为同步发布：订阅方异常直接传播给发布方
 * （确定性语义，便于测试定位）。</p>
 */
@Component
public class EventBus {

    private final ApplicationEventPublisher publisher;
    private final SimulationClock clock;

    public EventBus(ApplicationEventPublisher publisher, SimulationClock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * 当前逻辑时间——构造事件时统一经此取值，确保 {@code occurredAtInstant}
     * 来自仿真时钟而非墙钟（REALTIME 下两者近似相等，加速下仅逻辑时间有意义）。
     */
    public Instant logicalNow() {
        return clock.now();
    }

    /**
     * 发布事件（同步）。
     *
     * @param event 仿真事件（不可变；时间戳应由 {@link #logicalNow()} 生成）
     */
    public void publish(SimulationEvent event) {
        publisher.publishEvent(Objects.requireNonNull(event, "event"));
    }
}
