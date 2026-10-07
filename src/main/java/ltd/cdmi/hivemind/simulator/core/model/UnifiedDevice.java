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

package ltd.cdmi.hivemind.simulator.core.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/**
 * 统一设备模型（快照不可变，对齐需求 §4）。
 * <p>投影约定：每次遥测投影生成新实例，避免并发共享可变状态（可行性方案 §2.2）。
 * 只放跨厂商语义字段；任何厂商特有枚举（如 DJI 的 mode_code=13）一律进 vendorExt，
 * 由上层按 vendor 解释，避免统一模型被单一厂商字段污染。</p>
 * <p>deviceId 约定：{@code vendor + ":" + deviceSn}，用 {@link #deviceIdOf} 构造。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class UnifiedDevice {

    /** 统一设备 ID（约定：vendor + ":" + deviceSn） */
    private final String deviceId;

    /** 设备序列号 */
    private final String deviceSn;

    /** 厂商 */
    private final DeviceVendor vendor;

    /** 型号（厂商内型号名，如 DOCK3 / M4TD / M30） */
    private final String model;

    /** 统一设备类型 */
    private final DeviceType deviceType;

    /** 设备来源（真机演进预留） */
    private final DeviceSource source;

    /** 生命周期状态（状态机推导） */
    private final LifecycleState lifecycleState;

    /** 在线状态（与生命周期互补的便捷位） */
    private final boolean online;

    /** 位置信息 */
    private final Position position;

    /** 姿态信息 */
    private final Attitude attitude;

    /** 电池信息 */
    private final BatteryInfo battery;

    /** GPS 定位信息 */
    private final GpsInfo gps;

    /** RTK 信息（可为空） */
    private final RtkInfo rtk;

    /** 统一飞行模式名（如 READY/TAKEOFF/WAYLINE/RTH/LANDING） */
    private final String flightMode;

    /** 当前任务 ID（null=无任务） */
    private final String missionId;

    /** 相机信息 */
    private final CameraInfo camera;

    /** 云台姿态 */
    private final GimbalInfo gimbal;

    /** 负载信息摘要 */
    private final PayloadInfo payload;

    /** 能力集 */
    private final Capabilities capabilities;

    /** 厂商扩展：原始枚举与增量字段（如 "dji.mode_code": 5, "dji.step": 24） */
    private final Map<String, Object> vendorExt;

    /**
     * 统一设备 ID 构造约定：{@code vendor + ":" + deviceSn}。
     * <p>所有投影/构造路径必须用本方法生成 deviceId，保证跨模块可关联。</p>
     */
    public static String deviceIdOf(DeviceVendor vendor, String deviceSn) {
        return vendor.name() + ":" + deviceSn;
    }
}
