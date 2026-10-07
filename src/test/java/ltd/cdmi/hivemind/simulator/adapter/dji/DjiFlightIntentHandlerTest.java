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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.hivemind.simulator.adapter.dji.DjiFlightIntentHandler.IntentResult;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightIntent;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionPlan;
import ltd.cdmi.hivemind.simulator.core.model.Waypoint;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.MediaUploadSimulator;
import ltd.cdmi.hivemind.simulator.handler.ServiceCommandHandler;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.DockTopicSchema;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DjiFlightIntentHandler 单测（TC-ADP-013~019，实施方案 §2.9.2）。
 *
 * <p>验证统一 FlightIntent → 现有内部执行路径的映射（经真实 Handler 实例端到端驱动，
 * 断言 DeviceState 与 WaylineTaskSimulator 的任务状态；异步进度事件由既有调度器推进，
 * 不在本单测等待窗口内）。</p>
 */
class DjiFlightIntentHandlerTest {

    private DeviceState state;
    private WaylineTaskSimulator wayline;
    private DjiFlightIntentHandler handler;

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

    private RuntimeConfig runtimeConfig() {
        RuntimeConfig rc = Mockito.mock(RuntimeConfig.class);
        Mockito.when(rc.getDeviceMode()).thenReturn(DeviceMode.DOCK);
        Mockito.when(rc.getDockType()).thenReturn(DockModel.DOCK3);
        Mockito.when(rc.getDockSn()).thenReturn("dock-sn-test");
        Mockito.when(rc.getGatewaySn()).thenReturn("dock-sn-test");
        Mockito.when(rc.getLocationLatitude()).thenReturn(30.67);
        Mockito.when(rc.getLocationLongitude()).thenReturn(104.07);
        Mockito.when(rc.getLocationHeight()).thenReturn(500.0);
        return rc;
    }

    @BeforeEach
    void setUp() {
        state = new DeviceState();
        ObjectMapper objectMapper = new ObjectMapper();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        RuntimeConfig rc = runtimeConfig();

        FlightCommandSimulator flightCommand = new FlightCommandSimulator(
                testProps(), mqtt, state, rc, Mockito.mock(DiagnosticLogRecorder.class), new DockTopicSchema());
        wayline = new WaylineTaskSimulator(
                testProps(), mqtt, state, objectMapper, Mockito.mock(ServiceCommandHandler.class),
                Mockito.mock(MediaUploadSimulator.class), rc, Mockito.mock(DiagnosticLogRecorder.class),
                new DockTopicSchema());
        handler = new DjiFlightIntentHandler(flightCommand, wayline);
    }

    private List<Waypoint> twoWaypoints() {
        return List.of(
                Waypoint.builder().index(0).longitude(104.10).latitude(30.60).altitude(50.0).speed(0)
                        .actions(List.of("PHOTO")).build(),
                Waypoint.builder().index(1).longitude(104.20).latitude(30.70).altitude(60.0).speed(5.0)
                        .actions(List.of()).build());
    }

    // ==================== TC-ADP-013：Goto → flyto 内部入口 ====================

    @DisplayName("TC-ADP-013：Goto 意图映射 flyto 内部入口（state 全字段设置 + 速度生效）")
    @Test
    void gotoIntentMapsToFlyToInternal() {
        IntentResult result = handler.handle(new FlightIntent.Goto(104.07, 30.67, 100.0, 12.0));

        assertTrue(result.accepted(), result.detail());
        assertNotNull(state.getCurrentFlyToId());
        assertTrue(state.getCurrentFlyToId().startsWith("intent-"), "flyto id 应由适配层生成");
        assertEquals(30.67, state.getTargetLatitude(), 1e-9);
        assertEquals(104.07, state.getTargetLongitude(), 1e-9);
        assertEquals(100.0, state.getTargetHeight(), 1e-9);
        assertEquals(12, state.getMaxSpeed());
    }

    @DisplayName("TC-ADP-013：Goto maxSpeed≤0 沿用当前速度")
    @Test
    void gotoIntentKeepsCurrentSpeedWhenNonPositive() {
        state.setMaxSpeed(8);

        IntentResult result = handler.handle(new FlightIntent.Goto(104.07, 30.67, 100.0, 0));

        assertTrue(result.accepted(), result.detail());
        assertEquals(8, state.getMaxSpeed(), "maxSpeed≤0 时不应覆盖已有速度");
    }

    // ==================== TC-ADP-014：Takeoff → 一键起飞内部入口 ====================

    @DisplayName("TC-ADP-014：Takeoff 意图映射一键起飞（原地起飞：水平取当前位置 + 目标高度）")
    @Test
    void takeoffIntentMapsToTakeoffToInternal() {
        state.setDroneLatitude(30.5);
        state.setDroneLongitude(104.5);

        IntentResult result = handler.handle(new FlightIntent.Takeoff(80.0));

        assertTrue(result.accepted(), result.detail());
        assertNotNull(state.getCurrentFlightId());
        assertTrue(state.getCurrentFlightId().startsWith("intent-"), "flight id 应由适配层生成");
        assertNotNull(state.getCurrentTrackId(), "track_id 应自动生成");
        assertEquals(80.0, state.getTargetHeight(), 1e-9);
        assertEquals(30.5, state.getTargetLatitude(), 1e-9, "原地起飞水平坐标=当前位置纬度");
        assertEquals(104.5, state.getTargetLongitude(), 1e-9, "原地起飞水平坐标=当前位置经度");
    }

