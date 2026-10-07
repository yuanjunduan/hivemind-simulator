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

package ltd.cdmi.hivemind.simulator.core.engine.flight;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ltd.cdmi.hivemind.simulator.core.model.Waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FlightIntent JSON 契约单测（TC-CORE-083，实施方案 §2.9.2 意图 API）。
 *
 * <p>验证 sealed interface 的 Jackson 多态往返：{@code type} 属性判别 + record 值相等；
 * 未知 type 反序列化 fail-fast。该契约即 {@code POST /api/unified/devices/{deviceId}/intents}
 * 的 body 格式。</p>
 */
class FlightIntentJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 序列化 → type 判别正确 → 反序列化 → record 值相等 */
    private void assertRoundTrip(FlightIntent intent, String expectedType) throws Exception {
        String json = mapper.writeValueAsString(intent);
        JsonNode node = mapper.readTree(json);
        assertEquals(expectedType, node.path("type").asText(), "type 判别属性");

        FlightIntent back = mapper.readValue(json, FlightIntent.class);
        assertEquals(intent, back, "JSON 往返后 record 值应相等");
    }

    @DisplayName("TC-CORE-083：13 种意图 JSON 多态往返（Takeoff/Goto/ExecuteWaypoints 等）")
    @Test
    void allThirteenIntentsRoundTrip() throws Exception {
        assertRoundTrip(new FlightIntent.Takeoff(100.0), "Takeoff");
        assertRoundTrip(new FlightIntent.Land(), "Land");
        assertRoundTrip(new FlightIntent.Goto(104.07, 30.67, 100.0, 12.0), "Goto");
        assertRoundTrip(new FlightIntent.Hover(), "Hover");
        assertRoundTrip(new FlightIntent.PauseMission(), "PauseMission");
        assertRoundTrip(new FlightIntent.ResumeMission(), "ResumeMission");
        assertRoundTrip(new FlightIntent.ReturnHome(120), "ReturnHome");
        assertRoundTrip(new FlightIntent.ReturnHome(null), "ReturnHome");
        assertRoundTrip(new FlightIntent.SetSpeed(5.0), "SetSpeed");
        assertRoundTrip(new FlightIntent.SetAltitude(50.0), "SetAltitude");
        assertRoundTrip(new FlightIntent.Yaw(90.0), "Yaw");
        assertRoundTrip(new FlightIntent.Arm(), "Arm");
        assertRoundTrip(new FlightIntent.Disarm(), "Disarm");
        assertRoundTrip(new FlightIntent.ExecuteWaypoints(List.of(
                Waypoint.builder().index(0).longitude(104.10).latitude(30.60).altitude(50.0).speed(0)
                        .actions(List.of("PHOTO")).build(),
                Waypoint.builder().index(1).longitude(104.20).latitude(30.70).altitude(60.0).speed(5.0)
                        .actions(List.of()).build()), 8.0), "ExecuteWaypoints");
    }

    @DisplayName("TC-CORE-083：未知 type 反序列化 fail-fast（不静默回退）")
    @Test
    void unknownTypeFailsFast() {
        assertThrows(Exception.class,
                () -> mapper.readValue("{\"type\":\"FlyToMoon\"}", FlightIntent.class));
    }
}
