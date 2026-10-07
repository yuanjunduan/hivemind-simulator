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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.core.engine.telemetry.TelemetryEngine;
import ltd.cdmi.hivemind.simulator.core.model.Attitude;
import ltd.cdmi.hivemind.simulator.core.model.BatteryInfo;
import ltd.cdmi.hivemind.simulator.core.model.CameraInfo;
import ltd.cdmi.hivemind.simulator.core.model.Capabilities;
import ltd.cdmi.hivemind.simulator.core.model.DeviceSource;
import ltd.cdmi.hivemind.simulator.core.model.DeviceType;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.GimbalInfo;
import ltd.cdmi.hivemind.simulator.core.model.GpsInfo;
import ltd.cdmi.hivemind.simulator.core.model.PayloadInfo;
import ltd.cdmi.hivemind.simulator.core.model.Position;
import ltd.cdmi.hivemind.simulator.core.model.RtkInfo;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;
import ltd.cdmi.hivemind.simulator.core.statemachine.DjiStateMapper;
import ltd.cdmi.hivemind.simulator.device.DefaultCameraResolver;
import ltd.cdmi.hivemind.simulator.device.DeviceMode;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;

/**
 * DJI 上行投影器（实施方案 §2.9.1）：{@code DeviceState + RuntimeConfig → UnifiedDevice}。
 *
 * <p>核心 {@link #project(DeviceState, RuntimeConfig, Input)} 为纯函数（无副作用、不抛意外异常），
 * 便于按状态组合做单测（TC-ADP-001~008）；实例方法 {@link #onOsdPublished(DeviceState)}
 * 是 {@link ltd.cdmi.hivemind.simulator.device.DeviceSimulator#publishOsd()} 的投影钩子
 * （§2.8："唯一对现有核心文件的侵入"），负责组装非 DeviceState 依赖（MQTT 链路、任务标记）
 * 并做异常隔离——投影失败只记日志，绝不影响 OSD 上报与调度器。</p>
 *
 * <p>字段映射约定：</p>
 * <ul>
 *   <li>网关身份：Dock 模式 → 机场（DOCK，model=型号枚举名如 DOCK3）；Pilot 模式 → 遥控器
 *       （PILOT，model=RcModel 枚举名）；deviceSn 取 {@link RuntimeConfig#getGatewaySn()}；</li>
 *   <li>飞行器字段（位置/姿态/电池/定位/相机/云台）来自 DeviceState 的无人机段；</li>
 *   <li>生命周期状态复用 {@link DjiStateMapper}（与 DeviceLifecycleMonitor 同一推导表）；
 *       flightMode 按 droneModeCode 映射统一名（READY/TAKEOFF/WAYLINE/RTH/LANDING 等）；</li>
 *   <li>厂商特有原值统一进 {@code vendorExt}（{@code dji.mode_code} 等），统一模型不被污染。</li>
 * </ul>
 */
@Component
public class DjiTelemetryProjector {

    private static final Logger log = LoggerFactory.getLogger(DjiTelemetryProjector.class);

    /** 卫星数：与 {@code AbstractDroneOsdBuilder.buildPositionState} 的 gps_number=18 对齐 */
    private static final int GPS_SATELLITES = 18;

    /** 负载统一语义类型：相机 */
    private static final String PAYLOAD_TYPE_CAMERA = "CAMERA";

    /** DJI 适配器声明的负载类型能力（Capabilities 语义：全量能力声明） */
    private static final List<String> PAYLOAD_TYPES = List.of("CAMERA", "SEARCHLIGHT", "SPEAKER");

    /** DJI 适配器声明的机场型号能力（Dock to Cloud 全部型号） */
    private static final List<String> DOCK_MODELS =
            List.of(DockModel.DOCK1.name(), DockModel.DOCK2.name(), DockModel.DOCK3.name());

    /** 定位状态：RTK 固定解（DJI position_state 枚举） */
    private static final int POSITION_STATE_RTK_FIXED = 2;

    /** 相机模式：录像（DJI camera_mode 枚举，统一语义 0=拍照 1=录像） */
    private static final int CAMERA_MODE_RECORD = 1;

