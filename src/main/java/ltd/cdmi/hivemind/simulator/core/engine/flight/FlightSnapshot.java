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

package ltd.cdmi.hivemind.simulator.core.engine.flight;

/**
 * 飞行快照（引擎输出的只读视图，实施方案 §2.6.1）。
 * <p>位置为统一语义：经纬度 WGS84 度、{@code elevation} 椭球高（m）、
 * {@code altitude} 相对起飞点高度（m）。target 字段为 null 表示当前无目标（悬停）。</p>
 *
 * @param engine            引擎名（lite/px4/fastswarm）
 * @param phase             当前飞行阶段
 * @param latitude          纬度（度）
 * @param longitude         经度（度）
 * @param elevation         椭球高（m）
 * @param altitude          相对起飞点高度（m）
 * @param yawDeg            偏航角（度，正北 0，顺时针增大）
 * @param targetLatitude    目标纬度（null=无目标）
 * @param targetLongitude   目标经度（null=无目标）
 * @param targetElevation   目标椭球高（null=无目标）
 */
public record FlightSnapshot(
        String engine,
        FlightPhase phase,
        double latitude,
        double longitude,
        double elevation,
        double altitude,
        double yawDeg,
        Double targetLatitude,
        Double targetLongitude,
        Double targetElevation) {
}
