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

package ltd.cdmi.hivemind.simulator.core.engine.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ltd.cdmi.hivemind.simulator.core.event.TelemetryUpdatedEvent;
import ltd.cdmi.hivemind.simulator.core.model.DeviceSource;
import ltd.cdmi.hivemind.simulator.core.model.DeviceType;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * TelemetryWebSocketHandler 单测（TC-CORE-065~067，实施方案 §2.8）。
 *
 * <p>验证：广播 JSON 封包契约 {@code {type, occurredAt, device}}（时间戳 ISO-8601）、
 * 断开连接停止推送、单会话发送失败隔离（只关闭该会话）。</p>
 */
class TelemetryWebSocketHandlerTest {

    private static final Instant LOGICAL_NOW = Instant.parse("2026-10-07T10:00:00Z");

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private TelemetryUpdatedEvent event() {
        UnifiedDevice device = UnifiedDevice.builder()
                .deviceId(UnifiedDevice.deviceIdOf(DeviceVendor.DJI, "SN-001"))
                .deviceSn("SN-001")
                .vendor(DeviceVendor.DJI)
                .model("DOCK3")
                .deviceType(DeviceType.DOCK)
                .source(DeviceSource.SIMULATED)
                .lifecycleState(LifecycleState.MISSION)
                .online(true)
                .flightMode("WAYLINE")
                .vendorExt(Map.of("dji.mode_code", 5))
                .build();
        return new TelemetryUpdatedEvent(device.getDeviceId(), LOGICAL_NOW, device);
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession session = Mockito.mock(WebSocketSession.class);
        Mockito.when(session.getId()).thenReturn(id);
        Mockito.when(session.isOpen()).thenReturn(true);
        return session;
    }

    @DisplayName("TC-CORE-065：广播 JSON 封包 {type, occurredAt, device}（时间戳 ISO-8601）")
    @Test
    void broadcastEnvelopeContract() throws Exception {
        TelemetryWebSocketHandler handler = new TelemetryWebSocketHandler(mapper);
        WebSocketSession session = openSession("s1");
        handler.afterConnectionEstablished(session);

        handler.onTelemetryUpdated(event());

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        Mockito.verify(session).sendMessage(captor.capture());
        JsonNode envelope = mapper.readTree(captor.getValue().getPayload());
        assertEquals("TELEMETRY_UPDATED", envelope.path("type").asText());
        assertTrue(envelope.path("occurredAt").asText().startsWith("2026-10-07T10:00:00"),
                "occurredAt 应为逻辑时间 ISO-8601 字符串");
        JsonNode device = envelope.path("device");
        assertEquals("DJI:SN-001", device.path("deviceId").asText());
        assertEquals("WAYLINE", device.path("flightMode").asText());
        assertEquals(5, device.path("vendorExt").path("dji.mode_code").asInt());
    }

    @DisplayName("TC-CORE-066：连接断开后不再推送（无订阅时广播为空操作）")
    @Test
    void closedSessionReceivesNothing() throws Exception {
        TelemetryWebSocketHandler handler = new TelemetryWebSocketHandler(mapper);
        WebSocketSession session = openSession("s1");
        handler.afterConnectionEstablished(session);
        assertEquals(1, handler.sessionCount());

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals(0, handler.sessionCount());

        handler.onTelemetryUpdated(event());
        Mockito.verify(session, Mockito.never()).sendMessage(Mockito.any(TextMessage.class));
    }

    @DisplayName("TC-CORE-067：单会话发送失败隔离——失败会话被关闭移除，其他订阅者正常收到")
    @Test
    void failedSessionIsolated() throws Exception {
        TelemetryWebSocketHandler handler = new TelemetryWebSocketHandler(mapper);
        WebSocketSession bad = openSession("bad");
        WebSocketSession good = openSession("good");
        Mockito.doThrow(new IOException("send failed"))
                .when(bad).sendMessage(Mockito.any(TextMessage.class));
        handler.afterConnectionEstablished(bad);
        handler.afterConnectionEstablished(good);

        handler.onTelemetryUpdated(event());

        Mockito.verify(good).sendMessage(Mockito.any(TextMessage.class));
        Mockito.verify(bad).close(Mockito.any(CloseStatus.class));
        assertEquals(1, handler.sessionCount(), "失败会话应被移除，健康会话保留");
    }
}
