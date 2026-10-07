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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;

/**
 * 场景 YAML 解析与校验单测（TC-SCN-001~010，实施方案 §4.1 Schema v1 契约）。
 *
 * <p>覆盖：合法全字段解析（含 at 单位换算）、未知 action fail-fast（列出支持项）、
 * id/version/vendor/deviceMode/at/参数范围等各类校验错误聚合。</p>
 */
class ScenarioParserTest {

    private static final ScenarioParser PARSER = new ScenarioParser();

    /** 基础合法模板：测试只替换需要变化的段 */
    private static String yaml(String devicesBlock, String eventsBlock) {
        return "scenario:\n"
                + "  id: test-scenario\n"
                + "  version: 1\n"
                + "devices:\n"
                + devicesBlock
                + "events:\n"
                + eventsBlock;
    }

    private static String defaultDevices() {
        return "  - id: drone-001\n"
                + "    vendor: DJI\n";
    }

    private static List<String> errorsOf(String yamlText) {
        ScenarioValidationException ex = assertThrows(ScenarioValidationException.class,
                () -> PARSER.parseText(yamlText));
        assertTrue(ex.errors().size() >= 1, "应至少聚合一条错误");
        return ex.errors();
    }

    private static boolean anyContains(List<String> errors, String fragment) {
        return errors.stream().anyMatch(e -> e.contains(fragment));
    }

    @DisplayName("TC-SCN-001：合法全字段场景解析成功（clock 覆盖 + 双设备 + at 单位换算）")
    @Test
    void parsesFullValidScenario() {
        String text = "scenario:\n"
                + "  id: low-battery-return\n"
                + "  version: 1\n"
                + "  description: 低电量 20% 触发告警并返航\n"
                + "  clock:\n"
                + "    mode: accelerated\n"
                + "    speed: 10\n"
                + "devices:\n"
                + "  - id: dock-001\n"
                + "    vendor: DJI\n"
                + "    deviceMode: DOCK\n"
                + "    dockModel: DOCK3\n"
                + "    droneModel: M4TD\n"
                + "    initial:\n"
                + "      location: { lng: 104.123, lat: 30.123, height: 500 }\n"
                + "      battery: 80\n"
                + "      online: true\n"
                + "  - id: drone-001\n"
                + "    vendor: DJI\n"
                + "events:\n"
                + "  - { at: 60s, action: set_battery, value: 20 }\n"
                + "  - { at: 1500ms, action: trigger_alarm, type: LOW_BATTERY }\n"
                + "  - { at: 2m, action: return_home }\n";

        ScenarioDefinition def = PARSER.parseText(text);

        assertEquals("low-battery-return", def.id());
        assertEquals(1, def.version());
        assertEquals("低电量 20% 触发告警并返航", def.description());
        assertNotNull(def.clock());
        assertEquals("accelerated", def.clock().mode());
        assertEquals(10.0, def.clock().speed());
        assertEquals(2, def.devices().size());

        ScenarioDefinition.DeviceSpec dock = def.devices().get(0);
        assertEquals("dock-001", dock.id());
        assertEquals(DeviceVendor.DJI, dock.vendor());
        assertEquals("DOCK", dock.deviceMode());
        assertEquals("DOCK3", dock.dockModel());
        assertEquals("M4TD", dock.droneModel());
        assertNotNull(dock.initial());
        assertEquals(104.123, dock.initial().location().lng());
        assertEquals(30.123, dock.initial().location().lat());
        assertEquals(500.0, dock.initial().location().height());
        assertEquals(80, dock.initial().battery());
        assertEquals(Boolean.TRUE, dock.initial().online());

        // at 单位换算：60s=60000ms、1500ms=1500ms、2m=120000ms
        assertEquals(3, def.events().size());
        assertEquals(60_000L, def.events().get(0).atMillis());
        assertEquals("set_battery", def.events().get(0).action());
        assertEquals(20, ((Number) def.events().get(0).params().get("value")).intValue());
        assertEquals(1_500L, def.events().get(1).atMillis());
        assertEquals(120_000L, def.events().get(2).atMillis());
        assertEquals("return_home", def.events().get(2).action());
    }

    @DisplayName("TC-SCN-002：未知 action fail-fast 且列出全部支持项（不静默忽略）")
    @Test
    void unknownActionFailsFastWithSupportedList() {
        String text = yaml(defaultDevices(),
                "  - { at: 10s, action: fly_away_forever }\n");

        List<String> errors = errorsOf(text);
        assertTrue(anyContains(errors, "fly_away_forever"), "错误应包含未知 action 名");
        assertTrue(anyContains(errors, "支持的 action"), "错误应列出支持的 action");
        assertTrue(anyContains(errors, "set_battery") && anyContains(errors, "return_home"),
                "支持列表应包含契约表核心项");
    }

    @DisplayName("TC-SCN-003：scenario.id 命名约束（仅小写字母/数字/连字符）与缺失校验")
    @Test
    void invalidOrMissingIdRejected() {
        List<String> badFormat = errorsOf("scenario:\n  id: Low_Battery\n  version: 1\ndevices:\n"
                + defaultDevices() + "events:\n");
        assertTrue(anyContains(badFormat, "scenario.id 只能包含小写字母/数字/连字符"));

        List<String> missing = errorsOf("scenario:\n  version: 1\ndevices:\n"
                + defaultDevices() + "events:\n");
        assertTrue(anyContains(missing, "scenario.id 缺失"));
    }

