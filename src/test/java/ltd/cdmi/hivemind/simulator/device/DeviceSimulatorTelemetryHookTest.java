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

package ltd.cdmi.hivemind.simulator.device;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import ltd.cdmi.hivemind.simulator.adapter.dji.DjiTelemetryProjector;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.device.osd.Dock3OsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M4DDroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.RcPlusOsdBuilder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.AiSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;

/**
 * DeviceSimulator 遥测投影钩子单测（TC-CORE-074~075，实施方案 §2.8）。
 *
 * <p>验证 §2.8 的唯一侵入点：publishOsd() 末尾调用 {@code telemetryProjector.onOsdPublished(state)}；
 * 未在线（提前 return）时不调用。</p>
 */
class DeviceSimulatorTelemetryHookTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null);
    }

    private RuntimeConfig testRuntimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
    }

    private DeviceSimulator newSimulator(MqttClientManager mqtt, DeviceState state,
                                         RuntimeConfig runtimeConfig, DjiTelemetryProjector projector) {
        return new DeviceSimulator(
                testProps(), mqtt, state, new ObjectMapper(), runtimeConfig,
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, runtimeConfig, new DockTopicSchema()),
                ClockScheduler.realtime(),
                projector);
    }

    @DisplayName("TC-CORE-074：在线时 publishOsd 末尾调用投影钩子（一次 OSD 一轮投影）")
    @Test
    void hookInvokedWhenOnline() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RuntimeConfig runtimeConfig = testRuntimeConfig();
        DeviceState state = new DeviceState();
        state.setOnline(true);
        DjiTelemetryProjector projector = Mockito.mock(DjiTelemetryProjector.class);

        DeviceSimulator simulator = newSimulator(mqtt, state, runtimeConfig, projector);
        simulator.publishOsd();

        Mockito.verify(projector).onOsdPublished(state);
    }

    @DisplayName("TC-CORE-075：未在线时不调用投影钩子（publishOsd 提前返回）")
    @Test
    void hookSkippedWhenOffline() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RuntimeConfig runtimeConfig = testRuntimeConfig();
        DeviceState state = new DeviceState();
        state.setOnline(false);
        DjiTelemetryProjector projector = Mockito.mock(DjiTelemetryProjector.class);

        DeviceSimulator simulator = newSimulator(mqtt, state, runtimeConfig, projector);
        simulator.publishOsd();

        Mockito.verify(projector, Mockito.never()).onOsdPublished(Mockito.any());
    }
}
