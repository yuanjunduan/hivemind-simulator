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

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ltd.cdmi.hivemind.simulator.core.event.TelemetryUpdatedEvent;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * 遥测 WebSocket 推送端点（实施方案 §2.8）：{@code /ws/simulator/telemetry}。
 *
 * <p>订阅 {@link TelemetryUpdatedEvent}（§2.4 订阅方约定：W1 订阅方为 WS handler 与诊断日志），
 * 向全部连接广播 JSON 封包 {@code {type, occurredAt, device}}——type 为事件类型标签
 * （当前仅 {@value #TYPE_TELEMETRY_UPDATED}，为后续事件扩展预留），device 为不可变的
 * {@link UnifiedDevice} 快照。推送频率跟随 OSD 投影（0.5Hz 逻辑时间，ADR-5）。</p>
 *
 * <p>会话隔离：单个会话发送失败只关闭该会话并记录日志，不影响其他订阅者，也不向
 * 发布方（投影钩子）传播异常。</p>
 */
@Component
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryWebSocketHandler.class);

    /** WS 端点路径（web-console 订阅入口） */
    public static final String PATH = "/ws/simulator/telemetry";

    /** 事件类型标签：遥测快照更新 */
    public static final String TYPE_TELEMETRY_UPDATED = "TELEMETRY_UPDATED";

    private final ObjectMapper objectMapper;

    /** 当前连接会话（id → session，连接/断开由容器线程驱动） */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public TelemetryWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("遥测 WS 订阅建立：session={}，当前订阅数={}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("遥测 WS 订阅断开：session={}，status={}，当前订阅数={}",
                session.getId(), status, sessions.size());
    }

    /**
     * {@link TelemetryUpdatedEvent} 订阅：向全部会话广播 JSON 封包。
     * <p>同步发布（EventBus 语义）：本方法内的异常不得传播给发布方——单会话失败隔离处理。</p>
     */
    @EventListener
    public void onTelemetryUpdated(TelemetryUpdatedEvent event) {
        if (sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new Envelope(
                    TYPE_TELEMETRY_UPDATED, event.getOccurredAtInstant(), event.getDevice()));
        } catch (JsonProcessingException e) {
            log.error("遥测快照序列化失败，本轮跳过推送: {}", e.getMessage(), e);
            return;
        }
        for (WebSocketSession session : sessions.values()) {
            sendTo(session, payload);
        }
    }

    /** 单会话发送（异常隔离：失败即关闭并移除该会话） */
    private void sendTo(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.warn("遥测 WS 推送失败，关闭会话 session={}: {}", session.getId(), e.getMessage());
            sessions.remove(session.getId());
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // 关闭失败无需处理：会话已从推送集合移除
            }
        }
    }

    /** 当前订阅数（测试/诊断用） */
    int sessionCount() {
        return sessions.size();
    }

    /**
     * WS 消息封包（§2.8 契约：{@code {type, occurredAt, device}}）。
     * <p>occurredAt 为逻辑时间（ISO-8601 字符串传输，与 REST 序列化一致）。</p>
     */
    private record Envelope(String type, Instant occurredAt, UnifiedDevice device) {
    }
}
