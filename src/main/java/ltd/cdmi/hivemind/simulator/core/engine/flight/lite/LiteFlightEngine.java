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

package ltd.cdmi.hivemind.simulator.core.engine.flight.lite;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightIntent;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightPhase;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightProperties;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightRuntimeContext;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightSimulationEngine;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightSnapshot;
import ltd.cdmi.hivemind.simulator.core.model.Waypoint;

/**
 * 轻量飞行引擎（实施方案 §2.6.2/§2.6.3）——抽取现有三处算法的默认实现。
 * <p>算法来源与等价性保证：</p>
 * <ul>
 *   <li>{@link #stepTowards}：从 {@code WaylineTaskSimulator.advanceInterpolation} 与
 *       {@code FlightCommandSimulator.advanceFlightInterpolation} 搬来的<b>同一套公式</b>
 *       （匀速推进、垂直/水平分离、剩余不足一步精确到达），抽取后轨迹逐点一致；</li>
 *   <li>{@link #integrateSticks}：从 {@code DrcCommandHandler.integrateStick} 搬来的同一套公式
 *       （满杆线性映射、机头方向投影东北坐标系、偏航积分、dt=0 仅建基准）。</li>
 * </ul>
 * <p>速度常量全部经 {@link FlightProperties} 参数化，缺省值与现状一致（10.0/3.0/5.0/60/15/0.5/5.0）。
 * 现有组件（Wayline/DRC/FlightCommandSimulator）保留自身状态字段与调度，仅将算法体委托至本引擎
 * ——"替换算法归属、不改行为"（T1.6 最小侵入接线）。</p>
 * <p>同时实现有状态接口层（{@link #apply}/{@link #advance}/{@link #snapshot}），
 * 面向 W2 场景引擎：状态由 {@link FlightRuntimeContext} 承载。</p>
 */
@Component
public class LiteFlightEngine implements FlightSimulationEngine {

    /** 纬度每度对应米数（地球平均半径换算；与现有三处算法一致） */
    private static final double METERS_PER_DEGREE_LATITUDE = 111320.0;

    /** 到达判定容差（复刻 flyto 的 1e-9） */
    private static final double ARRIVE_EPSILON = 1e-9;

    private final FlightProperties props;

    /** Spring 注入构造器：速度参数来自 simulation.flight.*（缺失时回落现状缺省值） */
    @Autowired
    public LiteFlightEngine(FlightProperties props) {
        this.props = props;
    }

    /** 兼容构造器（测试与组件兼容构造器直接 new 用）：全缺省速度参数（与现状逐位一致） */
    public LiteFlightEngine() {
        this(new FlightProperties(null, null, null, null, null, null, null, null));
    }

    @Override
    public String name() {
        return "lite";
    }

    // ==================== 速度参数访问器（供现有组件参数化引用） ====================

    /** 航线插值水平速度（m/s，缺省 10.0） */
    public double horizontalSpeedMps() {
        return props.horizontalSpeedMps();
    }

    /** 航线插值垂直速度（m/s，缺省 3.0） */
    public double verticalSpeedMps() {
        return props.verticalSpeedMps();
    }

    /** flyto 指令速度缺省兜底（m/s，缺省 5.0） */
    public double fallbackSpeedMps() {
        return props.fallbackSpeedMps();
    }

    /** 杆量积分步长上限（秒，缺省 0.5，防断流恢复瞬移） */
    public double dtCapSeconds() {
        return props.dtCapSeconds();
    }

    // ==================== 纯算法层（现有组件委托；公式从现有代码搬移，逐位一致） ====================

    /**
     * 单步推进结果。
     *
     * @param latitude          新纬度（度）
     * @param longitude         新经度（度）
     * @param altitude          新高度（语义随调用方：Wayline=相对高度；flyto=椭球高）
     * @param horizontalArrived 水平已到达（剩余不足一步，精确落位）
     * @param verticalArrived   垂直已到达（1e-9 容差）
     */
    public record StepResult(double latitude, double longitude, double altitude,
                             boolean horizontalArrived, boolean verticalArrived) {

        /** 水平与垂直均已到达（flyto"到达即停"语义） */
        public boolean arrived() {
            return horizontalArrived && verticalArrived;
        }
    }

