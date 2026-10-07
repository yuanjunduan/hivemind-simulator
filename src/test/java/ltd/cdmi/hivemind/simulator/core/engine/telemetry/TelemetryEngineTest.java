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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.TelemetryUpdatedEvent;
import ltd.cdmi.hivemind.simulator.core.model.DeviceSource;
import ltd.cdmi.hivemind.simulator.core.model.DeviceType;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * TelemetryEngine 单测（TC-CORE-060~062，实施方案 §2.8）。
 *
 * <p>验证：快照持有（AtomicReference 整体替换）、事件发布（时间戳取逻辑时间）、
 * 按 deviceId 查询与全量读取语义。</p>
 */
class TelemetryEngineTest {

    private static final Instant LOGICAL_NOW = Instant.parse("2026-10-07T10:00:00Z");

    private UnifiedDevice device(String deviceId) {
        return UnifiedDevice.builder()
                .deviceId(deviceId)
                .deviceSn(deviceId.substring(deviceId.indexOf(':') + 1))
                .vendor(DeviceVendor.DJI)
                .model("DOCK3")
                .deviceType(DeviceType.DOCK)
                .source(DeviceSource.SIMULATED)
                .lifecycleState(LifecycleState.CHARGING)
                .online(true)
                .build();
    }

    private EventBus mockEventBus() {
        EventBus eventBus = Mockito.mock(EventBus.class);
        Mockito.when(eventBus.logicalNow()).thenReturn(LOGICAL_NOW);
        return eventBus;
    }

    @DisplayName("TC-CORE-060：onSnapshot 持有快照并发布 TelemetryUpdatedEvent（时间戳=逻辑时间）")
    @Test
    void onSnapshotHoldsAndPublishes() {
        EventBus eventBus = mockEventBus();
        TelemetryEngine engine = new TelemetryEngine(eventBus);
        UnifiedDevice snapshot = device("DJI:SN-001");

        engine.onSnapshot(snapshot);

        assertSame(snapshot, engine.latest(), "latest 必须返回最近一次快照");
        ArgumentCaptor<TelemetryUpdatedEvent> captor = ArgumentCaptor.forClass(TelemetryUpdatedEvent.class);
        Mockito.verify(eventBus).publish(captor.capture());
        TelemetryUpdatedEvent event = captor.getValue();
        assertEquals("DJI:SN-001", event.getDeviceId());
        assertEquals(LOGICAL_NOW, event.getOccurredAtInstant(), "事件时间戳必须来自逻辑时钟");
        assertSame(snapshot, event.getDevice());
    }

    @DisplayName("TC-CORE-061：二次快照整体替换（读方拿到的旧引用不被修改）")
    @Test
    void secondSnapshotReplacesAtomically() {
        EventBus eventBus = mockEventBus();
        TelemetryEngine engine = new TelemetryEngine(eventBus);
        UnifiedDevice first = device("DJI:SN-001");
        UnifiedDevice second = device("DJI:SN-002");

        engine.onSnapshot(first);
        engine.onSnapshot(second);

        assertSame(second, engine.latest());
        assertEquals(LifecycleState.CHARGING, first.getLifecycleState(), "旧快照引用保持不可变");
        Mockito.verify(eventBus, Mockito.times(2)).publish(Mockito.any(TelemetryUpdatedEvent.class));
    }

    @DisplayName("TC-CORE-062：find/all 查询语义（无快照/不匹配/匹配）")
    @Test
    void findAndAllSemantics() {
        EventBus eventBus = mockEventBus();
        TelemetryEngine engine = new TelemetryEngine(eventBus);

        assertNull(engine.latest(), "尚无快照时 latest 返回 null");
        assertTrue(engine.all().isEmpty(), "尚无快照时 all 为空列表");
        assertNull(engine.find("DJI:SN-001"), "尚无快照时 find 返回 null");

        UnifiedDevice snapshot = device("DJI:SN-001");
        engine.onSnapshot(snapshot);

        assertSame(snapshot, engine.find("DJI:SN-001"));
        assertNull(engine.find("DJI:OTHER"), "deviceId 不匹配返回 null");
        assertEquals(1, engine.all().size());
        assertSame(snapshot, engine.all().get(0));
    }
}
