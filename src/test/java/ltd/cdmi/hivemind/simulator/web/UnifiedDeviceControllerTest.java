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

package ltd.cdmi.hivemind.simulator.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ltd.cdmi.hivemind.simulator.core.engine.telemetry.TelemetryEngine;
import ltd.cdmi.hivemind.simulator.core.model.DeviceSource;
import ltd.cdmi.hivemind.simulator.core.model.DeviceType;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * UnifiedDeviceController 单测（TC-CORE-070~072，实施方案 §2.8）。
 *
 * <p>验证 REST 契约：GET /api/unified/devices（全量）与 /{deviceId}（单设备，未命中 404）。</p>
 */
class UnifiedDeviceControllerTest {

    private TelemetryEngine engine;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock(TelemetryEngine.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UnifiedDeviceController(engine)).build();
    }

    private UnifiedDevice device() {
        return UnifiedDevice.builder()
                .deviceId(UnifiedDevice.deviceIdOf(DeviceVendor.DJI, "SN-001"))
                .deviceSn("SN-001")
                .vendor(DeviceVendor.DJI)
                .model("DOCK3")
                .deviceType(DeviceType.DOCK)
                .source(DeviceSource.SIMULATED)
                .lifecycleState(LifecycleState.CHARGING)
                .online(true)
                .flightMode("READY")
                .build();
    }

    @DisplayName("TC-CORE-070：GET /api/unified/devices 返回全部快照数组")
    @Test
    void listAllDevices() throws Exception {
        Mockito.when(engine.all()).thenReturn(List.of(device()));

        mockMvc.perform(get("/api/unified/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("DJI:SN-001"))
                .andExpect(jsonPath("$[0].model").value("DOCK3"))
                .andExpect(jsonPath("$[0].lifecycleState").value("CHARGING"));
    }

    @DisplayName("TC-CORE-071：GET /api/unified/devices/{deviceId} 命中返回快照")
    @Test
    void detailFound() throws Exception {
        Mockito.when(engine.find("DJI:SN-001")).thenReturn(device());

        mockMvc.perform(get("/api/unified/devices/DJI:SN-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("DJI:SN-001"))
                .andExpect(jsonPath("$.online").value(true));
    }

    @DisplayName("TC-CORE-072：GET /api/unified/devices/{deviceId} 未命中返回 404")
    @Test
    void detailNotFound() throws Exception {
        Mockito.when(engine.find("DJI:UNKNOWN")).thenReturn(null);

        mockMvc.perform(get("/api/unified/devices/DJI:UNKNOWN"))
                .andExpect(status().isNotFound());
    }
}