    /**
     * 向目标点匀速单步推进（复刻 Wayline 与 flyto 的同一套公式）。
     * <p>水平沿当前位置→目标点直线推进（speed×dt，剩余不足一步时精确到达、不越过不震荡）；
     * 垂直独立推进（verticalSpeed×dt）；经纬度按每度 111320 米换算（经度随纬度收缩）。</p>
     *
     * @param latitude          当前纬度（度）
     * @param longitude         当前经度（度）
     * @param altitude          当前高度（m）
     * @param targetLatitude    目标纬度（度）
     * @param targetLongitude   目标经度（度）
     * @param targetAltitude    目标高度（m）
     * @param horizontalSpeedMps 水平速度（m/s）
     * @param verticalSpeedMps  垂直速度（m/s）
     * @param dtSeconds         积分步长（秒；固定步长调用方传调度周期，如 0.5）
     * @return 单步推进结果
     */
    public StepResult stepTowards(double latitude, double longitude, double altitude,
                                  double targetLatitude, double targetLongitude, double targetAltitude,
                                  double horizontalSpeedMps, double verticalSpeedMps, double dtSeconds) {
        double stepMeters = horizontalSpeedMps * dtSeconds;
        double verticalStep = verticalSpeedMps * dtSeconds;

        // 当前位置 → 目标点的水平位移（米，东北坐标系）
        double dLatDeg = targetLatitude - latitude;
        double dLngDeg = targetLongitude - longitude;
        double metersPerDegreeLng = METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude));
        double dNorth = dLatDeg * METERS_PER_DEGREE_LATITUDE;
        double dEast = dLngDeg * metersPerDegreeLng;
        double horizontalDistance = Math.hypot(dNorth, dEast);

        double ratio;
        if (horizontalDistance <= stepMeters || horizontalDistance == 0) {
            ratio = 1.0;  // 剩余距离不足一个步长 → 精确到达（不越过、不震荡）
        } else {
            ratio = stepMeters / horizontalDistance;
        }

        // 高度独立推进
        double dh = targetAltitude - altitude;
        double newAltitude = altitude
                + (Math.abs(dh) <= verticalStep ? dh : Math.signum(dh) * verticalStep);

        double newLatitude = latitude + dLatDeg * ratio;
        double newLongitude = longitude + dLngDeg * ratio;

        boolean horizontalArrived = ratio >= 1.0;
        boolean verticalArrived = Math.abs(targetAltitude - newAltitude) < ARRIVE_EPSILON;
        return new StepResult(newLatitude, newLongitude, newAltitude, horizontalArrived, verticalArrived);
    }

    /**
     * 杆量积分结果。
     *
     * @param latitude      新纬度（度）
     * @param longitude     新经度（度）
     * @param deltaAltitude 高度增量（m，正=上升）
     * @param heading       积分后偏航角（度，[0,360)）
     * @param attitudePitch 姿态俯仰（度，前推杆机头下俯为负）
     * @param attitudeRoll  姿态横滚（度，右压杆右倾为正）
     */
    public record StickMove(double latitude, double longitude, double deltaAltitude,
                            double heading, double attitudePitch, double attitudeRoll) {
    }

    /**
     * 杆量积分（复刻 {@code DrcCommandHandler.integrateStick} 的同一套公式）。
     * <p>满杆归一化杆量线性映射速度；位移沿机头方向（heading）投影到东北坐标系；
     * 偏航按角速度积分（[0,360) 归一化）；俯仰/横滚为瞬时映射不积分。
     * dt≤0（首条消息建立基准）时位置不变，偏航/姿态仍更新——与现状一致。</p>
     *
     * @param dtSeconds  积分步长（秒；调用方已做上限封顶与时钟回退保护）
     * @param latitude   当前纬度（度）
     * @param longitude  当前经度（度）
     * @param headingDeg 当前偏航角（度）
     * @param nPitch     俯仰杆量（[-1,1]，正=前飞）
     * @param nRoll      横滚杆量（[-1,1]，正=右移）
     * @param nThrottle  油门杆量（[-1,1]，正=上升）
     * @param nYaw       偏航杆量（[-1,1]，正=顺时针）
     * @return 杆量积分结果
     */
    public StickMove integrateSticks(double dtSeconds,
                                     double latitude, double longitude, double headingDeg,
                                     double nPitch, double nRoll, double nThrottle, double nYaw) {
        // 偏航积分（度，[0, 360) 归一化）
        double heading = Math.floorMod(
                (long) (headingDeg + props.yawRateDegPerSec() * nYaw * dtSeconds), 360);

        // 姿态倾角（瞬时映射，不积分）
        double attitudePitch = -nPitch * props.attitudeMaxDeg();  // 前推杆机头下俯为负
        double attitudeRoll = nRoll * props.attitudeMaxDeg();     // 右压杆右倾为正

        if (dtSeconds <= 0) {
            return new StickMove(latitude, longitude, 0.0, heading, attitudePitch, attitudeRoll);
        }

        // 机体坐标系速度 → 东北坐标系位移（速度 × dt，heading 0=北，顺时针增大）
        double rad = Math.toRadians(heading);
        double vForward = props.maxHorizontalSpeedMps() * nPitch;
        double vRight = props.maxHorizontalSpeedMps() * nRoll;
        double east = (vForward * Math.sin(rad) + vRight * Math.cos(rad)) * dtSeconds;
        double north = (vForward * Math.cos(rad) - vRight * Math.sin(rad)) * dtSeconds;

        double newLatitude = latitude + north / METERS_PER_DEGREE_LATITUDE;
        // 经度每度米数随纬度收缩
        double metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(newLatitude));
        double newLongitude = longitude + east / metersPerDegreeLongitude;
        double deltaAltitude = props.maxVerticalSpeedMps() * nThrottle * dtSeconds;

        return new StickMove(newLatitude, newLongitude, deltaAltitude, heading, attitudePitch, attitudeRoll);
    }

    // ==================== 有状态接口层（面向 W2 场景引擎；状态由 ctx 承载） ====================

    @Override
    public void apply(FlightIntent intent, FlightRuntimeContext ctx) {
        switch (intent) {
            case FlightIntent.Takeoff takeoff -> {
                ctx.setTarget(ctx.getLatitude(), ctx.getLongitude(),
                        ctx.getHomeElevation() + takeoff.targetAltitude());
                ctx.setPhase(FlightPhase.CLIMB);
            }
            case FlightIntent.Goto gotoIntent -> applyGoto(ctx, gotoIntent);
            case FlightIntent.ExecuteWaypoints waypoints -> {
                for (Waypoint wp : waypoints.waypoints()) {
                    ctx.enqueue(toGoto(wp, waypoints.cruiseSpeed()));
                }
                if (!ctx.hasTarget()) {
                    applyNextFromQueue(ctx);
                }
            }
            case FlightIntent.ReturnHome returnHome -> {
                Integer rthAltitude = returnHome.rthAltitude();
                double targetElevation = (rthAltitude == null || rthAltitude <= 0)
                        ? ctx.getHomeElevation() : ctx.getHomeElevation() + rthAltitude;
                ctx.setTarget(ctx.getHomeLatitude(), ctx.getHomeLongitude(), targetElevation);
                ctx.setPhase(FlightPhase.CRUISE);
            }
            case FlightIntent.Land land -> {
                ctx.setTarget(ctx.getHomeLatitude(), ctx.getHomeLongitude(), ctx.getHomeElevation());
                ctx.setPhase(FlightPhase.DESCEND);
            }
            case FlightIntent.Hover hover -> {
                ctx.clearTarget();
                ctx.setPhase(FlightPhase.HOVER);
            }
            case FlightIntent.PauseMission pause -> ctx.setPaused(true);
            case FlightIntent.ResumeMission resume -> ctx.setPaused(false);
            case FlightIntent.SetSpeed setSpeed ->
                    ctx.setSpeedParameters(setSpeed.mps(), ctx.getVerticalSpeedMps());
            case FlightIntent.SetAltitude setAltitude -> ctx.setTarget(ctx.getLatitude(), ctx.getLongitude(),
                    ctx.getHomeElevation() + setAltitude.alt());
            case FlightIntent.Yaw yaw -> ctx.setYawDeg(yaw.headingDeg());
            case FlightIntent.Arm arm -> ctx.setArmed(true);
            case FlightIntent.Disarm disarm -> ctx.setArmed(false);
        }
    }

    @Override
    public void advance(Instant logicalNow, FlightRuntimeContext ctx) {
        double dt = 0.0;
        Instant last = ctx.getLastAdvance();
        if (last != null) {
            dt = (logicalNow.toEpochMilli() - last.toEpochMilli()) / 1000.0;
            if (dt < 0) {
                dt = 0.0;  // 时钟回退保护
            }
        }
        ctx.setLastAdvance(logicalNow);

        if (!ctx.hasTarget()) {
            ctx.setPhase(FlightPhase.HOVER);
            return;
        }
        if (ctx.isPaused() || dt <= 0) {
            return;
        }

        StepResult result = stepTowards(ctx.getLatitude(), ctx.getLongitude(), ctx.getElevation(),
                ctx.getTargetLatitude(), ctx.getTargetLongitude(), ctx.getTargetElevation(),
                ctx.getHorizontalSpeedMps(), ctx.getVerticalSpeedMps(), dt);
        ctx.setPosition(result.latitude(), result.longitude(), result.altitude());

        if (result.arrived()) {
            if (!applyNextFromQueue(ctx)) {
                ctx.clearTarget();
                ctx.setPhase(FlightPhase.HOVER);
            }
            return;
        }

        double dh = ctx.getTargetElevation() - result.altitude();
        if (Math.abs(dh) > ARRIVE_EPSILON) {
            ctx.setPhase(dh > 0 ? FlightPhase.CLIMB : FlightPhase.DESCEND);
        } else {
            ctx.setPhase(FlightPhase.CRUISE);
        }
    }

    @Override
    public FlightSnapshot snapshot(FlightRuntimeContext ctx) {
        return new FlightSnapshot(name(), ctx.getPhase(), ctx.getLatitude(), ctx.getLongitude(),
                ctx.getElevation(), ctx.altitude(), ctx.getYawDeg(),
                ctx.getTargetLatitude(), ctx.getTargetLongitude(), ctx.getTargetElevation());
    }

    // ==================== 内部辅助 ====================

    /** 应用 Goto 意图：设目标（alt 相对高度 → 椭球高）+ 覆盖速度参数（maxSpeed&gt;0 时） */
    private void applyGoto(FlightRuntimeContext ctx, FlightIntent.Goto gotoIntent) {
        ctx.setTarget(gotoIntent.lat(), gotoIntent.lng(), ctx.getHomeElevation() + gotoIntent.alt());
        if (gotoIntent.maxSpeed() > 0) {
            ctx.setSpeedParameters(gotoIntent.maxSpeed(), ctx.getVerticalSpeedMps());
        }
    }

    /** 队列出队一个目标并应用；返回 false=队列为空 */
    private boolean applyNextFromQueue(FlightRuntimeContext ctx) {
        FlightIntent.Goto next = ctx.pollQueue();
        if (next == null) {
            return false;
        }
        applyGoto(ctx, next);
        ctx.setPhase(FlightPhase.CRUISE);
        return true;
    }

    /** 航点转 Goto 意图（航点 speed&gt;0 覆盖巡航速度） */
    private static FlightIntent.Goto toGoto(Waypoint wp, double cruiseSpeed) {
        double speed = wp.getSpeed() > 0 ? wp.getSpeed() : cruiseSpeed;
        return new FlightIntent.Goto(wp.getLongitude(), wp.getLatitude(), wp.getAltitude(), speed);
    }
}
