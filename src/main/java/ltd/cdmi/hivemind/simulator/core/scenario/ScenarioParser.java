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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;

/**
 * 场景 YAML 解析器（Schema v1；实施方案 §4.1）。
 *
 * <p>解析 + 校验一次完成，错误聚合后以 {@link ScenarioValidationException} 抛出（fail-fast）：
 * <ul>
 *   <li>顶层结构：{@code scenario}（id/version 必填）、{@code devices}（至少 1 个）、{@code events}；</li>
 *   <li>{@code at} 时间严格格式：{@code 数字+单位}（{@code ms|s|m}），如 {@code 60s}、{@code 1500ms}、{@code 2m}；</li>
 *   <li>{@code action} 必须属于 {@link ScenarioAction} 契约表，未知 action 报错并列出全部支持项；</li>
 *   <li>action 参数按契约逐项校验（必填/类型/范围）。</li>
 * </ul></p>
 *
 * <p>无状态、线程安全：可单例复用；解析结果 {@link ScenarioDefinition} 不可变。</p>
 */
public class ScenarioParser {

    /** id 命名约束：小写字母/数字/连字符（与场景资产文件名兼容） */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9-]+$");

    /** at 时间格式：数字 + 单位（ms/s/m） */
    private static final Pattern AT_PATTERN = Pattern.compile("^(\\d+)(ms|s|m)$");

    /** deviceMode 允许值（DJI 接入模式；其余厂商由 Adapter 解释） */
    private static final Set<String> DEVICE_MODES = Set.of("DOCK", "PILOT");

    /**
     * 从文件解析场景。
     *
     * @param yamlPath 场景 YAML 文件路径
     * @return 校验通过的场景定义
     * @throws ScenarioValidationException 读取失败/语法错误/校验失败
     */
    public ScenarioDefinition parse(Path yamlPath) {
        String text;
        try {
            text = Files.readString(yamlPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ScenarioValidationException("场景文件读取失败: " + yamlPath + " - " + e.getMessage());
        }
        return parseText(text);
    }

    /**
     * 从文本解析场景（测试与 REST 上传复用同一入口）。
     *
     * @param yamlText 场景 YAML 文本
     * @return 校验通过的场景定义
     * @throws ScenarioValidationException 语法错误/校验失败（错误聚合）
     */
    public ScenarioDefinition parseText(String yamlText) {
        Object root;
        try {
            root = new Yaml().load(yamlText);
        } catch (YAMLException e) {
            throw new ScenarioValidationException("YAML 语法错误: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new ScenarioValidationException("场景文件顶层必须是映射（含 scenario/devices/events 段）");
        }

        List<String> errors = new ArrayList<>();

        // ---- scenario 段 ----
        Map<?, ?> scenarioMap = asMap(map.get("scenario"));
        String id = null;
        int version = 0;
        String description = null;
        ScenarioDefinition.ClockOverride clock = null;
        if (scenarioMap == null) {
            errors.add("缺少 scenario 段（id/version 必填）");
        } else {
            id = parseScenarioId(scenarioMap, errors);
            version = parseScenarioVersion(scenarioMap, errors);
            Object descRaw = scenarioMap.get("description");
            description = descRaw == null ? null : String.valueOf(descRaw);
            clock = parseClock(scenarioMap.get("clock"), errors);
        }

        // ---- devices 段 ----
        List<ScenarioDefinition.DeviceSpec> devices = parseDevices(map.get("devices"), errors);

        // ---- events 段 ----
        List<ScenarioDefinition.EventSpec> events = parseEvents(map.get("events"), errors);

        if (!errors.isEmpty()) {
            throw new ScenarioValidationException(errors);
        }
        return new ScenarioDefinition(id, version, description, clock, devices, events);
    }

    // ==================== scenario 段 ====================

    private static String parseScenarioId(Map<?, ?> scenarioMap, List<String> errors) {
        String id = asString(scenarioMap.get("id"));
        if (id == null || id.isBlank()) {
            errors.add("scenario.id 缺失（必填，[a-z0-9-]）");
            return null;
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            errors.add("scenario.id 只能包含小写字母/数字/连字符: " + id);
        }
        return id;
    }

    private static int parseScenarioVersion(Map<?, ?> scenarioMap, List<String> errors) {
        Object raw = scenarioMap.get("version");
        if (!(raw instanceof Number n)) {
            errors.add("scenario.version 缺失或非数字（当前 Schema v1，必须为 1）");
            return 0;
        }
        int version = n.intValue();
        if (version != 1) {
            errors.add("scenario.version 不支持: " + version + "（当前 Schema v1）");
        }
        return version;
    }

    private static ScenarioDefinition.ClockOverride parseClock(Object raw, List<String> errors) {
        if (raw == null) {
            return null; // 可选：缺省沿用全局 simulation.* 配置
        }
        Map<?, ?> clockMap = asMap(raw);
        if (clockMap == null) {
            errors.add("scenario.clock 必须是映射（mode/speed）");
            return null;
        }
        String mode = "realtime";
        Object modeRaw = clockMap.get("mode");
        if (modeRaw != null) {
            String m = String.valueOf(modeRaw).trim().toLowerCase();
            if (!m.equals("realtime") && !m.equals("accelerated")) {
                errors.add("scenario.clock.mode 只支持 realtime|accelerated: " + modeRaw);
            } else {
                mode = m;
            }
        }
        double speed = 1.0;
        Object speedRaw = clockMap.get("speed");
        if (speedRaw != null) {
            Double v = asNumber(speedRaw);
            if (v == null || !(v > 0) || !Double.isFinite(v)) {
                errors.add("scenario.clock.speed 必须为正有限值: " + speedRaw);
            } else {
                speed = v;
            }
        }
        return new ScenarioDefinition.ClockOverride(mode, speed);
    }

    // ==================== devices 段 ====================

    private static List<ScenarioDefinition.DeviceSpec> parseDevices(Object raw, List<String> errors) {
        List<ScenarioDefinition.DeviceSpec> devices = new ArrayList<>();
        if (raw == null) {
            errors.add("缺少 devices 段（至少 1 个设备）");
            return devices;
        }
        if (!(raw instanceof List<?> list)) {
            errors.add("devices 段必须是列表");
            return devices;
        }
        if (list.isEmpty()) {
            errors.add("devices 段不能为空（至少 1 个设备）");
            return devices;
        }
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String prefix = "devices[" + i + "]";
            Map<?, ?> dm = asMap(list.get(i));
            if (dm == null) {
                errors.add(prefix + " 必须是映射");
                continue;
            }
            String devId = asString(dm.get("id"));
            if (devId == null || devId.isBlank()) {
                errors.add(prefix + ".id 缺失（必填）");
            } else if (!seenIds.add(devId)) {
                errors.add(prefix + ".id 与场景内其他设备重复: " + devId);
            }
            DeviceVendor vendor = parseVendor(dm.get("vendor"), prefix, errors);
            String deviceMode = parseDeviceMode(dm.get("deviceMode"), prefix, errors);
            String dockModel = optionalString(dm.get("dockModel"));
            String droneModel = optionalString(dm.get("droneModel"));
            ScenarioDefinition.InitialState initial = parseInitial(dm.get("initial"), prefix, errors);
            devices.add(new ScenarioDefinition.DeviceSpec(devId, vendor, deviceMode,
                    dockModel, droneModel, initial));
        }
        return devices;
    }

    private static DeviceVendor parseVendor(Object raw, String prefix, List<String> errors) {
        if (raw == null) {
            errors.add(prefix + ".vendor 缺失（必填，可选值: " + Arrays.toString(DeviceVendor.values()) + "）");
            return null;
        }
        String name = String.valueOf(raw).trim().toUpperCase();
        for (DeviceVendor v : DeviceVendor.values()) {
            if (v.name().equals(name)) {
                return v;
            }
        }
        errors.add(prefix + ".vendor 非法: " + raw + "（可选值: " + Arrays.toString(DeviceVendor.values()) + "）");
        return null;
    }

    private static String parseDeviceMode(Object raw, String prefix, List<String> errors) {
        if (raw == null) {
            return null; // 可选：由 Adapter 取缺省（DJI 缺省 DOCK）
        }
        String mode = String.valueOf(raw).trim().toUpperCase();
        if (!DEVICE_MODES.contains(mode)) {
            errors.add(prefix + ".deviceMode 非法: " + raw + "（可选值: DOCK | PILOT）");
            return null;
        }
        return mode;
    }

    private static ScenarioDefinition.InitialState parseInitial(Object raw, String prefix, List<String> errors) {
        if (raw == null) {
            return null;
        }
        Map<?, ?> im = asMap(raw);
        if (im == null) {
            errors.add(prefix + ".initial 必须是映射（location/battery/online）");
            return null;
        }
        ScenarioDefinition.LocationSpec location = null;
        if (im.get("location") != null) {
            Map<?, ?> lm = asMap(im.get("location"));
            if (lm == null) {
                errors.add(prefix + ".initial.location 必须是映射（lng/lat/height）");
            } else {
                Double lng = asNumber(lm.get("lng"));
                Double lat = asNumber(lm.get("lat"));
                Double height = asNumber(lm.get("height"));
                boolean ok = true;
                if (lng == null || lng < -180 || lng > 180) {
                    errors.add(prefix + ".initial.location.lng 必须为 [-180, 180] 内数字: " + lm.get("lng"));
                    ok = false;
                }
                if (lat == null || lat < -90 || lat > 90) {
                    errors.add(prefix + ".initial.location.lat 必须为 [-90, 90] 内数字: " + lm.get("lat"));
                    ok = false;
                }
                if (height == null) {
                    errors.add(prefix + ".initial.location.height 必须为数字: " + lm.get("height"));
                    ok = false;
                }
                if (ok) {
                    location = new ScenarioDefinition.LocationSpec(lng, lat, height);
                }
            }
        }
        Integer battery = null;
        if (im.get("battery") != null) {
            Double b = asNumber(im.get("battery"));
            if (b == null || b < 0 || b > 100) {
                errors.add(prefix + ".initial.battery 必须为 [0, 100] 内数字: " + im.get("battery"));
            } else {
                battery = (int) Math.round(b);
            }
        }
        Boolean online = null;
        if (im.get("online") != null) {
            if (im.get("online") instanceof Boolean bool) {
                online = bool;
            } else {
                errors.add(prefix + ".initial.online 必须为布尔值: " + im.get("online"));
            }
        }
        return new ScenarioDefinition.InitialState(location, battery, online);
    }

    // ==================== events 段 ====================

    private static List<ScenarioDefinition.EventSpec> parseEvents(Object raw, List<String> errors) {
        List<ScenarioDefinition.EventSpec> events = new ArrayList<>();
        if (raw == null) {
            return events; // 可选：无事件场景合法（如纯初始状态场景）
        }
        if (!(raw instanceof List<?> list)) {
            errors.add("events 段必须是列表");
            return events;
        }
        for (int i = 0; i < list.size(); i++) {
            String prefix = "events[" + i + "]";
            Map<?, ?> em = asMap(list.get(i));
            if (em == null) {
                errors.add(prefix + " 必须是映射（at/action/参数）");
                continue;
            }
            Long atMillis = parseAt(em.get("at"), prefix, errors);
            String actionName = asString(em.get("action"));
            ScenarioAction action = null;
            if (actionName == null || actionName.isBlank()) {
                errors.add(prefix + ".action 缺失（必填）");
            } else {
                action = ScenarioAction.fromWireName(actionName);
                if (action == null) {
                    errors.add(prefix + ".action 未知: " + actionName
                            + "（支持的 action: " + ScenarioAction.supportedNames() + "）");
                }
            }
            // 其余键即 action 参数
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : em.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!key.equals("at") && !key.equals("action")) {
                    params.put(key, e.getValue());
                }
            }
            if (action != null) {
                for (String err : action.validateParams(params)) {
                    errors.add(prefix + "（" + action.wireName() + "）" + err);
                }
            }
            if (atMillis != null) {
                events.add(new ScenarioDefinition.EventSpec(atMillis, actionName, params));
            }
        }
        return events;
    }

    /** 解析 at 时间（严格：数字+单位 ms|s|m） */
    private static Long parseAt(Object raw, String prefix, List<String> errors) {
        if (raw == null) {
            errors.add(prefix + ".at 缺失（必填，格式如 60s / 1500ms / 2m）");
            return null;
        }
        String text = String.valueOf(raw).trim();
        Matcher m = AT_PATTERN.matcher(text);
        if (!m.matches()) {
            errors.add(prefix + ".at 格式非法: " + raw + "（要求 数字+单位，单位可选 ms|s|m，如 60s / 1500ms / 2m）");
            return null;
        }
        long value = Long.parseLong(m.group(1));
        long millis = switch (m.group(2)) {
            case "ms" -> value;
            case "s" -> value * 1000L;
            default -> value * 60_000L; // m
        };
        return millis;
    }

    // ==================== 通用工具 ====================

    private static Map<?, ?> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? m : null;
    }

    private static String asString(Object raw) {
        return raw instanceof String s ? s : null;
    }

    private static String optionalString(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static Double asNumber(Object raw) {
        return raw instanceof Number n ? n.doubleValue() : null;
    }
}
