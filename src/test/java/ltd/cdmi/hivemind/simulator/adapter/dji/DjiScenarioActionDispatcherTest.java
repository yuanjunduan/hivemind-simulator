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

package ltd.cdmi.hivemind.simulator.adapter.dji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import ltd.cdmi.hivemind.simulator.core.clock.SimulationClockImpl;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightIntent;
import ltd.cdmi.hivemind.simulator.core.event.AlarmRaisedEvent;
import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.fault.FlightFaultInjector;
import ltd.cdmi.hivemind.simulator.core.fault.MediaFaultInjector;
import ltd.cdmi.hivemind.simulator.core.fault.NetworkFaultInjector;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioDefinition;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.media.FfmpegWhipPusher;

/**
 * 场景动作分发器映射单测（TC-FLT-010~016，T2.4）。
 *
 * <p>真实注入器/时钟/DeviceState + mock 飞行入口（意图处理器/任务模拟器/推流器），
 * 验证 18 种 action 契约分发到既有执行路径的映射正确性与清理幂等。</p>
 */
class DjiScenarioActionDispatcherTest {

    private DeviceState state;
    private NetworkFaultInjector networkFault;
    private FlightFaultInjector flightFault;
    private MediaFaultInjector mediaFault;
    private SimulationClockImpl clock;
    private DjiFlightIntentHandler intentHandler;
    private WaylineTaskSimulator wayline;
    private FfmpegWhipPusher pusher;
    private DjiScenarioActionDispatcher dispatcher;
    private List<Object> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        EventBus bus = new EventBus(events::add, new SimulationClockImpl(1.0));
        state = new DeviceState();
        networkFault = new NetworkFaultInjector(bus);
        flightFault = new FlightFaultInjector(state, bus);
        mediaFault = new MediaFaultInjector(bus);
        clock = new SimulationClockImpl(1.0);

        intentHandler = Mockito.mock(DjiFlightIntentHandler.class);
        Mockito.when(intentHandler.handle(Mockito.any(FlightIntent.class)))
                .thenReturn(DjiFlightIntentHandler.IntentResult.accepted("mock"));
        wayline = Mockito.mock(WaylineTaskSimulator.class);
        Mockito.when(wayline.publishProgressFailedWithBreakReason()).thenReturn(true);
        pusher = Mockito.mock(FfmpegWhipPusher.class);

