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

package ltd.cdmi.hivemind.simulator.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一模型 JSON 往返测试（TC-CORE-001）。
 * <p>验收标准（实施方案 §2.2）：所有字段序列化/反序列化往返一致——
 * 统一模型快照经 REST/WS 传输后必须能被消费方无损还原。</p>
 * <p>使用 {@link Jackson2ObjectMapperBuilder} 构造 mapper，与 Spring Boot 运行时
 * 一致（注册 JavaTimeModule、时间写 ISO-8601、忽略未知属性）。</p>
 */
class UnifiedDeviceJsonTest {

    /** 对齐 Spring Boot 自动配置：时间写 ISO-8601 字符串（WRITE_DATES_AS_TIMESTAMPS 默认关闭） */
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /** 全字段非默认值构造：任一路径静默丢字段都会导致往返断言失败 */
    private UnifiedDevice fullDevice() {
        return UnifiedDevice.builder()
                .deviceId(UnifiedDevice.deviceIdOf(DeviceVendor.DJI, "1581F5BKD22A000A0001"))
                .deviceSn("1581F5BKD22A000A0001")
                .vendor(DeviceVendor.DJI)
                .model("DOCK3")
                .deviceType(DeviceType.DOCK)
                .source(DeviceSource.SIMULATED)
                .lifecycleState(LifecycleState.MISSION)
                .online(true)
                .position(Position.builder()
                        .longitude(104.07).latitude(30.67).altitude(120.5).elevation(500.0)
                        .build())
                .attitude(Attitude.builder().pitch(1.5).roll(-0.5).yaw(270.0).build())
                .battery(BatteryInfo.builder()
                        .percent(80).voltageMv(17200).temperatureC(30).chargeState(1)
                        .build())
                .gps(GpsInfo.builder().positionState(2).satellites(32).build())
                .rtk(RtkInfo.builder().rtkState(1).baseStationSn("RTK-BASE-001").build())
                .flightMode("WAYLINE")
                .missionId("mission-2026-001")
                .camera(CameraInfo.builder()
                        .payloadIndex("98-0-0").mode(1).recordingState(1).photoState(0)
                        .build())
                .gimbal(GimbalInfo.builder().pitch(-45.0).roll(2.0).yaw(180.0).build())
                .payload(PayloadInfo.builder()
                        .type("CAMERA").name("M4TD-Camera").index(0).working(true)
                        .build())
                .capabilities(Capabilities.builder()
                        .supportsRtk(true)
                        .supportsDroneInDock(true)
                        .payloadTypes(List.of("CAMERA", "SEARCHLIGHT", "SPEAKER"))
                        .dockModels(List.of("DOCK1", "DOCK2", "DOCK3"))
                        .build())
                .vendorExt(Map.of("dji.mode_code", 5, "dji.step", 24))
                .build();
    }

    @DisplayName("TC-CORE-001-A：UnifiedDevice 全字段 JSON 往返一致")
    @Test
    void unifiedDeviceRoundTrip() throws Exception {
        UnifiedDevice original = fullDevice();
        String json = mapper.writeValueAsString(original);
        UnifiedDevice decoded = mapper.readValue(json, UnifiedDevice.class);
        assertEquals(original, decoded, "往返后必须与原始快照逐字段相等");
    }

    @DisplayName("TC-CORE-001-B：UnifiedMission 全字段 JSON 往返一致（含嵌套 Waypoint）")
    @Test
    void unifiedMissionRoundTrip() throws Exception {
        UnifiedMission original = UnifiedMission.builder()
                .missionId("mission-2026-001")
                .name("巡检航线A")
                .state("EXECUTING")
                .percent(60)
                .currentWaypointIndex(2)
                .waypoints(List.of(
                        Waypoint.builder().index(0).longitude(104.07).latitude(30.67)
                                .altitude(120.0).speed(10.0).actions(List.of("PHOTO")).build(),
                        Waypoint.builder().index(1).longitude(104.08).latitude(30.68)
                                .altitude(120.0).speed(0.0).actions(List.of()).build()))
                .vendorExt(Map.of("dji.step", 26, "dji.percent", 60))
                .build();

        String json = mapper.writeValueAsString(original);
        UnifiedMission decoded = mapper.readValue(json, UnifiedMission.class);
        assertEquals(original, decoded, "往返后必须与原始任务快照逐字段相等");
    }

    @DisplayName("TC-CORE-001-C：Alarm 全字段 JSON 往返一致（raisedAt 用 ISO-8601 传输）")
    @Test
    void alarmRoundTrip() throws Exception {
        Alarm original = Alarm.builder()
                .alarmId("hms-2026-0001")
                .code("LOW_BATTERY")
                .level(2)
                .message("电量低于 20%，即将触发返航")
                .deviceId(UnifiedDevice.deviceIdOf(DeviceVendor.DJI, "1581F5BKD22A000A0001"))
                .raisedAt(Instant.parse("2026-10-07T10:00:00Z"))
                .vendorExt(Map.of("dji.hms_id", 100001, "dji.module", 0))
                .build();

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"2026-10-07T10:00:00Z\""),
                "逻辑时间应以 ISO-8601 字符串传输（与 Spring Boot 运行时一致）");
        Alarm decoded = mapper.readValue(json, Alarm.class);
        assertEquals(original, decoded, "往返后必须与原始告警逐字段相等");
    }

    @DisplayName("TC-CORE-001-D：UnifiedDevice 序列化包含全部契约字段名（防静默丢字段）")
    @Test
    void unifiedDeviceJsonContainsContractFieldNames() throws Exception {
        String json = mapper.writeValueAsString(fullDevice());
        for (String key : new String[]{
                "deviceId", "deviceSn", "vendor", "model", "deviceType", "source",
                "lifecycleState", "online", "position", "attitude", "battery", "gps", "rtk",
                "flightMode", "missionId", "camera", "gimbal", "payload", "capabilities", "vendorExt"}) {
            assertTrue(json.contains("\"" + key + "\""), "JSON 缺少契约字段: " + key);
        }
    }

    @DisplayName("TC-CORE-001-E：deviceId 构造约定为 vendor:deviceSn")
    @Test
    void deviceIdConventionVendorColonSn() {
        assertEquals("DJI:SN-001", UnifiedDevice.deviceIdOf(DeviceVendor.DJI, "SN-001"));
        assertEquals("SIKONG:SK-001", UnifiedDevice.deviceIdOf(DeviceVendor.SIKONG, "SK-001"));
    }
}
