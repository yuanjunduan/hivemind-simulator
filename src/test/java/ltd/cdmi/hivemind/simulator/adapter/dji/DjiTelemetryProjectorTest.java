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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;
import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.core.engine.telemetry.TelemetryEngine;
import ltd.cdmi.hivemind.simulator.core.model.DeviceSource;
import ltd.cdmi.hivemind.simulator.core.model.DeviceType;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;
import ltd.cdmi.hivemind.simulator.device.DefaultCameraResolver;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;

/**
 * DjiTelemetryProjector 单测（TC-ADP-001~012，实施方案 §2.9.1）。
 *
 * <p>覆盖各 Dock/Drone 型号的关键状态组合：生命周期映射、飞行模式统一名、位置/姿态/电池/
 * 定位/相机/云台字段对齐、Pilot 模式网关身份、vendorExt 原始枚举保留、异常边界。</p>
 */
class DjiTelemetryProjectorTest {

    private RuntimeConfig config;
    private DeviceState state;

    @BeforeEach
    void setUp() {
        config = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                testProps(),
                new LiveConfigStore());
        state = new DeviceState();
    }

    private SimulatorProperties testProps() {
        return new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null);
    }

    /** 默认输入：链路已连接、无任务 */
    private DjiTelemetryProjector.Input noMissionInput() {
        return new DjiTelemetryProjector.Input(true, false, false, null, null);
    }

    /** 基础在舱在线状态（DOCK3 + M4TD，在舱充电） */
    private void onlineInDockCharging() {
        state.setPowered(true);
        state.setOnline(true);
        state.setDroneInDock(true);
        state.setDroneActivated(false);
        state.setDroneModeCode(0);
        state.setDroneChargeState(1);
    }

    @DisplayName("TC-ADP-001：Dock3 在舱充电待机 → CHARGING/READY，全字段对齐")
    @Test
    void dock3ChargingIdleFullFields() {
        onlineInDockCharging();
        state.setDroneLongitude(104.07);
        state.setDroneLatitude(30.67);
        state.setDroneHeight(120.5);
        state.setDroneElevation(500.0);
        state.setAttitudePitch(1.5);
        state.setAttitudeRoll(-0.5);
        state.setAttitudeYaw(270.0);
        state.setBatteryPercent(80);
        state.setBatteryVoltage(17200);
        state.setBatteryTemperature(30);
        state.setPositionState(2);

        UnifiedDevice d = DjiTelemetryProjector.project(state, config, noMissionInput());

        assertEquals(UnifiedDevice.deviceIdOf(DeviceVendor.DJI, config.getGatewaySn()), d.getDeviceId());
        assertEquals(config.getGatewaySn(), d.getDeviceSn());
        assertEquals(DeviceVendor.DJI, d.getVendor());
        assertEquals("DOCK3", d.getModel());
        assertEquals(DeviceType.DOCK, d.getDeviceType());
        assertEquals(DeviceSource.SIMULATED, d.getSource());
        assertEquals(LifecycleState.CHARGING, d.getLifecycleState());
        assertTrue(d.isOnline());

        assertEquals(104.07, d.getPosition().getLongitude());
        assertEquals(30.67, d.getPosition().getLatitude());
        assertEquals(120.5, d.getPosition().getAltitude());
        assertEquals(500.0, d.getPosition().getElevation());
        assertEquals(1.5, d.getAttitude().getPitch());
        assertEquals(-0.5, d.getAttitude().getRoll());
        assertEquals(270.0, d.getAttitude().getYaw());

        assertEquals(80, d.getBattery().getPercent());
        assertEquals(17200, d.getBattery().getVoltageMv());
        assertEquals(30, d.getBattery().getTemperatureC());
        assertEquals(1, d.getBattery().getChargeState());

        assertEquals(2, d.getGps().getPositionState());
        assertEquals(18, d.getGps().getSatellites(), "卫星数与 OSD gps_number=18 对齐");
        assertEquals(1, d.getRtk().getRtkState(), "position_state=2 → RTK 固定解");

        assertEquals("READY", d.getFlightMode());
        assertNull(d.getMissionId());

        assertEquals(DefaultCameraResolver.defaultCameraFor(DroneModel.M4TD).cameraIndex(),
                d.getCamera().getPayloadIndex(), "未下发 payload_index 时按机型默认相机解析");
        assertEquals("CAMERA", d.getPayload().getType());
        assertEquals(DefaultCameraResolver.defaultCameraFor(DroneModel.M4TD).name(),
                d.getPayload().getName());
        assertEquals(0, d.getVendorExt().get("dji.mode_code"));
    }

    @DisplayName("TC-ADP-002：任务航线飞行 → MISSION/WAYLINE，missionId 与 dji.step 透传")
    @Test
    void missionActiveWayline() {
        onlineInDockCharging();
        state.setDroneInDock(false);
        state.setDroneActivated(true);
        state.setDroneModeCode(5);

        UnifiedDevice d = DjiTelemetryProjector.project(state, config,
                new DjiTelemetryProjector.Input(true, true, false, "task-001", 24));

        assertEquals(LifecycleState.MISSION, d.getLifecycleState());
        assertEquals("WAYLINE", d.getFlightMode());
        assertEquals("task-001", d.getMissionId());
        assertEquals(24, d.getVendorExt().get("dji.step"));
    }

    @DisplayName("TC-ADP-003：自动返航（mode_code=9）→ RETURNING/RTH")
    @Test
    void returningRth() {
        onlineInDockCharging();
        state.setDroneInDock(false);
        state.setDroneActivated(true);
        state.setDroneModeCode(9);

        UnifiedDevice d = DjiTelemetryProjector.project(state, config, noMissionInput());

        assertEquals(LifecycleState.RETURNING, d.getLifecycleState());
        assertEquals("RTH", d.getFlightMode());
        assertNull(d.getVendorExt().get("dji.step"), "无任务时不应出现 dji.step");
    }

    @DisplayName("TC-ADP-004：开机未上线 → OFFLINE，online=false")
    @Test
    void poweredOffline() {
        state.setPowered(true);
        state.setOnline(false);

        UnifiedDevice d = DjiTelemetryProjector.project(state, config, noMissionInput());

        assertEquals(LifecycleState.OFFLINE, d.getLifecycleState());
        assertFalse(d.isOnline());
    }

    @DisplayName("TC-ADP-005：MQTT 断连 → NETWORK_LOST（在线标记保留）")
    @Test
    void mqttDisconnectedNetworkLost() {
        onlineInDockCharging();

        UnifiedDevice d = DjiTelemetryProjector.project(state, config,
                new DjiTelemetryProjector.Input(false, false, false, null, null));

        assertEquals(LifecycleState.NETWORK_LOST, d.getLifecycleState());
        assertTrue(d.isOnline(), "NETWORK_LOST 是链路态，平台在线标记保持");
    }

    @DisplayName("TC-ADP-006：Dock1 型号 → model=DOCK1（生命周期与能力集正常）")
    @Test
    void dock1Model() {
        config.setDockType(DockModel.DOCK1);
        config.setDroneType(DroneModel.M30);
        onlineInDockCharging();
        state.setPositionState(1);

        UnifiedDevice d = DjiTelemetryProjector.project(state, config, noMissionInput());

        assertEquals("DOCK1", d.getModel());
        assertEquals(0, d.getRtk().getRtkState(), "position_state=1（GPS）→ 无固定解");
        assertEquals(DefaultCameraResolver.defaultCameraFor(DroneModel.M30).cameraIndex(),
                d.getCamera().getPayloadIndex());
    }

    @DisplayName("TC-ADP-007：Pilot 模式 → 网关为遥控器（PILOT/RC_PLUS），不支持在舱形态")
    @Test
    void pilotModeGateway() {
        config.setDeviceMode(DeviceMode.PILOT);
        config.setControllerType(RcModel.RC_PLUS);
        config.setDroneType(DroneModel.M30);
        state.setPowered(true);
        state.setOnline(true);
        state.setDroneActivated(true);
        state.setDroneModeCode(5);

        UnifiedDevice d = DjiTelemetryProjector.project(state, config, noMissionInput());

        assertEquals("RC_PLUS", d.getModel());
        assertEquals(DeviceType.PILOT, d.getDeviceType());
        assertEquals(config.getGatewaySn(), d.getDeviceSn(), "网关 SN 应为遥控器 SN");
        assertFalse(d.getCapabilities().isSupportsDroneInDock(), "Pilot 模式无在舱形态");
        assertEquals("WAYLINE", d.getFlightMode());
    }

    @DisplayName("TC-ADP-008：未知 mode_code → 兜底 ONLINE + flightMode=UNKNOWN，原始码保留")
    @Test
    void unknownModeCodeFallback() {
        onlineInDockCharging();
        state.setDroneInDock(false);
        state.setDroneActivated(true);
        state.setDroneModeCode(99);

        UnifiedDevice d = DjiTelemetryProjector.project(state, config, noMissionInput());

        assertEquals(LifecycleState.ONLINE, d.getLifecycleState(), "未知模式码兜底 ONLINE");
        assertEquals("UNKNOWN", d.getFlightMode());
        assertEquals(99, d.getVendorExt().get("dji.mode_code"));
    }

    @DisplayName("TC-ADP-009：平台下发 payload_index 优先于机型默认相机")
    @Test
    void appliedPayloadIndexWins() {
        onlineInDockCharging();
        state.setPayloadIndex("42-0-0");

        UnifiedDevice d = DjiTelemetryProjector.project(state, config, noMissionInput());

        assertEquals("42-0-0", d.getCamera().getPayloadIndex());
    }

    @DisplayName("TC-ADP-010：相机模式/录像/云台/DRC 状态映射")
    @Test
    void cameraGimbalDrcMapping() {
        onlineInDockCharging();
        state.setCameraMode(1);
        state.setRecordingState(1);
        state.setPhotoState(0);
        state.setGimbalPitch(-45.0);
        state.setGimbalRoll(2.0);
        state.setGimbalYaw(180.0);
        state.setDrcState(2);
        state.setControlMode(1);

        UnifiedDevice d = DjiTelemetryProjector.project(state, config, noMissionInput());

        assertEquals(1, d.getCamera().getMode(), "camera_mode=1 → 统一语义 1=录像");
        assertEquals(1, d.getCamera().getRecordingState());
        assertEquals(0, d.getCamera().getPhotoState());
        assertEquals(-45.0, d.getGimbal().getPitch());
        assertEquals(2.0, d.getGimbal().getRoll());
        assertEquals(180.0, d.getGimbal().getYaw());
        assertEquals(2, d.getVendorExt().get("dji.drc_state"));
        assertEquals(1, d.getVendorExt().get("dji.control_mode"));
    }

    @DisplayName("TC-ADP-011：onOsdPublished 钩子组装任务快照并交给 TelemetryEngine")
    @Test
    void onOsdPublishedPublishesSnapshot() {
        onlineInDockCharging();
        state.setDroneInDock(false);
        state.setDroneActivated(true);
        state.setDroneModeCode(5);

        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        WaylineTaskSimulator wayline = Mockito.mock(WaylineTaskSimulator.class);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("active", true);
        task.put("paused", false);
        task.put("flight_id", "task-007");
        task.put("current_step", 24);
        Mockito.when(wayline.getTaskStatus()).thenReturn(task);
        TelemetryEngine engine = Mockito.mock(TelemetryEngine.class);

        DjiTelemetryProjector projector = new DjiTelemetryProjector(config, mqtt, wayline, engine);
        projector.onOsdPublished(state);

        ArgumentCaptor<UnifiedDevice> captor = ArgumentCaptor.forClass(UnifiedDevice.class);
        Mockito.verify(engine).onSnapshot(captor.capture());
        UnifiedDevice d = captor.getValue();
        assertEquals("task-007", d.getMissionId());
        assertEquals(24, d.getVendorExt().get("dji.step"));
        assertEquals(LifecycleState.MISSION, d.getLifecycleState());
    }

    @DisplayName("TC-ADP-012：钩子异常隔离——任务快照组装失败不抛出、不触发快照更新")
    @Test
    void onOsdPublishedExceptionIsolated() {
        onlineInDockCharging();
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.isConnected()).thenReturn(true);
        WaylineTaskSimulator wayline = Mockito.mock(WaylineTaskSimulator.class);
        Mockito.when(wayline.getTaskStatus()).thenThrow(new IllegalStateException("任务状态读取失败"));
        TelemetryEngine engine = Mockito.mock(TelemetryEngine.class);

        DjiTelemetryProjector projector = new DjiTelemetryProjector(config, mqtt, wayline, engine);
        // 不抛异常（异常隔离），且不产生快照
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> projector.onOsdPublished(state));
        Mockito.verify(engine, Mockito.never()).onSnapshot(Mockito.any());
    }

    @DisplayName("TC-ADP-012B：机型无相机映射且未下发 payload_index → 抛异常不静默回退")
    @Test
    void noCameraMappingFailsFast() {
        // MAVIC_3TA 为飞行器但无对应相机负载映射：defaultCameraFor 返回 null，
        // requireDefaultCameraIndex 必须显式失败（TC-PAYLOAD-026 禁止静默 fallback）
        config.setDroneType(DroneModel.MAVIC_3TA);
        onlineInDockCharging();

        assertThrows(IllegalStateException.class,
                () -> DjiTelemetryProjector.project(state, config, noMissionInput()),
                "机型无相机映射必须显式失败（TC-PAYLOAD-026 原则）");
    }
}
