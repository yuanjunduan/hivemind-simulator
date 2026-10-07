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

package ltd.cdmi.hivemind.simulator.core.fault;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.event.AlarmRaisedEvent;
import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.FaultInjectedEvent;
import ltd.cdmi.hivemind.simulator.core.model.Alarm;
import ltd.cdmi.hivemind.simulator.device.DeviceState;

/**
 * 飞行故障注入器（T2.4）——直接回写 {@link DeviceState} 并发布告警事件。
 *
 * <ul>
 *   <li>{@code gps_loss}：positionState=0（未定位）+ {@code GPS_LOST} 严重告警；{@code gps_recover} 恢复为 GPS 定位(1)；</li>
 *   <li>{@code rtk_loss}：positionState=1（GPS 定位）+ {@code RTK_LOST} 告警；{@code rtk_recover} 恢复 RTK 固定解(2)；</li>
 *   <li>{@code set_battery}：batteryPercent 回写；低于 20% 附加 {@code LOW_BATTERY} 告警；</li>
 *   <li>{@code trigger_alarm}：按 {@code type} 触发自定义 HMS 风格告警；</li>
 *   <li>{@code task_fail}：发布 {@code TASK_FAILED} 严重告警（任务进度失败上报由场景分发器负责）。</li>
 * </ul>
 *
 * <p>positionState 语义对齐 DJI OSD（0=未定位, 1=GPS定位, 2=RTK固定解）；后续 OSD 上报
 * （TelemetryEngine）按各自频率读取，无需注入器主动触发上报。</p>
 */
@Component
public class FlightFaultInjector implements FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FlightFaultInjector.class);

    /** 低电量阈值（%，低于此值附加 LOW_BATTERY 告警） */
    private static final int LOW_BATTERY_THRESHOLD = 20;

    private final DeviceState state;
    private final EventBus eventBus;

    public FlightFaultInjector(DeviceState state, EventBus eventBus) {
        this.state = state;
        this.eventBus = eventBus;
    }

    @Override
    public String name() {
        return "flight";
    }

    @Override
    public void inject(String action, String deviceId, Map<String, Object> params) {
        switch (action) {
            case "gps_loss" -> {
                state.setPositionState(0);
                raiseAlarm(deviceId, "GPS_LOST", 3, "GPS 信号丢失（场景注入）");
            }
            case "gps_recover" -> state.setPositionState(1);
            case "rtk_loss" -> {
                state.setPositionState(1);
                raiseAlarm(deviceId, "RTK_LOST", 2, "RTK 差分定位丢失，降级为 GPS 定位（场景注入）");
            }
            case "rtk_recover" -> state.setPositionState(2);
            case "set_battery" -> {
                int value = asInt(params.get("value"));
                state.setBatteryPercent(value);
                if (value < LOW_BATTERY_THRESHOLD) {
                    raiseAlarm(deviceId, "LOW_BATTERY", 2, "低电量告警（场景注入）: " + value + "%");
                }
            }
            case "trigger_alarm" -> {
                String type = String.valueOf(params.get("type"));
                raiseAlarm(deviceId, type, 2, "场景触发告警: " + type);
            }
            case "task_fail" -> {
                String reason = String.valueOf(params.get("reason"));
                raiseAlarm(deviceId, "TASK_FAILED", 3, "任务失败（场景注入）: " + reason);
            }
            default -> {
                log.warn("FlightFaultInjector 收到未支持的 action: {}", action);
                return;
            }
        }
        log.info("[故障注入-飞行] action={}, device={}, positionState={}, battery={}",
                action, deviceId, state.getPositionState(), state.getBatteryPercent());
        eventBus.publish(new FaultInjectedEvent(deviceId, eventBus.logicalNow(), action, params, "flight"));
    }

    @Override
    public void clear() {
        state.setPositionState(2);
    }

    /** 发布统一告警（逻辑时间；vendorExt 空） */
    private void raiseAlarm(String deviceId, String code, int level, String message) {
        Alarm alarm = Alarm.builder()
                .alarmId("fault-" + UUID.randomUUID())
                .code(code)
                .level(level)
                .message(message)
                .deviceId(deviceId)
                .raisedAt(eventBus.logicalNow())
                .vendorExt(Map.of())
                .build();
        eventBus.publish(new AlarmRaisedEvent(deviceId, alarm.getRaisedAt(), alarm));
        log.info("[故障注入-告警] code={}, level={}, device={}, message={}", code, level, deviceId, message);
    }

    private static int asInt(Object raw) {
        return raw instanceof Number n ? n.intValue() : 0;
    }
}