        dispatcher = new DjiScenarioActionDispatcher(
                intentHandler, wayline, networkFault, flightFault, mediaFault, pusher, clock);
    }

    private static ScenarioDefinition scenarioOf(ScenarioDefinition.EventSpec... specs) {
        return new ScenarioDefinition("test-scenario", 1, null, null,
                List.of(new ScenarioDefinition.DeviceSpec("drone-1", DeviceVendor.DJI, "DOCK", null, null, null)),
                List.of(specs));
    }

    private static ScenarioDefinition.EventSpec ev(String action, Map<String, Object> params) {
        return new ScenarioDefinition.EventSpec(1000, action, params);
    }

    @DisplayName("TC-FLT-010：飞行类 action → FlightIntent 通道（takeoff/goto/return_home/land/pause/resume 映射与参数）")
    @Test
    void flightActionsMapToIntents() {
        ScenarioDefinition s = scenarioOf(
                ev("takeoff", Map.of("altitude", 50.0)),
                ev("goto", Map.of("lng", 104.1, "lat", 30.2, "alt", 80.0, "maxSpeed", 10.0)),
                ev("return_home", Map.of("rthAltitude", 100)),
                ev("land", Map.of()),
                ev("pause_mission", Map.of()),
                ev("resume_mission", Map.of()));
        for (ScenarioDefinition.EventSpec spec : s.events()) {
            dispatcher.dispatch(s, spec);
        }

        ArgumentCaptor<FlightIntent> captor = ArgumentCaptor.forClass(FlightIntent.class);
        Mockito.verify(intentHandler, Mockito.times(6)).handle(captor.capture());
        List<FlightIntent> intents = captor.getAllValues();

        FlightIntent.Takeoff takeoff = assertInstanceOf(FlightIntent.Takeoff.class, intents.get(0));
        assertEquals(50.0, takeoff.targetAltitude());

        FlightIntent.Goto gotoIntent = assertInstanceOf(FlightIntent.Goto.class, intents.get(1));
        assertEquals(104.1, gotoIntent.lng());
        assertEquals(30.2, gotoIntent.lat());
        assertEquals(80.0, gotoIntent.alt());
        assertEquals(10.0, gotoIntent.maxSpeed());

        FlightIntent.ReturnHome returnHome = assertInstanceOf(FlightIntent.ReturnHome.class, intents.get(2));
        assertEquals(100, returnHome.rthAltitude());

        assertInstanceOf(FlightIntent.Land.class, intents.get(3));
        assertInstanceOf(FlightIntent.PauseMission.class, intents.get(4));
        assertInstanceOf(FlightIntent.ResumeMission.class, intents.get(5));
    }

    @DisplayName("TC-FLT-011：任务类 action —— cancel_mission 取消任务；task_fail 上报失败 + TASK_FAILED 告警")
    @Test
    void missionActionsMapToWayline() {
        ScenarioDefinition s = scenarioOf(
                ev("cancel_mission", Map.of()),
                ev("task_fail", Map.of("reason", "obstacle")));
        dispatcher.dispatch(s, s.events().get(0));
        dispatcher.dispatch(s, s.events().get(1));

        Mockito.verify(wayline).cancelMissionInternal();
        Mockito.verify(wayline).publishProgressFailedWithBreakReason();

        List<AlarmRaisedEvent> alarms = events.stream()
                .filter(AlarmRaisedEvent.class::isInstance)
                .map(AlarmRaisedEvent.class::cast)
                .toList();
        assertEquals(1, alarms.size());
        assertEquals("TASK_FAILED", alarms.get(0).getAlarm().getCode());
        assertTrue(alarms.get(0).getAlarm().getMessage().contains("obstacle"));
    }

    @DisplayName("TC-FLT-012：网络类 action → NetworkFaultInjector（延迟/断连/重连生效）")
    @Test
    void networkActionsMapToInjector() {
        ScenarioDefinition s = scenarioOf(
                ev("network_delay", Map.of("ms", 300)),
                ev("network_disconnect", Map.of()),
                ev("network_drop", Map.of("percent", 100)));
        dispatcher.dispatch(s, s.events().get(0));
        assertEquals(300, networkFault.delayMillis());

        dispatcher.dispatch(s, s.events().get(1));
        assertTrue(networkFault.isDisconnected());

        dispatcher.dispatch(s, s.events().get(2));
        assertTrue(networkFault.shouldDrop());

        dispatcher.dispatch(s, ev("network_reconnect", Map.of()));
        assertFalse(networkFault.isDisconnected());
    }

    @DisplayName("TC-FLT-013：飞行故障类 action → DeviceState 回写（gps_loss/rtk_recover/set_battery）")
    @Test
    void flightFaultActionsMapToState() {
        ScenarioDefinition s = scenarioOf(
                ev("gps_loss", Map.of()),
                ev("rtk_recover", Map.of()),
                ev("set_battery", Map.of("value", 7)));
        for (ScenarioDefinition.EventSpec spec : s.events()) {
            dispatcher.dispatch(s, spec);
        }
        // 依次：gps_loss → 0；rtk_recover → 2；set_battery 不改 positionState
        assertEquals(2, state.getPositionState());
        assertEquals(7, state.getBatteryPercent());

        long lowBattery = events.stream()
                .filter(AlarmRaisedEvent.class::isInstance)
                .map(AlarmRaisedEvent.class::cast)
                .filter(a -> "LOW_BATTERY".equals(a.getAlarm().getCode()))
                .count();
        assertEquals(1, lowBattery, "set_battery=7 触发低电量告警");
    }

    @DisplayName("TC-FLT-014：媒体类 action —— video_stream_stop 置门并停活跃推流；media_upload_fail 生效")
    @Test
    void mediaActionsMapToInjectorAndPusher() {
        ScenarioDefinition s = scenarioOf(
                ev("video_stream_stop", Map.of()),
                ev("media_upload_fail", Map.of("percent", 100)));
        dispatcher.dispatch(s, s.events().get(0));
        assertTrue(mediaFault.isVideoStopped());
        Mockito.verify(pusher).stopAll();

        dispatcher.dispatch(s, s.events().get(1));
        assertTrue(mediaFault.shouldFailUpload());
    }

    @DisplayName("TC-FLT-015：场景级 action —— set_clock_speed 时钟热切换生效")
    @Test
    void clockSpeedAction() {
        ScenarioDefinition s = scenarioOf(ev("set_clock_speed", Map.of("speed", 8.0)));
        dispatcher.dispatch(s, s.events().get(0));
        assertEquals(8.0, clock.speed());
    }

    @DisplayName("TC-FLT-016：cleanup —— 全部注入门复位（断网/定位/推流/上传率），可重复调用")
    @Test
    void cleanupResetsAllGates() {
        ScenarioDefinition s = scenarioOf(
                ev("network_disconnect", Map.of()),
                ev("gps_loss", Map.of()),
                ev("video_stream_stop", Map.of()),
                ev("media_upload_fail", Map.of("percent", 100)));
        for (ScenarioDefinition.EventSpec spec : s.events()) {
            dispatcher.dispatch(s, spec);
        }
        assertTrue(networkFault.isDisconnected());
        assertEquals(0, state.getPositionState());
        assertTrue(mediaFault.isVideoStopped());

        dispatcher.cleanup(s);
        dispatcher.cleanup(s); // 幂等

        assertFalse(networkFault.isDisconnected());
        assertEquals(2, state.getPositionState());
        assertFalse(mediaFault.isVideoStopped());
        assertFalse(mediaFault.shouldFailUpload());
    }
}
