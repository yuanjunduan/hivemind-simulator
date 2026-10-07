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

/**
 * 统一设备类型（厂商无关）。
 * <p>V3.0 §4 统一设备模型的一部分：平台上层只认识统一类型，具体厂商型号由
 * {@link UnifiedDevice#getModel()} 表达（如 DJI 的 DOCK3 / M4TD）。</p>
 */
public enum DeviceType {

    /** 飞行器 */
    AIRCRAFT,

    /** 机场/机库/机巢 */
    DOCK,

    /** 遥控器（Pilot 端） */
    PILOT,

    /** 相机负载 */
    CAMERA,

    /** 云台 */
    GIMBAL,

    /** 其他负载（探照灯、喊话器等） */
    PAYLOAD
}
