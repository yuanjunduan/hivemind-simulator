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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ltd.cdmi.hivemind.simulator.core.clock.SimulationClockImpl;
import ltd.cdmi.hivemind.simulator.core.event.AlarmRaisedEvent;
import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.FaultInjectedEvent;
import ltd.cdmi.hivemind.simulator.device.DeviceState;

/**
 * 故障注入器状态机单测（TC-FLT-001~005，T2.4）。
 *
 * <p>三个注入器用真实 {@link EventBus}（捕获发布事件）+ 真实 {@link DeviceState}，
 * 验证注入、概率边界（100% 必中）、复位与事件发布。</p>
 */
class FaultInjectorsTest {

    private List<Object> events;
    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        eventBus = new EventBus(events::add, new SimulationClockImpl(1.0));
    }

    // ==================== 网络注入器 ====================

    @DisplayName("TC-FLT-001：网络注入 —— 断连阻断收发、重连解除、延迟与丢弃率生效、clear 复位")
    @Test
    void networkInjector() {
        NetworkFaultInjector network = new NetworkFaultInjector(eventBus);

        assertFalse(network.isDisconnected());
        assertFalse(network.shouldDrop());

        network.inject("network_disconnect", "drone-1", Map.of());
        assertTrue(network.isDisconnected());
        assertTrue(network.shouldDrop(), "断网时上行阻断 / 下行必丢");

        network.inject("network_reconnect", "drone-1", Map.of());
        assertFalse(network.isDisconnected());
        assertFalse(network.shouldDrop());

        network.inject("network_delay", "drone-1", Map.of("ms", 500, "jitterMs", 0));
        assertEquals(500, network.delayMillis());

        network.inject("network_drop", "drone-1", Map.of("percent", 100));
        assertTrue(network.shouldDrop(), "100% 丢弃率必须必中");

        network.clear();
        assertEquals(0, network.delayMillis());
        assertFalse(network.shouldDrop());
        assertFalse(network.isDisconnected());

        // 4 次注入各发布一次 FaultInjectedEvent
        long faultEvents = events.stream().filter(e -> e instanceof FaultInjectedEvent).count();
        assertEquals(4, faultEvents);
    }

    // ==================== 飞行注入器 ====================

    @DisplayName("TC-FLT-002：GPS/RTK 注入 —— positionState 回写、告警发布、clear 恢复 RTK 固定解")
    @Test
    void flightInjectorGpsRtk() {
        DeviceState state = new DeviceState();
        FlightFaultInjector flight = new FlightFaultInjector(state, eventBus);

        flight.inject("gps_loss", "drone-1", Map.of());
        assertEquals(0, state.getPositionState(), "GPS 丢失 → 未定位(0)");

        flight.inject("gps_recover", "drone-1", Map.of());
        assertEquals(1, state.getPositionState(), "GPS 恢复 → GPS 定位(1)");

        flight.inject("rtk_loss", "drone-1", Map.of());
        assertEquals(1, state.getPositionState(), "RTK 丢失 → 降级 GPS 定位(1)");

        flight.inject("rtk_recover", "drone-1", Map.of());
        assertEquals(2, state.getPositionState(), "RTK 恢复 → RTK 固定解(2)");

        flight.clear();
        assertEquals(2, state.getPositionState(), "clear 复位 RTK 固定解");

        // GPS_LOST(1) + RTK_LOST(1) 两次告警
        List<AlarmRaisedEvent> alarms = events.stream()
                .filter(AlarmRaisedEvent.class::isInstance)
                .map(AlarmRaisedEvent.class::cast)
                .toList();
        assertEquals(2, alarms.size());
        assertEquals("GPS_LOST", alarms.get(0).getAlarm().getCode());
        assertEquals(3, alarms.get(0).getAlarm().getLevel());
        assertEquals("RTK_LOST", alarms.get(1).getAlarm().getCode());
    }

    @DisplayName("TC-FLT-003：电量与告警注入 —— 电量回写、低于 20% 附加 LOW_BATTERY、trigger_alarm 自定义告警")
    @Test
    void flightInjectorBatteryAndAlarm() {
        DeviceState state = new DeviceState();
        FlightFaultInjector flight = new FlightFaultInjector(state, eventBus);

        flight.inject("set_battery", "drone-1", Map.of("value", 50));
        assertEquals(50, state.getBatteryPercent());
        assertEquals(0, alarmCount(), "50% 不触发低电量告警");

        flight.inject("set_battery", "drone-1", Map.of("value", 8));
        assertEquals(8, state.getBatteryPercent());
        assertEquals(1, alarmCount(), "低于 20% 触发 LOW_BATTERY");

        flight.inject("trigger_alarm", "drone-1", Map.of("type", "motor_error"));
        assertEquals(2, alarmCount());
        List<AlarmRaisedEvent> alarms = alarms();
        assertEquals("LOW_BATTERY", alarms.get(0).getAlarm().getCode());
        assertEquals("motor_error", alarms.get(1).getAlarm().getCode());
    }

    // ==================== 媒体注入器 ====================

    @DisplayName("TC-FLT-004：媒体注入 —— 推流停止门开关、100% 上传失败必中、clear 复位")
    @Test
    void mediaInjector() {
        MediaFaultInjector media = new MediaFaultInjector(eventBus);

        assertFalse(media.isVideoStopped());
        assertFalse(media.shouldFailUpload());

        media.inject("video_stream_stop", "drone-1", Map.of());
        assertTrue(media.isVideoStopped());

        media.inject("video_stream_recover", "drone-1", Map.of());
        assertFalse(media.isVideoStopped());

        media.inject("media_upload_fail", "drone-1", Map.of("percent", 100));
        assertTrue(media.shouldFailUpload(), "100% 失败率必须必中");

        media.clear();
        assertFalse(media.shouldFailUpload());
        assertFalse(media.isVideoStopped());
    }

    @DisplayName("TC-FLT-005：注入事件契约 —— target/params 透传、时间戳来自逻辑时钟；未知 action 不发布")
    @Test
    void faultEventsCarryContext() {
        NetworkFaultInjector network = new NetworkFaultInjector(eventBus);

        network.inject("network_delay", "drone-9", Map.of("ms", 200));
        FaultInjectedEvent event = (FaultInjectedEvent) events.get(0);
        assertEquals("drone-9", event.getDeviceId());
        assertEquals("network", event.getTarget());
        assertEquals("network_delay", event.getFaultType());
        assertEquals(200, ((Number) event.getParams().get("ms")).intValue());

        int before = events.size();
        network.inject("no_such_action", "drone-9", Map.of());
        assertEquals(before, events.size(), "未知 action 记警告但不发布事件、不抛异常");
    }

    private long alarmCount() {
        return events.stream().filter(AlarmRaisedEvent.class::isInstance).count();
    }

    private List<AlarmRaisedEvent> alarms() {
        return events.stream()
                .filter(AlarmRaisedEvent.class::isInstance)
                .map(AlarmRaisedEvent.class::cast)
                .toList();
    }
}
