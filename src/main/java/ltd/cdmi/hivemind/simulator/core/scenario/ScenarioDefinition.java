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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;

/**
 * 场景定义（YAML Schema v1，实施方案 §4.1 冻结契约）。
 *
 * <p>字段语义与冻结范围：</p>
 * <ul>
 *   <li>{@code scenario} 段：id/version/description/clock（clock 可选，覆盖全局 {@code simulation.*} 配置）；</li>
 *   <li>{@code devices} 段：场景内设备定义；{@code deviceMode/dockModel/droneModel} 为厂商参数，
 *       核心层原样保留、由各 Adapter 解释（SIKONG/JOUAV Mock 可忽略）；</li>
 *   <li>{@code events} 段：时间轴事件（{@code at} 为场景启动后的逻辑毫秒偏移）。</li>
 * </ul>
 *
 * <p>不可变值对象：解析器产出后全生命周期只读；执行器（T2.2）按 {@code atMillis} 排序登记。</p>
 */
public record ScenarioDefinition(
        String id,
        int version,
        String description,
        ClockOverride clock,
        List<DeviceSpec> devices,
        List<EventSpec> events) {

    /** 构造时防御性拷贝：保证集合不可变（Schema 冻结后禁止运行期修改） */
    public ScenarioDefinition {
        devices = List.copyOf(devices);
        events = List.copyOf(events);
    }

    /**
     * 场景内时钟覆盖（可选）。
     *
     * @param mode  realtime | accelerated（{@code ClockProperties.ClockMode} 的线名，大小写不敏感）
     * @param speed 加速倍率（>0 有限值；realtime 时被忽略）
     */
    public record ClockOverride(String mode, double speed) {
    }

    /**
     * 场景内设备定义。
     *
     * @param id          场景内设备 ID（非空、场景内唯一；执行器据此解析目标设备）
     * @param vendor      设备厂商（统一模型 DeviceVendor）
     * @param deviceMode  厂商内接入模式（DJI：DOCK | PILOT；其余厂商由 Adapter 解释，可空）
     * @param dockModel   厂商型号参数（可选，如 DOCK3）
     * @param droneModel  厂商型号参数（可选，如 M4TD）
     * @param initial     初始状态（可选；null = 沿用模拟器当前值）
     */
    public record DeviceSpec(String id, DeviceVendor vendor, String deviceMode,
                             String dockModel, String droneModel, InitialState initial) {
    }

    /**
     * 设备初始状态（所有字段可选，null = 沿用模拟器当前/默认值）。
     *
     * @param location 初始位置（机场位置语义）
     * @param battery  初始电量（0-100）
     * @param online   场景启动时是否自动上线
     */
    public record InitialState(LocationSpec location, Integer battery, Boolean online) {
    }

    /**
     * 初始位置。
     *
     * @param lng    经度（WGS84 度）
     * @param lat    纬度（WGS84 度）
     * @param height 海拔（米）
     */
    public record LocationSpec(double lng, double lat, double height) {
    }

    /**
     * 时间轴事件。
     *
     * @param atMillis 场景启动后的逻辑毫秒偏移（YAML 写法如 {@code 60s} / {@code 1500ms} / {@code 2m}；
     *                 加速倍率下按逻辑时间触发——10x 时 60s 逻辑 ≈ 6s 物理）
     * @param action   action 契约名（冻结集合见 {@link ScenarioAction}）
     * @param params   原始参数（解析时已校验；执行器按 action 语义读取）
     */
    public record EventSpec(long atMillis, String action, Map<String, Object> params) {

        /** 构造时防御性拷贝：保证参数映射不可变（允许 null 值，YAML 字段可能显式为 null） */
        public EventSpec {
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }
}
