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

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightIntent;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionPlan;
import ltd.cdmi.hivemind.simulator.handler.FlightCommandSimulator;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;

/**
 * DJI 飞行意图下行适配器（实施方案 §2.9.2）——统一 {@link FlightIntent} → 现有内部执行路径。
 *
 * <p>不经过 MQTT 协议解析层，直接调用 Handler 的内部入口方法（与协议驱动路径共用同一
 * 执行体，行为一致）：</p>
 * <ul>
 *   <li>{@code Takeoff} → {@link FlightCommandSimulator#takeoffToInternal}（一键起飞；原地起飞到目标高度）</li>
 *   <li>{@code Goto} → {@link FlightCommandSimulator#flyToInternal}（flyto 语义，maxSpeed 传入）</li>
 *   <li>{@code ExecuteWaypoints} → {@link WaylineTaskSimulator#startMissionInternal}（任务启动）</li>
 *   <li>{@code ReturnHome} / {@code Land} → {@link WaylineTaskSimulator#returnHomeInternal}（返航/降落归舱）</li>
 *   <li>{@code PauseMission} / {@code ResumeMission} → {@link WaylineTaskSimulator#pauseMissionInternal} /
 *       {@link WaylineTaskSimulator#resumeMissionInternal}</li>
 * </ul>
 *
 * <p>W1 未接入（返回 rejected，W2 场景引擎扩展）：{@code Hover/SetSpeed/SetAltitude/Yaw/Arm/Disarm}
 * 当前无对应 DJI 服务执行体。</p>
 *
 * <p>对外路由见 {@code web/UnifiedIntentController}
 * （{@code POST /api/unified/devices/{deviceId}/intents}）。</p>
 */
@Component
public class DjiFlightIntentHandler {

    private static final Logger log = LoggerFactory.getLogger(DjiFlightIntentHandler.class);

    private final FlightCommandSimulator flightCommand;
    private final WaylineTaskSimulator wayline;

    @Autowired
    public DjiFlightIntentHandler(FlightCommandSimulator flightCommand, WaylineTaskSimulator wayline) {
        this.flightCommand = flightCommand;
        this.wayline = wayline;
    }

    /**
     * 意图处理结果（REST 响应体）。
     * <p>{@code accepted=false} 时 {@code detail} 说明拒绝原因；HTTP 状态仍为 200，
     * 由 {@code accepted} 区分业务结果（与 services_reply 的 result 语义对齐）。</p>
     */
    public record IntentResult(boolean accepted, String detail) {

        /** 处理成功 */
        public static IntentResult accepted(String detail) {
            return new IntentResult(true, detail);
        }

        /** 处理被拒绝（W1 未接入 / 参数非法 / 执行体拒绝等） */
        public static IntentResult rejected(String detail) {
            return new IntentResult(false, detail);
        }
    }

    /**
     * 处理统一飞行意图。
     *
     * @param intent 统一飞行意图（非空）
     * @return 处理结果（accepted / rejected + 详情）
     */
    public IntentResult handle(FlightIntent intent) {
        Objects.requireNonNull(intent, "intent");

        IntentResult result = switch (intent) {
            case FlightIntent.Goto g -> toResult(
                    flightCommand.flyToInternal(newFlyToId(), g.lat(), g.lng(), g.alt(), g.maxSpeed(), null),
                    "flyto 已启动");
            case FlightIntent.Takeoff t -> takeoffInternal(t);
            case FlightIntent.ExecuteWaypoints e -> executeWaypointsInternal(e);
            case FlightIntent.ReturnHome rh -> toResult(wayline.returnHomeInternal(rh.rthAltitude()), "返航已启动");
            case FlightIntent.Land l -> toResult(wayline.returnHomeInternal(null),
                    "Land 按 DJI return_home 语义执行（自动下降归舱）");
            case FlightIntent.PauseMission p -> toResult(wayline.pauseMissionInternal(), "任务已暂停");
            case FlightIntent.ResumeMission rm -> toResult(wayline.resumeMissionInternal(), "任务已恢复");
            default -> IntentResult.rejected("意图 " + intent.getClass().getSimpleName()
                    + " 在 W1 未接入内部执行路径（W2 场景引擎扩展）");
        };
        log.info("统一飞行意图处理: intent={}, accepted={}, detail={}",
                intent.getClass().getSimpleName(), result.accepted(), result.detail());
        return result;
    }

    /** Takeoff → 一键起飞内部入口（目标点水平坐标取当前位置，原地起飞到 targetAltitude） */
    private IntentResult takeoffInternal(FlightIntent.Takeoff t) {
        String flightId = "intent-" + UUID.randomUUID();
        Map<String, Object> reply = flightCommand.takeoffToInternal(flightId, t.targetAltitude(), 0, null);
        return toResult(reply, "一键起飞已启动: flight_id=" + flightId);
    }

    /** ExecuteWaypoints → 任务启动内部入口（missionId 自动生成；cruiseSpeed 进 MissionPlan） */
    private IntentResult executeWaypointsInternal(FlightIntent.ExecuteWaypoints e) {
        if (e.waypoints() == null || e.waypoints().isEmpty()) {
            return IntentResult.rejected("ExecuteWaypoints 航点列表为空，拒绝启动任务");
        }
        MissionPlan plan = new MissionPlan("intent-" + UUID.randomUUID(), e.waypoints(), e.cruiseSpeed(), Map.of());
        Map<String, Object> reply = wayline.startMissionInternal(plan);
        return toResult(reply, "任务已启动: mission_id=" + plan.missionId());
    }

    /** 业务侧 flyto 任务 id（统一意图不带该字段，由适配层生成） */
    private static String newFlyToId() {
        return "intent-" + UUID.randomUUID();
    }

    /** services_reply 风格结果（result=0 视为成功）转 IntentResult */
    private static IntentResult toResult(Map<String, Object> reply, String okDetail) {
        if (reply != null && Integer.valueOf(0).equals(reply.get("result"))) {
            return IntentResult.accepted(okDetail);
        }
        return IntentResult.rejected("执行体拒绝执行: " + reply);
    }
}
