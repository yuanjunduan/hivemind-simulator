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

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

import lombok.Getter;

/**
 * 飞行运行时上下文（实施方案 §2.6.2）——飞行引擎的可变状态载体。
 * <p>新对象（core 内），<b>不直接引用 DeviceState</b>：由 adapter/dji 在投影/回写时承担
 * 双向同步（§2.9）。承载：当前位置/高度/偏航、飞行阶段、意图队列（Goto 队列=航线）、
 * 速度参数与 INTEGRATOR 时间基准。</p>
 * <p>W1 由统一调度线程单线程驱动（每设备一个上下文实例），字段暂不做并发控制。</p>
 */
@Getter
public final class FlightRuntimeContext {

    /** 当前位置：纬度（WGS84 度） */
    private double latitude;

    /** 当前位置：经度（WGS84 度） */
    private double longitude;

    /** 当前椭球高（m） */
    private double elevation;

    /** 当前偏航角（度，正北 0，顺时针增大） */
    private double yawDeg;

    /** 返航点：纬度 */
    private double homeLatitude;

    /** 返航点：经度 */
    private double homeLongitude;

    /** 返航点：椭球高（altitude 换算基准） */
    private double homeElevation;

    /** 是否已解锁 */
    private boolean armed;

    /** 是否暂停（PauseMission 后 advance 不推进，保留目标与队列） */
    private boolean paused;

    /** 目标纬度（null=无目标） */
    private Double targetLatitude;

    /** 目标经度（null=无目标） */
    private Double targetLongitude;

    /** 目标椭球高（null=无目标） */
    private Double targetElevation;

    /** 水平巡航速度（m/s） */
    private double horizontalSpeedMps = 10.0;

    /** 垂直速度（m/s） */
    private double verticalSpeedMps = 3.0;

    /** 当前飞行阶段 */
    private FlightPhase phase = FlightPhase.HOVER;

    /** 意图队列（Goto 队列=航线；队首为下一个目标） */
    private final Deque<FlightIntent.Goto> queue = new ArrayDeque<>();

    /** INTEGRATOR 上次推进的逻辑时刻（首帧为 null，dt=0 仅建立基准） */
    private Instant lastAdvance;

    /** 相对起飞点高度（m）= 椭球高 − 返航点椭球高 */
    public double altitude() {
        return elevation - homeElevation;
    }

    /** 设置返航点（adapter 投影时调用） */
    public void setHome(double latitude, double longitude, double elevation) {
        this.homeLatitude = latitude;
        this.homeLongitude = longitude;
        this.homeElevation = elevation;
    }

    /** 设置当前位置（adapter 投影时调用；引擎 advance 内部也会更新） */
    public void setPosition(double latitude, double longitude, double elevation) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.elevation = elevation;
    }

    /** 设置目标（lat/lng 度；elevation 椭球高 m） */
    public void setTarget(double latitude, double longitude, double elevation) {
        this.targetLatitude = latitude;
        this.targetLongitude = longitude;
        this.targetElevation = elevation;
    }

    /** 清除目标（悬停/到达收尾） */
    public void clearTarget() {
        targetLatitude = null;
        targetLongitude = null;
        targetElevation = null;
    }

    /** 是否有目标 */
    public boolean hasTarget() {
        return targetLatitude != null;
    }

    /** 设置偏航角（度） */
    public void setYawDeg(double yawDeg) {
        this.yawDeg = yawDeg;
    }

    /** 设置解锁状态 */
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    /** 设置暂停状态 */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** 设置速度参数 */
    public void setSpeedParameters(double horizontalSpeedMps, double verticalSpeedMps) {
        this.horizontalSpeedMps = horizontalSpeedMps;
        this.verticalSpeedMps = verticalSpeedMps;
    }

    /** 设置飞行阶段（引擎内部推导写入） */
    public void setPhase(FlightPhase phase) {
        this.phase = phase;
    }

    /** 航点入队（航线=Goto 队列） */
    public void enqueue(FlightIntent.Goto gotoIntent) {
        queue.add(gotoIntent);
    }

    /** 队首出队（null=队列空） */
    public FlightIntent.Goto pollQueue() {
        return queue.poll();
    }

    /** 队列长度（不含当前目标） */
    public int queueSize() {
        return queue.size();
    }

    /** 记录 INTEGRATOR 推进时刻（引擎内部使用） */
    public void setLastAdvance(Instant lastAdvance) {
        this.lastAdvance = lastAdvance;
    }
}
