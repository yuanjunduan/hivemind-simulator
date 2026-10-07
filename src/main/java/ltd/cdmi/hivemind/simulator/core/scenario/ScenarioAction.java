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

package ltd.cdmi.hivemind.simulator.core.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景 Action 契约表（v1，冻结；实施方案 §4.1.1）。
 *
 * <p>契约冻结含义：名称、作用域与参数规则自 W2 起不再随意追加（变更走 §7.3 评审）。
 * 未知 action 由解析器 fail-fast 拒绝并列出本表全部支持项，不做静默忽略。</p>
 *
 * <p>作用域：{@link Scope#DEVICE} 的 action 必须绑定场景内设备 ID（执行器解析目标设备）；
 * {@link Scope#SCENARIO} 的 action 作用于场景自身（如变速）。</p>
 */
public enum ScenarioAction {

    // ==================== 状态回写 ====================
    /** 设置电量（DJI 回写 DeviceState.batteryPercent） */
    SET_BATTERY("set_battery", Scope.DEVICE),
    /** 触发 HMS 告警（复用现有 HmsSimulator 场景） */
    TRIGGER_ALARM("trigger_alarm", Scope.DEVICE),

    // ==================== 飞行意图（走 §2.9.2 意图通道） ====================
    /** 一键起飞到目标相对高度（m） */
    TAKEOFF("takeoff", Scope.DEVICE),
    /** 飞向目标点 */
    GOTO("goto", Scope.DEVICE),
    /** 返航（rthAltitude 可选，相对高度 m） */
    RETURN_HOME("return_home", Scope.DEVICE),
    /** 降落（DJI return_home 语义） */
    LAND("land", Scope.DEVICE),

    // ==================== 任务引擎 ====================
    /** 暂停任务 */
    PAUSE_MISSION("pause_mission", Scope.DEVICE),
    /** 恢复任务 */
    RESUME_MISSION("resume_mission", Scope.DEVICE),
    /** 取消任务 */
    CANCEL_MISSION("cancel_mission", Scope.DEVICE),
    /** 任务失败注入（触发 FAILED 路径 + 进度事件） */
    TASK_FAIL("task_fail", Scope.DEVICE),

    // ==================== 网络故障（消息级语义，文档如实声明） ====================
    /** 断开 MQTT（复用现有 /disconnect 底层） */
    NETWORK_DISCONNECT("network_disconnect", Scope.DEVICE),
    /** 重连 */
    NETWORK_RECONNECT("network_reconnect", Scope.DEVICE),
    /** 下行消息延迟注入 */
    NETWORK_DELAY("network_delay", Scope.DEVICE),
    /** 下行消息丢弃率注入 */
    NETWORK_DROP("network_drop", Scope.DEVICE),

    // ==================== 飞行故障 ====================
    /** GPS 丢失（positionState=0 + 告警） */
    GPS_LOSS("gps_loss", Scope.DEVICE),
    /** GPS 恢复 */
    GPS_RECOVER("gps_recover", Scope.DEVICE),
    /** RTK 丢失 */
    RTK_LOSS("rtk_loss", Scope.DEVICE),
    /** RTK 恢复 */
    RTK_RECOVER("rtk_recover", Scope.DEVICE),

    // ==================== 媒体故障 ====================
    /** 停 FFmpeg 推流 */
    VIDEO_STREAM_STOP("video_stream_stop", Scope.DEVICE),
    /** 恢复 FFmpeg 推流 */
    VIDEO_STREAM_RECOVER("video_stream_recover", Scope.DEVICE),
    /** 上传失败率注入 */
    MEDIA_UPLOAD_FAIL("media_upload_fail", Scope.DEVICE),

    // ==================== 场景级 ====================
    /** 场景内变速（走时钟热切换） */
    SET_CLOCK_SPEED("set_clock_speed", Scope.SCENARIO);

    /** action 作用域 */
    public enum Scope {
        /** 作用于设备（必须绑定场景内设备 ID） */
        DEVICE,
        /** 作用于场景自身 */
        SCENARIO
    }

    private static final Map<String, ScenarioAction> BY_WIRE_NAME = new LinkedHashMap<>();

    static {
        for (ScenarioAction a : values()) {
            BY_WIRE_NAME.put(a.wireName, a);
        }
    }

    private final String wireName;
    private final Scope scope;

    ScenarioAction(String wireName, Scope scope) {
        this.wireName = wireName;
        this.scope = scope;
    }

    /** YAML 中的契约定名（snake_case） */
    public String wireName() {
        return wireName;
    }

    /** action 作用域 */
    public Scope scope() {
        return scope;
    }

    /** 按契约定名查找；未知返回 null（调用方 fail-fast 并提示 {@link #supportedNames()}） */
    public static ScenarioAction fromWireName(String name) {
        return name == null ? null : BY_WIRE_NAME.get(name);
    }

    /** 全部支持的 action 名（逗号分隔，用于未知 action 的报错提示） */
    public static String supportedNames() {
        return String.join(", ", BY_WIRE_NAME.keySet());
    }

    /**
     * 参数校验（Schema v1 冻结规则）。
     *
     * @param params 事件参数（不含 at/action）
     * @return 错误列表（空 = 通过；多条错误一次返回，便于一次修完）
     */
    public List<String> validateParams(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        switch (this) {
            case SET_BATTERY -> requireNumberInRange(params, "value", 0, 100, errors);
            case TRIGGER_ALARM -> requireNonBlankString(params, "type", errors);
            case TAKEOFF -> requirePositive(params, "altitude", errors);
            case GOTO -> {
                requireFiniteNumber(params, "lng", errors);
                requireFiniteNumber(params, "lat", errors);
                requireFiniteNumber(params, "alt", errors);
                optionalNumberInRange(params, "maxSpeed", 0, Double.MAX_VALUE, errors);
            }
            case RETURN_HOME -> optionalNumberInRange(params, "rthAltitude", 0, Double.MAX_VALUE, errors);
            case LAND, PAUSE_MISSION, RESUME_MISSION, CANCEL_MISSION,
                 NETWORK_DISCONNECT, NETWORK_RECONNECT, GPS_LOSS, GPS_RECOVER,
                 RTK_LOSS, RTK_RECOVER, VIDEO_STREAM_STOP, VIDEO_STREAM_RECOVER -> {
                // 无参数契约：忽略多余字段（向前兼容），不报错
            }
            case TASK_FAIL -> requireNonBlankString(params, "reason", errors);
            case NETWORK_DELAY -> {
                requirePositive(params, "ms", errors);
                optionalNumberInRange(params, "jitterMs", 0, Double.MAX_VALUE, errors);
            }
            case NETWORK_DROP, MEDIA_UPLOAD_FAIL -> requireNumberInRange(params, "percent", 0, 100, errors);
            case SET_CLOCK_SPEED -> requirePositive(params, "speed", errors);
        }
        return errors;
    }

    // ==================== 参数校验工具 ====================

    /** 必填 + 取值范围校验（缺失/非数字/越界均为错误） */
    private static void requireNumberInRange(Map<String, Object> params, String key,
                                             double min, double max, List<String> errors) {
        Double v = asNumber(params.get(key));
        if (v == null) {
            errors.add("缺少必填参数（数字）: " + key);
            return;
        }
        if (!(v >= min) || v > max) {
            errors.add("参数 " + key + " 超出范围 [" + fmt(min) + ", " + fmt(max) + "]: " + fmt(v));
        }
    }

    /** 可选 + 取值范围校验（存在时校验，缺失放行） */
    private static void optionalNumberInRange(Map<String, Object> params, String key,
                                              double min, double max, List<String> errors) {
        if (!params.containsKey(key)) {
            return;
        }
        Double v = asNumber(params.get(key));
        if (v == null) {
            errors.add("参数 " + key + " 必须为数字: " + params.get(key));
            return;
        }
        if (!(v >= min) || v > max) {
            errors.add("参数 " + key + " 超出范围 [" + fmt(min) + ", " + fmt(max) + "]: " + fmt(v));
        }
    }

    /** 必填 + 正有限值校验（>0） */
    private static void requirePositive(Map<String, Object> params, String key, List<String> errors) {
        Double v = asNumber(params.get(key));
        if (v == null) {
            errors.add("缺少必填参数（数字）: " + key);
            return;
        }
        if (!(v > 0) || !Double.isFinite(v)) {
            errors.add("参数 " + key + " 必须为正有限值: " + v);
        }
    }

    /** 必填 + 有限数字校验（任意实数） */
    private static void requireFiniteNumber(Map<String, Object> params, String key, List<String> errors) {
        Double v = asNumber(params.get(key));
        if (v == null) {
            errors.add("缺少必填参数（数字）: " + key);
            return;
        }
        if (!Double.isFinite(v)) {
            errors.add("参数 " + key + " 必须为有限数字: " + v);
        }
    }

    /** 必填 + 非空白字符串校验 */
    private static void requireNonBlankString(Map<String, Object> params, String key, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null || raw.toString().isBlank()) {
            errors.add("缺少必填参数（非空字符串）: " + key);
        }
    }

    private static Double asNumber(Object raw) {
        return raw instanceof Number n ? n.doubleValue() : null;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
    }
}
