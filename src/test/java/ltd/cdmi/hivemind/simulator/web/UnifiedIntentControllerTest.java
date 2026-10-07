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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ltd.cdmi.hivemind.simulator.adapter.dji.DjiFlightIntentHandler;
import ltd.cdmi.hivemind.simulator.adapter.dji.DjiFlightIntentHandler.IntentResult;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightIntent;
import ltd.cdmi.hivemind.simulator.core.engine.telemetry.TelemetryEngine;
import ltd.cdmi.hivemind.simulator.core.model.DeviceSource;
import ltd.cdmi.hivemind.simulator.core.model.DeviceType;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * UnifiedIntentController 单测（TC-CORE-080~082，实施方案 §2.9.2 意图 API）。
 *
 * <p>验证 REST 契约：{@code POST /api/unified/devices/{deviceId}/intents}
 * （body 为 FlightIntent JSON；设备不存在 404；启动窗口期按 SN 放行）。</p>
 */
class UnifiedIntentControllerTest {

    private TelemetryEngine engine;
    private DjiFlightIntentHandler handler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock(TelemetryEngine.class);
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        Mockito.when(runtimeConfig.getGatewaySn()).thenReturn("SN-001");
        handler = Mockito.mock(DjiFlightIntentHandler.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new UnifiedIntentController(engine, runtimeConfig, handler)).build();
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

    @DisplayName("TC-CORE-080：POST intents 快照命中 → 200 + 意图结果（FlightIntent JSON 反序列化）")
    @Test
    void intentHandledWhenSnapshotExists() throws Exception {
        Mockito.when(engine.find("DJI:SN-001")).thenReturn(device());
        Mockito.when(handler.handle(Mockito.any(FlightIntent.class)))
                .thenReturn(IntentResult.accepted("flyto 已启动"));

        mockMvc.perform(post("/api/unified/devices/DJI:SN-001/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"Goto\",\"lng\":104.07,\"lat\":30.67,\"alt\":100.0,\"maxSpeed\":12.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.detail").value("flyto 已启动"));

        // 验证 body 正确反序列化为 Goto 意图到达 handler
        ArgumentCaptor<FlightIntent> captor = ArgumentCaptor.forClass(FlightIntent.class);
        Mockito.verify(handler).handle(captor.capture());
        assertEquals(new FlightIntent.Goto(104.07, 30.67, 100.0, 12.0), captor.getValue());
    }

    @DisplayName("TC-CORE-081：POST intents 设备不存在 → 404 且不触达 handler")
    @Test
    void deviceNotFoundReturns404() throws Exception {
        Mockito.when(engine.find("DJI:UNKNOWN")).thenReturn(null);

        mockMvc.perform(post("/api/unified/devices/DJI:UNKNOWN/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"Land\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.accepted").value(false));

        Mockito.verifyNoInteractions(handler);
    }

    @DisplayName("TC-CORE-082：无快照但 deviceId 与运行时 SN 一致 → 200（启动窗口期放行）")
    @Test
    void startupWindowAllowedBySn() throws Exception {
        Mockito.when(engine.find("DJI:SN-001")).thenReturn(null);
        Mockito.when(handler.handle(Mockito.any(FlightIntent.class)))
                .thenReturn(IntentResult.rejected("意图 Hover 在 W1 未接入内部执行路径（W2 场景引擎扩展）"));

        mockMvc.perform(post("/api/unified/devices/DJI:SN-001/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"Hover\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

        ArgumentCaptor<FlightIntent> captor = ArgumentCaptor.forClass(FlightIntent.class);
        Mockito.verify(handler).handle(captor.capture());
        assertEquals(new FlightIntent.Hover(), captor.getValue());
    }
}
