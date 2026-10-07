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
 * 设备来源（真机演进预留，可行性方案 §8.3 缝隙 1）。
 * <p>W1 起统一模型携带本字段：W3 Manager 以它区分"托管实例"（可编排：启动/停止）
 * 与"外部设备"（只观测：健康/状态/告警聚合）。真机接入平台时无需其他架构改动。</p>
 */
public enum DeviceSource {

    /** 模拟器生成 */
    SIMULATED,

    /** 真实设备 */
    REAL,

    /** 硬件在环（HIL） */
    HIL
}
