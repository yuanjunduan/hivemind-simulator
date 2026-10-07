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

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.core.clock.SimulationClockImpl;
import ltd.cdmi.hivemind.simulator.device.osd.Dock3OsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M4DDroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.RcPlusOsdBuilder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.AiSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeviceSimulator 加速报文频率封顶集成测试（TC-CORE-091，T1.10 / 可行性方案 §4.4 ADR-5）。
 *
 * <p>验证 {@code publishOsd()} 整轮节流接入（唯一侵入点）：</p>
 * <ul>
 *   <li>REALTIME（倍率 1.0）恒定放行——连续两轮均正常发布，行为与改造前一致（红线 2）；</li>
 *   <li>加速（10x）+ cap 2Hz：同一封顶间隔内的第二轮被整轮跳过（无任何 MQTT 发布），
 *       间隔耗尽后的下一轮恢复发布。</li>
 * </ul>
 */
class DeviceSimulatorPublishRateCapTest {

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null,
                null,
                null,
                null,
                null
        );
    }

    private RuntimeConfig testRuntimeConfig() {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
    }

    private DeviceSimulator newSimulator(MqttClientManager mqtt, DeviceState state,
                                         ClockScheduler scheduler, ClockProperties clockProperties) {
        return new DeviceSimulator(
                testProps(), mqtt, state, new ObjectMapper(), testRuntimeConfig(),
                List.of(new Dock3OsdBuilder()),
                List.of(new M4DDroneOsdBuilder()),
                List.of(new RcPlusOsdBuilder()),
                Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema(),
                new AiSimulator(mqtt, testRuntimeConfig(), new DockTopicSchema()),
                scheduler, null, clockProperties);
    }

    /** 统计一次 publishOsd 引发的 mqtt.publish 调用数（DRC/无人机 OSD 关闭时为 Dock OSD 多条）。 */
    private int publishCallCount(MqttClientManager mqtt) {
        return Mockito.mockingDetails(mqtt).getInvocations().size();
    }

    @DisplayName("TC-CORE-091：REALTIME（倍率 1.0）连续两轮 publishOsd 均放行——行为与改造前一致")
    @Test
    void realtimePublishIsNeverThrottled() {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = new DeviceState();
        state.setOnline(true);

        ClockScheduler scheduler = new ClockScheduler(new SimulationClockImpl(1.0),
                new ClockProperties(null, null, null));
        // 即使配置了加速封顶参数，REALTIME 倍率下闸门也必须恒定放行（红线 2）
        DeviceSimulator simulator = newSimulator(mqtt, state, scheduler,
                new ClockProperties(new ClockProperties.Clock(ClockProperties.ClockMode.ACCELERATED, 10.0),
                        null, new ClockProperties.Mqtt(2)));

        simulator.publishOsd();
        int firstRound = publishCallCount(mqtt);
        assertTrue(firstRound > 0, "REALTIME 首轮应发布报文，实际 " + firstRound);

        simulator.publishOsd(); // 紧邻第二轮（物理间隔 ≈0）
        int secondRound = publishCallCount(mqtt);
        assertTrue(secondRound > firstRound, "REALTIME 第二轮不应被节流（" + firstRound + " → " + secondRound + "）");
    }

    @DisplayName("TC-CORE-091：10x 加速 + cap 2Hz 下封顶间隔内整轮跳过，间隔耗尽后恢复")
    @Test
    void acceleratedPublishIsThrottledWithinCapInterval() throws Exception {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        DeviceState state = new DeviceState();
        state.setOnline(true);

        ClockScheduler scheduler = new ClockScheduler(new SimulationClockImpl(10.0),
                new ClockProperties(null, null, null));
        DeviceSimulator simulator = newSimulator(mqtt, state, scheduler,
                new ClockProperties(null, null, new ClockProperties.Mqtt(2)));

        simulator.publishOsd(); // 第 1 轮：放行并记录基准时刻
        assertTrue(publishCallCount(mqtt) > 0, "加速模式首轮应放行");

        Mockito.clearInvocations(mqtt);
        simulator.publishOsd(); // 第 2 轮：距上次放行 <500ms → 整轮跳过
        Mockito.verify(mqtt, Mockito.never()).publish(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(mqtt, Mockito.never()).publishJson(Mockito.anyString(), Mockito.any());

        Thread.sleep(550); // 物理等待超过封顶间隔（1/2Hz = 500ms）
        simulator.publishOsd(); // 第 3 轮：恢复放行
        Mockito.verify(mqtt, Mockito.atLeastOnce()).publish(Mockito.anyString(), Mockito.anyString());
    }
}
