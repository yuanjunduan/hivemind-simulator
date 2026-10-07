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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import ltd.cdmi.hivemind.simulator.core.clock.SimulationClock;
import ltd.cdmi.hivemind.simulator.core.model.Alarm;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * 事件总线单测（T1.4，TC-CORE-016~019）。
 * <p>验收标准（实施方案 §3.1）：发布/订阅/逻辑时间戳正确。
 * 订阅链路用最小 Spring 上下文（仅注册 3 个 bean，无 Web/MQTT）验证
 * {@code EventBus 门面 → ApplicationEventPublisher → @EventListener} 全链路。</p>
 */
class EventBusTest {

    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");
    private static final String DEVICE_ID = "DJI:SN-001";

    private final FixedClock clock = new FixedClock(T0);
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final EventBus bus = new EventBus(publisher, clock);

    @DisplayName("TC-CORE-016：事件公共契约——deviceId/occurredAtInstant 必填，子类载荷完整")
    @Test
    void tcCore016EventContract() {
        // DeviceLifecycleChangedEvent：from/to
        DeviceLifecycleChangedEvent lifecycle = new DeviceLifecycleChangedEvent(
                DEVICE_ID, T0, LifecycleState.ONLINE, LifecycleState.READY);
        assertEquals(DEVICE_ID, lifecycle.getDeviceId());
        assertEquals(T0, lifecycle.getOccurredAtInstant());
        assertEquals(LifecycleState.ONLINE, lifecycle.getFrom());
        assertEquals(LifecycleState.READY, lifecycle.getTo());

        // MissionProgressEvent：missionId/state/percent/currentStep
        MissionProgressEvent mission = new MissionProgressEvent(
                DEVICE_ID, T0, "m-1", "EXECUTING", 50, 12);
        assertEquals("m-1", mission.getMissionId());
        assertEquals("EXECUTING", mission.getState());
        assertEquals(50, mission.getPercent());
        assertEquals(12, mission.getCurrentStep());

        // 公共字段必填（null 拒绝）
        assertThrows(NullPointerException.class, () -> new DeviceLifecycleChangedEvent(
                null, T0, LifecycleState.ONLINE, LifecycleState.READY));
        assertThrows(NullPointerException.class, () -> new DeviceLifecycleChangedEvent(
                DEVICE_ID, null, LifecycleState.ONLINE, LifecycleState.READY));

        // percent 范围校验
        assertThrows(IllegalArgumentException.class, () -> new MissionProgressEvent(
                DEVICE_ID, T0, "m-1", "EXECUTING", 101, 0));

        // FaultInjectedEvent：params 防御性拷贝为不可变 Map、null 兜底为空
        FaultInjectedEvent fault = new FaultInjectedEvent(DEVICE_ID, T0, "NETWORK_DROP", null, "link");
        assertTrue(fault.getParams().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> fault.getParams().put("x", 1));

        // TelemetryUpdatedEvent / AlarmRaisedEvent：载荷对象原样持有（不可变模型，无需拷贝）
        UnifiedDevice device = UnifiedDevice.builder()
                .deviceId(DEVICE_ID).deviceSn("SN-001").vendor(DeviceVendor.DJI).build();
        assertSame(device, new TelemetryUpdatedEvent(DEVICE_ID, T0, device).getDevice());

        Alarm alarm = Alarm.builder().alarmId("a-1").code("LOW_BATTERY").level(2).message("电量低").build();
        assertSame(alarm, new AlarmRaisedEvent(DEVICE_ID, T0, alarm).getAlarm());
    }

    @DisplayName("TC-CORE-017：EventBus.publish 同步转发至 ApplicationEventPublisher；null 拒绝")
    @Test
    void tcCore017PublishForwards() {
        AlarmRaisedEvent event = new AlarmRaisedEvent(DEVICE_ID, bus.logicalNow(),
                Alarm.builder().alarmId("a-1").code("LOW_BATTERY").level(2).message("电量低").build());

        bus.publish(event);

        assertEquals(1, publisher.events.size(), "publish 必须转发事件");
        assertSame(event, publisher.events.get(0));
        assertThrows(NullPointerException.class, () -> bus.publish(null));
    }

    @DisplayName("TC-CORE-018：Spring @EventListener 订阅链路——基类订阅匹配全部子类事件且同步收到")
    @Test
    void tcCore018SpringSubscription() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(SimulationClock.class, () -> clock);
            ctx.registerBean(EventBus.class);
            ctx.registerBean(RecordingSubscriber.class);
            ctx.refresh();

            EventBus springBus = ctx.getBean(EventBus.class);
            springBus.publish(new MissionProgressEvent(
                    DEVICE_ID, springBus.logicalNow(), "m-1", "EXECUTING", 30, 7));

            RecordingSubscriber subscriber = ctx.getBean(RecordingSubscriber.class);
            assertEquals(1, subscriber.received.size(), "同步发布后订阅方应立即收到事件");
            assertTrue(subscriber.received.get(0) instanceof MissionProgressEvent,
                    "基类订阅须能匹配子类事件");
        }
    }

    @DisplayName("TC-CORE-019：事件时间戳来自逻辑时钟（logicalNow 随仿真时钟推进，与墙钟解耦）")
    @Test
    void tcCore019LogicalTimestamp() {
        // 固定时钟：logicalNow 精确返回注入的逻辑时间
        assertEquals(T0, bus.logicalNow());

        // 逻辑时间推进后发起事件：时间戳取逻辑时间（而非墙钟当前时刻）
        clock.set(T0.plusSeconds(90));
        MissionProgressEvent event = new MissionProgressEvent(
                DEVICE_ID, bus.logicalNow(), "m-2", "PAUSED", 40, 3);
        assertEquals(T0.plusSeconds(90), event.getOccurredAtInstant());

        bus.publish(event);
        assertSame(event, publisher.events.get(publisher.events.size() - 1));
    }

    /** 可控假时钟：固定/可设置逻辑时间，验证时间戳来源（EventBus 只消费 now()） */
    private static final class FixedClock implements SimulationClock {

        private final Instant startedAt;
        private Instant current;

        private FixedClock(Instant start) {
            this.startedAt = start;
            this.current = start;
        }

        private void set(Instant instant) {
            this.current = instant;
        }

        @Override
        public Instant now() {
            return current;
        }

        @Override
        public long elapsedMillis() {
            return Duration.between(startedAt, current).toMillis();
        }

        @Override
        public double speed() {
            return 1.0;
        }

        @Override
        public void setSpeed(double speed) {
            // 测试桩：固定时钟不支持倍率
        }

        @Override
        public WallClockMetrics wallMetrics() {
            return new WallClockMetrics(startedAt, current, Duration.between(startedAt, current).toMillis());
        }
    }

    /** 录制型发布器：替代 Spring 上下文做纯转发断言 */
    private static final class RecordingPublisher implements ApplicationEventPublisher {

        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }

    /** 订阅方样本：以仿真事件基类订阅（W1 诊断日志订阅方的等价形态） */
    public static class RecordingSubscriber {

        private final List<SimulationEvent> received = new ArrayList<>();

        @EventListener
        public void onSimulationEvent(SimulationEvent event) {
            received.add(event);
        }
    }
}