    private final RuntimeConfig runtimeConfig;
    private final MqttClientManager mqtt;
    private final WaylineTaskSimulator wayline;
    private final TelemetryEngine telemetryEngine;

    public DjiTelemetryProjector(RuntimeConfig runtimeConfig, MqttClientManager mqtt,
                                 WaylineTaskSimulator wayline, TelemetryEngine telemetryEngine) {
        this.runtimeConfig = runtimeConfig;
        this.mqtt = mqtt;
        this.wayline = wayline;
        this.telemetryEngine = telemetryEngine;
    }

    /**
     * 投影输入快照：DeviceState 之外依赖的只读标记。
     *
     * @param mqttConnected MQTT 链路已连接
     * @param missionActive 任务执行中（flightId 非空）
     * @param missionPaused 任务暂停中
     * @param missionId     当前任务 ID（无任务时 null）
     * @param djiStep       当前 DJI 任务步骤值（无任务时 null；进 vendorExt["dji.step"]）
     */
    public record Input(boolean mqttConnected, boolean missionActive, boolean missionPaused,
                        String missionId, Integer djiStep) {
    }

    /**
     * OSD 投影钩子（§2.8）：由 {@code DeviceSimulator.publishOsd()} 末尾调用。
     *
     * <p>组装输入 → 纯函数投影 → 交 {@link TelemetryEngine} 持有并广播。
     * 全捕获 Throwable：钩子异常绝不向 DeviceSimulator 传播（否则会影响调度器稳定性）。</p>
     *
     * @param state 当前设备状态（只读使用）
     */
    public void onOsdPublished(DeviceState state) {
        try {
            Map<String, Object> taskStatus = wayline.getTaskStatus();
            Integer djiStep = taskStatus.get("current_step") instanceof Number n ? n.intValue() : null;
            Input in = new Input(
                    mqtt.isConnected(),
                    Boolean.TRUE.equals(taskStatus.get("active")),
                    Boolean.TRUE.equals(taskStatus.get("paused")),
                    taskStatus.get("flight_id") instanceof String s ? s : null,
                    djiStep);
            telemetryEngine.onSnapshot(project(state, runtimeConfig, in));
        } catch (Throwable t) {
            log.error("统一遥测投影异常（不影响 OSD 上报）: {}", t.getMessage(), t);
        }
    }