    // ==================== TC-ADP-015：ExecuteWaypoints → 任务启动内部入口 ====================

    @DisplayName("TC-ADP-015：ExecuteWaypoints 意图映射任务启动（prepare+execute 合并语义）")
    @Test
    void executeWaypointsIntentStartsMission() {
        IntentResult result = handler.handle(new FlightIntent.ExecuteWaypoints(twoWaypoints(), 8.0));

        assertTrue(result.accepted(), result.detail());
        assertTrue(result.detail().contains("mission_id="), "应返回生成的 missionId");
        assertTrue(wayline.isMissionActive(), "任务应处于执行中");
        assertEquals(4, state.getDockModeCode(), "机场应进入作业模式");
        assertTrue(state.isCoverOpen(), "机场盖应打开");
        assertEquals(0, state.getDroneChargeState(), "应停止充电");
        assertTrue(state.isDroneActivated(), "无人机应激活");
        assertFalse(state.isDroneInDock(), "无人机应离舱");
        assertEquals(4, state.getDroneModeCode(), "自动起飞");
        assertTrue(state.isPutterExpanded(), "推杆应展开");
    }

    @DisplayName("TC-ADP-015：startMissionInternal 支持 ext.rthAltitude 写入 state（W2 预留）")
    @Test
    void startMissionInternalWritesRthAltitudeFromExt() {
        MissionPlan plan = new MissionPlan("mission-ext-1", twoWaypoints(), 8.0, Map.of("rthAltitude", 120));

        Map<String, Object> reply = wayline.startMissionInternal(plan);

        assertEquals(0, reply.get("result"));
        assertEquals(120, state.getRthAltitude());
        assertTrue(wayline.isMissionActive());
    }

    // ==================== TC-ADP-016：ReturnHome / Land → 返航内部入口 ====================

    @DisplayName("TC-ADP-016：ReturnHome 意图映射返航（rthAltitude 覆写 + mode_code=9）")
    @Test
    void returnHomeIntentMapsToReturnHomeInternal() {
        IntentResult result = handler.handle(new FlightIntent.ReturnHome(120));

        assertTrue(result.accepted(), result.detail());
        assertEquals(120, state.getRthAltitude(), "显式 rthAltitude 应写入 state");
        assertEquals(9, state.getDroneModeCode(), "自动返航");
    }

    @DisplayName("TC-ADP-016：Land 意图按 return_home 语义执行（下降归舱）")
    @Test
    void landIntentUsesReturnHomeSemantics() {
        state.setRthAltitude(100);

        IntentResult result = handler.handle(new FlightIntent.Land());

        assertTrue(result.accepted(), result.detail());
        assertTrue(result.detail().contains("return_home"), "详情应说明复用 return_home 语义");
        assertEquals(100, state.getRthAltitude(), "Land 不覆写已有返航高度");
        assertEquals(9, state.getDroneModeCode());
    }

    // ==================== TC-ADP-017：PauseMission / ResumeMission ====================

    @DisplayName("TC-ADP-017：PauseMission / ResumeMission 意图翻转任务暂停状态")
    @Test
    void pauseAndResumeMissionIntents() {
        assertFalse(wayline.isMissionPaused());

        IntentResult pause = handler.handle(new FlightIntent.PauseMission());
        assertTrue(pause.accepted(), pause.detail());
        assertTrue(wayline.isMissionPaused(), "暂停意图应使任务进入暂停");
        assertEquals(5, state.getDroneModeCode(), "悬停");

        IntentResult resume = handler.handle(new FlightIntent.ResumeMission());
        assertTrue(resume.accepted(), resume.detail());
        assertFalse(wayline.isMissionPaused(), "恢复意图应使任务退出暂停");
        assertEquals(5, state.getDroneModeCode());
    }

    // ==================== TC-ADP-018：未接入意图显式拒绝 ====================

    @DisplayName("TC-ADP-018：W1 未接入意图（Hover/SetSpeed/SetAltitude/Yaw/Arm/Disarm）返回 rejected")
    @Test
    void unconnectedIntentsRejectedExplicitly() {
        List<FlightIntent> unconnected = List.of(
                new FlightIntent.Hover(),
                new FlightIntent.SetSpeed(5.0),
                new FlightIntent.SetAltitude(50.0),
                new FlightIntent.Yaw(90.0),
                new FlightIntent.Arm(),
                new FlightIntent.Disarm());

        for (FlightIntent intent : unconnected) {
            IntentResult result = handler.handle(intent);
            assertFalse(result.accepted(), intent.getClass().getSimpleName() + " 应被拒绝");
            assertTrue(result.detail().contains("W1 未接入"),
                    intent.getClass().getSimpleName() + " 拒绝原因应说明 W1 未接入");
        }
    }

    // ==================== TC-ADP-019：参数非法拒绝 ====================

    @DisplayName("TC-ADP-019：ExecuteWaypoints 空航点列表返回 rejected（不启动任务）")
    @Test
    void executeWaypointsWithEmptyListRejected() {
        IntentResult result = handler.handle(new FlightIntent.ExecuteWaypoints(List.of(), 5.0));

        assertFalse(result.accepted());
        assertTrue(result.detail().contains("航点列表为空"), result.detail());
        assertFalse(wayline.isMissionActive(), "空航点不应启动任务");
    }
}
