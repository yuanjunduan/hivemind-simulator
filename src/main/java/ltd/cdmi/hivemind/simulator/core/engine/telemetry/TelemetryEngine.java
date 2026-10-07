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

package ltd.cdmi.hivemind.simulator.core.engine.telemetry;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.TelemetryUpdatedEvent;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * 遥测引擎（实施方案 §2.8）：持有最新统一设备快照并发布 {@link TelemetryUpdatedEvent}。
 *
 * <p>数据流：dji-adapter 的投影器（DjiTelemetryProjector）在每次 OSD 发布后调用
 * {@link #onSnapshot(UnifiedDevice)}；本引擎以 {@link AtomicReference} 持有最新快照
 * （W1 单机单设备；W3 多实例聚合时改为按 deviceId 的并发索引），并发布事件供
 * {@code TelemetryWebSocketHandler} 推送与后续场景引擎消费（§2.4 订阅方约定）。</p>
 *
 * <p>事件时间戳统一取 {@link EventBus#logicalNow()}——加速模式下 WS 推送的 occurredAt
 * 随仿真时钟同倍缩放，与事件总线的时间语义保持一致。</p>
 */
@Component
public class TelemetryEngine {

    private final EventBus eventBus;

    /** 最新快照（不可变对象整体替换，读方无锁安全） */
    private final AtomicReference<UnifiedDevice> latest = new AtomicReference<>();

    public TelemetryEngine(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 接收一次投影快照：替换最新值并发布 {@link TelemetryUpdatedEvent}。
     *
     * @param device 统一设备快照（不可变）
     */
    public void onSnapshot(UnifiedDevice device) {
        Objects.requireNonNull(device, "device");
        latest.set(device);
        eventBus.publish(new TelemetryUpdatedEvent(device.getDeviceId(), eventBus.logicalNow(), device));
    }

    /** 最新快照（尚无快照时 null） */
    public UnifiedDevice latest() {
        return latest.get();
    }

    /** 按 deviceId 查询快照（无匹配时 null） */
    public UnifiedDevice find(String deviceId) {
        UnifiedDevice device = latest.get();
        return device != null && device.getDeviceId().equals(deviceId) ? device : null;
    }

    /** 全部快照（W1 单机，最多 1 个；W3 多实例聚合后语义不变） */
    public List<UnifiedDevice> all() {
        UnifiedDevice device = latest.get();
        return device != null ? List.of(device) : List.of();
    }
}
