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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import ltd.cdmi.hivemind.simulator.core.model.Waypoint;

/**
 * 统一飞行意图（实施方案 §2.6.1，sealed 契约）——引擎的标准输入，与厂商协议解耦。
 * <p>全部高度字段（除明确标注外）为<b>相对起飞点高度</b>（m）；经/纬度 WGS84 度。
 * 厂商特有编码（如 DJI 的 max_speed/坐标系约定）由 adapter/dji 在转换时解释（§2.9.2）。</p>
 *
 * <p>JSON 契约（REST {@code POST /api/unified/devices/{deviceId}/intents}，实施方案 §2.9.2）：
 * 以 {@code type} 属性区分意图类型（值为 record 简单名，如 {@code {"type":"Goto","lng":104.1,...}}）。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FlightIntent.Takeoff.class, name = "Takeoff"),
        @JsonSubTypes.Type(value = FlightIntent.Land.class, name = "Land"),
        @JsonSubTypes.Type(value = FlightIntent.Goto.class, name = "Goto"),
        @JsonSubTypes.Type(value = FlightIntent.Hover.class, name = "Hover"),
        @JsonSubTypes.Type(value = FlightIntent.PauseMission.class, name = "PauseMission"),
        @JsonSubTypes.Type(value = FlightIntent.ResumeMission.class, name = "ResumeMission"),
        @JsonSubTypes.Type(value = FlightIntent.ReturnHome.class, name = "ReturnHome"),
        @JsonSubTypes.Type(value = FlightIntent.SetSpeed.class, name = "SetSpeed"),
        @JsonSubTypes.Type(value = FlightIntent.SetAltitude.class, name = "SetAltitude"),
        @JsonSubTypes.Type(value = FlightIntent.Yaw.class, name = "Yaw"),
        @JsonSubTypes.Type(value = FlightIntent.Arm.class, name = "Arm"),
        @JsonSubTypes.Type(value = FlightIntent.Disarm.class, name = "Disarm"),
        @JsonSubTypes.Type(value = FlightIntent.ExecuteWaypoints.class, name = "ExecuteWaypoints"),
})
public sealed interface FlightIntent permits FlightIntent.Takeoff, FlightIntent.Land, FlightIntent.Goto,
        FlightIntent.Hover, FlightIntent.PauseMission, FlightIntent.ResumeMission, FlightIntent.ReturnHome,
        FlightIntent.SetSpeed, FlightIntent.SetAltitude, FlightIntent.Yaw, FlightIntent.Arm, FlightIntent.Disarm,
        FlightIntent.ExecuteWaypoints {

    /** 起飞到目标高度（相对起飞点，m） */
    record Takeoff(double targetAltitude) implements FlightIntent {
    }

    /** 降落（原地/返航点垂直下降，归舱等 Dock 语义由 adapter/dji 处理） */
    record Land() implements FlightIntent {
    }

    /** 飞向目标点（lng/lat 为 WGS84 度；alt 相对起飞点高度 m；maxSpeed≤0 沿用当前速度） */
    record Goto(double lng, double lat, double alt, double maxSpeed) implements FlightIntent {
    }

    /** 悬停（清除目标，保持当前位置） */
    record Hover() implements FlightIntent {
    }

    /** 暂停任务（保留目标与队列，advance 不再推进） */
    record PauseMission() implements FlightIntent {
    }

    /** 恢复任务 */
    record ResumeMission() implements FlightIntent {
    }

    /** 返航（目标=返航点；rthAltitude 相对高度 m，null/≤0 表示直接回返航点高度） */
    record ReturnHome(Integer rthAltitude) implements FlightIntent {
    }

    /** 设置水平巡航速度（m/s） */
    record SetSpeed(double mps) implements FlightIntent {
    }

    /** 设置目标高度（水平位置不变，alt 相对起飞点高度 m） */
    record SetAltitude(double alt) implements FlightIntent {
    }

    /** 设置偏航角（度，正北 0，顺时针增大） */
    record Yaw(double headingDeg) implements FlightIntent {
    }

    /** 解锁（电机怠速） */
    record Arm() implements FlightIntent {
    }

    /** 上锁 */
    record Disarm() implements FlightIntent {
    }

    /** 执行航点航线（按序入队为 Goto 队列；航点 speed&gt;0 时覆盖巡航速度） */
    record ExecuteWaypoints(List<Waypoint> waypoints, double cruiseSpeed) implements FlightIntent {
    }
}
