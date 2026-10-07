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

package ltd.cdmi.hivemind.simulator.core.fault;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.FaultInjectedEvent;

/**
 * 网络故障注入器（T2.4）——消息级语义（实施方案 §4.3，文档如实声明）。
 *
 * <p>注入状态由 {@code MqttClientManager} 在消息收发路径上查询：</p>
 * <ul>
 *   <li>{@code network_disconnect}：收发双向阻断（{@link #isDisconnected()}）——模拟断网；</li>
 *   <li>{@code network_reconnect}：解除断网；</li>
 *   <li>{@code network_delay}：下行消息延迟 {@code ms}（+ 可选 {@code jitterMs} 抖动）后分发；</li>
 *   <li>{@code network_drop}：下行消息按 {@code percent} 概率丢弃。</li>
 * </ul>
 *
 * <p>语义边界：仅作用于本模拟器进程内的消息路径（不发真实网络故障），
 * 与现有 {@code /disconnect} 底层解耦，避免影响 MQTT 连接生命周期。</p>
 */
@Component
public class NetworkFaultInjector implements FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(NetworkFaultInjector.class);

    private final EventBus eventBus;

    /** 断网标志（收发双向阻断） */
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    /** 下行丢弃率（0-100） */
    private final AtomicLong dropPercent = new AtomicLong(0);
    /** 下行基础延迟（ms） */
    private final AtomicLong delayMillis = new AtomicLong(0);
    /** 下行延迟抖动上限（ms，0=无抖动） */
    private final AtomicLong jitterMillis = new AtomicLong(0);

    public NetworkFaultInjector(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public String name() {
        return "network";
    }

    @Override
    public void inject(String action, String deviceId, Map<String, Object> params) {
        switch (action) {
            case "network_disconnect" -> disconnected.set(true);
            case "network_reconnect" -> disconnected.set(false);
            case "network_delay" -> {
                delayMillis.set(asLong(params.get("ms")));
                jitterMillis.set(asLong(params.get("jitterMs")));
            }
            case "network_drop" -> dropPercent.set(Math.min(100, Math.max(0, asLong(params.get("percent")))));
            default -> {
                log.warn("NetworkFaultInjector 收到未支持的 action: {}", action);
                return;
            }
        }
        log.info("[故障注入-网络] action={}, device={}, disconnected={}, dropPercent={}, delay={}ms(+{}ms)",
                action, deviceId, disconnected.get(), dropPercent.get(), delayMillis.get(), jitterMillis.get());
        eventBus.publish(new FaultInjectedEvent(deviceId, eventBus.logicalNow(),
                action, params, "network"));
    }

    /** 收发是否被阻断（断网注入生效） */
    public boolean isDisconnected() {
        return disconnected.get();
    }

    /**
     * 下行消息是否应丢弃：断网直接丢弃；否则按 {@code percent} 概率丢弃。
     */
    public boolean shouldDrop() {
        if (disconnected.get()) {
            return true;
        }
        long percent = dropPercent.get();
        return percent > 0 && ThreadLocalRandom.current().nextLong(100) < percent;
    }

    /** 下行消息延迟（ms）：基础延迟 + 抖动随机值；无注入返回 0 */
    public long delayMillis() {
        long base = delayMillis.get();
        if (base <= 0) {
            return 0;
        }
        long jitter = jitterMillis.get();
        if (jitter > 0) {
            base += ThreadLocalRandom.current().nextLong(jitter + 1);
        }
        return base;
    }

    @Override
    public void clear() {
        disconnected.set(false);
        dropPercent.set(0);
        delayMillis.set(0);
        jitterMillis.set(0);
    }

    /** 快照（供状态查询与测试断言） */
    public Snapshot snapshot() {
        return new Snapshot(disconnected.get(), dropPercent.get(), delayMillis.get(), jitterMillis.get());
    }

    /** 注入状态快照 */
    public record Snapshot(boolean disconnected, long dropPercent, long delayMillis, long jitterMillis) {
    }

    private static long asLong(Object raw) {
        return raw instanceof Number n ? n.longValue() : 0L;
    }
}
