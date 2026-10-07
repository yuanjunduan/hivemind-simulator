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

package ltd.cdmi.hivemind.simulator.core.statemachine;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.core.clock.ClockProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.core.clock.SimulationClock;
import ltd.cdmi.hivemind.simulator.core.event.DeviceLifecycleChangedEvent;
import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.SimulationEvent;
import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备生命周期观察者集成单测（TC-CORE-030~031，实施方案 §2.5.3）。
 * <p>验证：状态变化发布 {@link DeviceLifecycleChangedEvent}（逻辑时间戳、vendor:sn deviceId）；
 * 未命中组合发 M-3 且按签名去重；扫描任务注册后由 ClockScheduler 手动 tick 驱动（确定性）。</p>
 */
class DeviceLifecycleMonitorTest {

    /** 可控时钟：手动推进逻辑时间，配合 ClockScheduler 手动 tick 做确定性断言 */
    private static final class MutableClock implements SimulationClock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedMillis() {
            return 0;
        }

        @Override
        public double speed() {
            return 1.0;
        }

        @Override
        public void setSpeed(double speed) {
        }

        @Override
        public WallClockMetrics wallMetrics() {
            return new WallClockMetrics(now, now, 0);
        }

        private void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }
    }

    /** READY 组合的设备状态（开机、在线、在舱、已激活、mode=0、充满） */
    private static DeviceState readyState() {
        DeviceState state = new DeviceState();
        state.setPowered(true);
        state.setOnline(true);
        state.setDroneInDock(true);
        state.setDroneActivated(true);
        state.setDroneModeCode(0);
        state.setDroneChargeState(2);
        return state;
    }

    @DisplayName("TC-CORE-030：状态变化发事件（逻辑时间戳 + vendor:sn）；未命中 M-3 按签名去重")
    @Test
    void publishesOnChangeAndDeduplicatesUnmapped() {
        DeviceState state = readyState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        when(mqtt.isConnected()).thenReturn(true);
        WaylineTaskSimulator wayline = Mockito.mock(WaylineTaskSimulator.class);
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        when(runtimeConfig.getGatewaySn()).thenReturn("DOCK-TEST-SN");
        EventBus eventBus = Mockito.mock(EventBus.class);
        when(eventBus.logicalNow()).thenReturn(Instant.parse("2026-01-01T00:00:03Z"));
        DiagnosticLogRecorder recorder = Mockito.mock(DiagnosticLogRecorder.class);
        ClockScheduler scheduler = new ClockScheduler(new MutableClock(), new ClockProperties(null, null, null));

        DeviceLifecycleMonitor monitor = new DeviceLifecycleMonitor(
                state, mqtt, wayline, runtimeConfig, eventBus, scheduler, recorder);

        // CREATED → READY：发布一次事件（from/to/deviceId/时间戳）
        assertEquals(LifecycleState.READY, monitor.evaluateNow());
        ArgumentCaptor<DeviceLifecycleChangedEvent> captor =
                ArgumentCaptor.forClass(DeviceLifecycleChangedEvent.class);
        verify(eventBus).publish(captor.capture());
        assertEquals(LifecycleState.CREATED, captor.getValue().getFrom());
        assertEquals(LifecycleState.READY, captor.getValue().getTo());
        assertEquals("DJI:DOCK-TEST-SN", captor.getValue().getDeviceId());
        assertEquals(Instant.parse("2026-01-01T00:00:03Z"), captor.getValue().getOccurredAtInstant());

        // 相同状态重复推导：不重复发事件
        assertEquals(LifecycleState.READY, monitor.evaluateNow());
        verify(eventBus, times(1)).publish(any(SimulationEvent.class));

        // 未知模式 → 未命中：M-3 记录一次；同一签名重复推导不重复记录
        state.setDroneModeCode(99);
        assertEquals(LifecycleState.ONLINE, monitor.evaluateNow());
        verify(recorder, times(1)).record(
                eq(DiagnosticCode.MONITOR_LIFECYCLE_UNMAPPED), eq("lifecycle_map"), anyString());
        monitor.evaluateNow();
        verify(recorder, times(1)).record(
                eq(DiagnosticCode.MONITOR_LIFECYCLE_UNMAPPED), eq("lifecycle_map"), anyString());

        // 命中恢复后再未命中：签名已清空，允许再次记录
        state.setDroneModeCode(9);
        assertEquals(LifecycleState.RETURNING, monitor.evaluateNow());
        state.setDroneModeCode(99);
        monitor.evaluateNow();
        verify(recorder, times(2)).record(
                eq(DiagnosticCode.MONITOR_LIFECYCLE_UNMAPPED), eq("lifecycle_map"), anyString());

        // 任务暂停标记：优先级高于模式细分
        when(mqtt.isConnected()).thenReturn(true);
        state.setDroneModeCode(5);
        when(wayline.isMissionActive()).thenReturn(true);
        when(wayline.isMissionPaused()).thenReturn(true);
        assertEquals(LifecycleState.PAUSED, monitor.evaluateNow());
    }

    @DisplayName("TC-CORE-031：init 注册 1s 扫描任务；tick 未到期不扫描、到期后推导并发布")
    @Test
    void scanRegisteredAndDrivenByTicks() {
        DeviceState state = readyState();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        when(mqtt.isConnected()).thenReturn(true);
        WaylineTaskSimulator wayline = Mockito.mock(WaylineTaskSimulator.class);
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        when(runtimeConfig.getGatewaySn()).thenReturn("DOCK-TEST-SN");
        EventBus eventBus = Mockito.mock(EventBus.class);
        when(eventBus.logicalNow()).thenReturn(Instant.parse("2026-01-01T00:00:01Z"));
        DiagnosticLogRecorder recorder = Mockito.mock(DiagnosticLogRecorder.class);
        MutableClock clock = new MutableClock();
        ClockScheduler scheduler = new ClockScheduler(clock, new ClockProperties(null, null, null));

        DeviceLifecycleMonitor monitor = new DeviceLifecycleMonitor(
                state, mqtt, wayline, runtimeConfig, eventBus, scheduler, recorder);

        // 构造后未扫描：仍为初始 CREATED
        assertEquals(LifecycleState.CREATED, monitor.currentState());

        monitor.init();
        assertEquals(1, scheduler.taskCount(), "应注册 1 个 1s 扫描任务");

        // 999ms：未到首帧（首帧=1 个逻辑周期后），tick 不扫描
        clock.advanceMillis(999);
        scheduler.tick();
        assertEquals(LifecycleState.CREATED, monitor.currentState());
        verify(eventBus, times(0)).publish(any(SimulationEvent.class));

        // 再 +1ms → 1000ms 到期：扫描并推导 READY
        clock.advanceMillis(1);
        scheduler.tick();
        assertEquals(LifecycleState.READY, monitor.currentState());
        verify(eventBus, times(1)).publish(any(SimulationEvent.class));
    }
}