    @DisplayName("TC-SCN-004：scenario.version 缺失或非 1 拒绝（Schema v1 冻结）")
    @Test
    void missingOrWrongVersionRejected() {
        List<String> missing = errorsOf("scenario:\n  id: ok-id\ndevices:\n"
                + defaultDevices() + "events:\n");
        assertTrue(anyContains(missing, "scenario.version 缺失"));

        List<String> wrong = errorsOf("scenario:\n  id: ok-id\n  version: 2\ndevices:\n"
                + defaultDevices() + "events:\n");
        assertTrue(anyContains(wrong, "scenario.version 不支持: 2"));
    }

    @DisplayName("TC-SCN-005：devices 段缺失/为空/设备 id 重复均拒绝")
    @Test
    void devicesSectionValidated() {
        List<String> missing = errorsOf("scenario:\n  id: ok-id\n  version: 1\nevents:\n");
        assertTrue(anyContains(missing, "缺少 devices 段"));

        List<String> empty = errorsOf("scenario:\n  id: ok-id\n  version: 1\ndevices: []\nevents:\n");
        assertTrue(anyContains(empty, "devices 段不能为空"));

        List<String> dup = errorsOf(yaml(
                "  - id: drone-001\n    vendor: DJI\n  - id: drone-001\n    vendor: DJI\n", ""));
        assertTrue(anyContains(dup, "重复"));
    }

    @DisplayName("TC-SCN-006：vendor 非法（含可用值提示）与 deviceMode 非法拒绝")
    @Test
    void vendorAndDeviceModeValidated() {
        List<String> badVendor = errorsOf(yaml(
                "  - id: drone-001\n    vendor: DJI_CLOUD\n", ""));
        assertTrue(anyContains(badVendor, "vendor 非法"));
        assertTrue(anyContains(badVendor, "DJI"), "错误应提示可选值枚举");

        List<String> badMode = errorsOf(yaml(
                "  - id: drone-001\n    vendor: DJI\n    deviceMode: CLOUD\n", ""));
        assertTrue(anyContains(badMode, "deviceMode 非法"));
    }

    @DisplayName("TC-SCN-007：at 时间必须为 数字+单位（无单位/非法字符/负数均拒绝）")
    @Test
    void atFormatStrictlyValidated() {
        List<String> unitless = errorsOf(yaml(defaultDevices(),
                "  - { at: 60, action: return_home }\n"));
        assertTrue(anyContains(unitless, "at 格式非法"));

        List<String> garbage = errorsOf(yaml(defaultDevices(),
                "  - { at: abc, action: return_home }\n"));
        assertTrue(anyContains(garbage, "at 格式非法"));

        List<String> negative = errorsOf(yaml(defaultDevices(),
                "  - { at: -5s, action: return_home }\n"));
        assertTrue(anyContains(negative, "at 格式非法"));
    }

    @DisplayName("TC-SCN-008：set_battery 参数校验（越界/缺失拒绝，0-100 内含边界）")
    @Test
    void setBatteryParamRangeValidated() {
        List<String> tooHigh = errorsOf(yaml(defaultDevices(),
                "  - { at: 10s, action: set_battery, value: 101 }\n"));
        assertTrue(anyContains(tooHigh, "value 超出范围"));

        List<String> negative = errorsOf(yaml(defaultDevices(),
                "  - { at: 10s, action: set_battery, value: -1 }\n"));
        assertTrue(anyContains(negative, "value 超出范围"));

        List<String> missing = errorsOf(yaml(defaultDevices(),
                "  - { at: 10s, action: set_battery }\n"));
        assertTrue(anyContains(missing, "缺少必填参数"));

        // 边界值 0/100 合法
        ScenarioDefinition def = PARSER.parseText(yaml(defaultDevices(),
                "  - { at: 10s, action: set_battery, value: 0 }\n"
                        + "  - { at: 10s, action: set_battery, value: 100 }\n"));
        assertEquals(2, def.events().size());
    }

    @DisplayName("TC-SCN-009：network_delay/network_drop 参数校验（必填与范围）")
    @Test
    void networkFaultParamsValidated() {
        List<String> missingMs = errorsOf(yaml(defaultDevices(),
                "  - { at: 10s, action: network_delay }\n"));
        assertTrue(anyContains(missingMs, "缺少必填参数（数字）: ms"));

        List<String> badPercent = errorsOf(yaml(defaultDevices(),
                "  - { at: 10s, action: network_drop, percent: 150 }\n"));
        assertTrue(anyContains(badPercent, "percent 超出范围"));

        // 合法：network_delay 带 jitter、network_drop 边界 0/100
        ScenarioDefinition def = PARSER.parseText(yaml(defaultDevices(),
                "  - { at: 10s, action: network_delay, ms: 500, jitterMs: 100 }\n"
                        + "  - { at: 20s, action: network_drop, percent: 0 }\n"
                        + "  - { at: 30s, action: network_drop, percent: 100 }\n"));
        assertEquals(3, def.events().size());
    }

    @DisplayName("TC-SCN-010：事件结构校验（缺 at/缺 action/非映射元素，多错误一次聚合）")
    @Test
    void eventStructureValidated() {
        List<String> structureErrors = errorsOf(yaml(defaultDevices(),
                "  - { action: return_home }\n"
                        + "  - { at: 10s }\n"
                        + "  - just-a-string\n"));
        assertTrue(anyContains(structureErrors, "at 缺失"));
        assertTrue(anyContains(structureErrors, "action 缺失"));
        assertTrue(anyContains(structureErrors, "必须是映射"));
        assertTrue(structureErrors.size() >= 3, "多错误应一次聚合返回");

        // 无 events 段合法（如纯初始状态场景）
        ScenarioDefinition def = PARSER.parseText("scenario:\n  id: idle-scene\n  version: 1\ndevices:\n"
                + defaultDevices());
        assertNull(def.clock());
        assertEquals(0, def.events().size());
    }
}