    /**
     * 纯函数投影：{@code DeviceState + RuntimeConfig + Input → UnifiedDevice}（§2.9.1）。
     *
     * @param state  设备状态（只读）
     * @param config 运行时配置（网关 SN / 型号 / 接入模式）
     * @param in     链路与任务标记快照
     * @return 统一设备快照（不可变）
     */
    public static UnifiedDevice project(DeviceState state, RuntimeConfig config, Input in) {
        boolean pilotMode = config.getDeviceMode() == DeviceMode.PILOT;
        String gatewaySn = config.getGatewaySn();
        String model = pilotMode ? config.getControllerType().name() : config.getDockType().name();
        DeviceType deviceType = pilotMode ? DeviceType.PILOT : DeviceType.DOCK;

        DjiStateMapper.Mapping mapping = DjiStateMapper.map(new DjiStateMapper.Input(
                state.isPowered(), state.isOnline(), in.mqttConnected(),
                state.isDroneInDock(), state.isDroneActivated(), state.getDroneModeCode(),
                in.missionActive(), in.missionPaused(), false,
                state.getDroneChargeState() == 1));

        PayloadType camera = config.getSelectedPayload() != null
                ? config.getSelectedPayload()
                : DefaultCameraResolver.defaultCameraFor(config.getDroneType());

        return UnifiedDevice.builder()
                .deviceId(UnifiedDevice.deviceIdOf(DeviceVendor.DJI, gatewaySn))
                .deviceSn(gatewaySn)
                .vendor(DeviceVendor.DJI)
                .model(model)
                .deviceType(deviceType)
                .source(DeviceSource.SIMULATED)
                .lifecycleState(mapping.state())
                .online(state.isOnline())
                .position(Position.builder()
                        .longitude(state.getDroneLongitude())
                        .latitude(state.getDroneLatitude())
                        .altitude(state.getDroneHeight())
                        .elevation(state.getDroneElevation())
                        .build())
                .attitude(Attitude.builder()
                        .pitch(state.getAttitudePitch())
                        .roll(state.getAttitudeRoll())
                        .yaw(state.getAttitudeYaw())
                        .build())
                .battery(BatteryInfo.builder()
                        .percent(state.getBatteryPercent())
                        .voltageMv(state.getBatteryVoltage())
                        .temperatureC(state.getBatteryTemperature())
                        .chargeState(state.getDroneChargeState())
                        .build())
                .gps(GpsInfo.builder()
                        .positionState(state.getPositionState())
                        .satellites(GPS_SATELLITES)
                        .build())
                .rtk(RtkInfo.builder()
                        .rtkState(state.getPositionState() == POSITION_STATE_RTK_FIXED ? 1 : 0)
                        .baseStationSn(null)
                        .build())
                .flightMode(flightModeOf(state.getDroneModeCode()))
                .missionId(in.missionId())
                .camera(CameraInfo.builder()
                        .payloadIndex(resolvePayloadIndex(state, config, camera))
                        .mode(state.getCameraMode() == CAMERA_MODE_RECORD ? 1 : 0)
                        .recordingState(state.getRecordingState())
                        .photoState(state.getPhotoState())
                        .build())
                .gimbal(GimbalInfo.builder()
                        .pitch(state.getGimbalPitch())
                        .roll(state.getGimbalRoll())
                        .yaw(state.getGimbalYaw())
                        .build())
                .payload(PayloadInfo.builder()
                        .type(PAYLOAD_TYPE_CAMERA)
                        .name(camera != null ? camera.name() : null)
                        .index(camera != null ? camera.gimbalIndex() : 0)
                        .working(state.isDroneActivated())
                        .build())
                .capabilities(Capabilities.builder()
                        .supportsRtk(true)
                        .supportsDroneInDock(!pilotMode)
                        .payloadTypes(PAYLOAD_TYPES)
                        .dockModels(DOCK_MODELS)
                        .build())
                .vendorExt(vendorExtOf(state, in))
                .build();
    }

    /**
     * 统一飞行模式名（UnifiedDevice.flightMode 约定：READY/TAKEOFF/WAYLINE/RTH/LANDING 等）。
     * <p>按 DJI droneModeCode 语义映射；未列出的模式码归 UNKNOWN——原始码始终保存在
     * {@code vendorExt["dji.mode_code"]}，上层可按 vendor 解释。</p>
     */
    private static String flightModeOf(int droneModeCode) {
        return switch (droneModeCode) {
            case 0 -> "READY";
            case 1, 2 -> "PREPARING";
            case 3, 6, 7, 8, 15, 16, 17 -> "FLYING";
            case 4 -> "TAKEOFF";
            case 5 -> "WAYLINE";
            case 9, 13 -> "RTH";
            case 10, 11, 12 -> "LANDING";
            case 14 -> "OFFLINE";
            default -> "UNKNOWN";
        };
    }

    /** 负载索引：平台下发值优先；未下发时按默认相机解析（无映射不静默回退，TC-PAYLOAD-026） */
    private static String resolvePayloadIndex(DeviceState state, RuntimeConfig config, PayloadType camera) {
        String applied = state.getPayloadIndex();
        if (applied != null && !applied.isBlank()) {
            return applied;
        }
        if (camera != null) {
            return camera.cameraIndex();
        }
        return DefaultCameraResolver.requireDefaultCameraIndex(config.getDroneType(), "统一遥测投影");
    }

    /** 厂商扩展：DJI 原始枚举与任务步骤（统一模型不被厂商字段污染，§2.2） */
    private static Map<String, Object> vendorExtOf(DeviceState state, Input in) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("dji.mode_code", state.getDroneModeCode());
        ext.put("dji.dock_mode_code", state.getDockModeCode());
        ext.put("dji.camera_mode", state.getCameraMode());
        ext.put("dji.drc_state", state.getDrcState());
        ext.put("dji.control_mode", state.getControlMode());
        if (in.djiStep() != null) {
            ext.put("dji.step", in.djiStep());
        }
        return ext;
    }
}
