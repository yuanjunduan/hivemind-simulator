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
 * 设备厂商（统一模型的 vendor 维度）。
 * <p>对齐需求 §29：厂商差异由 Adapter 解决，核心层只感知厂商标识。
 * INTERNAL 用于平台内部实体（非物理设备，如管理面自身）。</p>
 */
public enum DeviceVendor {

    /** 大疆 */
    DJI,

    /** 司空（第一阶段 Mock，声明 ≠ 官方协议兼容） */
    SIKONG,

    /** 纵横（第一阶段 Mock，声明 ≠ 官方协议兼容） */
    JOUAV,

    /** PX4（SITL，后置波次） */
    PX4,

    /** 平台内部 */
    INTERNAL
}
