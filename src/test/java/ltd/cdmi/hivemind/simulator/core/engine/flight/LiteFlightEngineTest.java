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
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ltd.cdmi.hivemind.simulator.core.engine.flight.lite.LiteFlightEngine;
import ltd.cdmi.hivemind.simulator.core.model.Waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轻量飞行引擎单测（TC-CORE-040~047，实施方案 §2.6）。
 * <p>验证：抽取算法的数值与现有三处公式逐位一致（现有 TC-WAYLINE-024~026 / TC-FLY-033~035 /
 * TC-DRC-061~065 全绿即整体回归对比）；速度参数化生效；意图状态机（apply/advance/snapshot）
 * 语义正确（队列推进、暂停恢复、到达切点）。</p>
 */
class LiteFlightEngineTest {

    /** 纬度每度对应米数（与引擎/现有组件一致） */
    private static final double METERS_PER_DEG_LAT = 111320.0;

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** 默认参数引擎（缺省速度与现状一致：10.0/3.0/5.0/60/15/0.5/5.0） */
    private final LiteFlightEngine engine = new LiteFlightEngine();

    @DisplayName("TC-CORE-040：stepTowards 水平/垂直分离推进（公式与 Wayline/flyto 逐位一致）")
    @Test
    void stepTowardsMatchesLegacyFormula() {
        // 起点 (30,120,0)，目标 (30.001,120,50)：正北 111.32m，爬升 50m
        LiteFlightEngine.StepResult r =
                engine.stepTowards(30.0, 120.0, 0.0, 30.001, 120.0, 50.0, 10.0, 3.0, 0.5);

        // 水平：10m/s × 0.5s = 5m → ratio = 5/111.32
        assertEquals(30.0 + 0.001 * (5.0 / 111.32), r.latitude(), 1e-12);
        assertEquals(120.0, r.longitude(), 1e-12);
        // 垂直：3m/s × 0.5s = 1.5m（独立推进）
        assertEquals(1.5, r.altitude(), 1e-12);
        assertFalse(r.horizontalArrived());
        assertFalse(r.verticalArrived());
        assertFalse(r.arrived());
    }

    @DisplayName("TC-CORE-041：剩余不足一步 → 精确到达（不越过不震荡）")
    @Test
    void stepTowardsSnapsToTargetWhenWithinOneStep() {
        // 剩余水平 3m（< 5m）、高差 1.0m（< 1.5m）
        double targetLat = 30.0 + 3.0 / METERS_PER_DEG_LAT;
        LiteFlightEngine.StepResult r =
                engine.stepTowards(30.0, 120.0, 10.0, targetLat, 120.0, 11.0, 10.0, 3.0, 0.5);

        assertTrue(r.horizontalArrived());
        assertTrue(r.verticalArrived());
        assertTrue(r.arrived());
        assertEquals(targetLat, r.latitude(), 1e-15);
        assertEquals(11.0, r.altitude(), 1e-15);
    }

    @DisplayName("TC-CORE-042：水平到达但垂直未到 → 不判定 arrived（flyto 到达即停语义）")
    @Test
    void stepTowardsRequiresBothAxesForArrival() {
        double targetLat = 30.0 + 3.0 / METERS_PER_DEG_LAT;
        LiteFlightEngine.StepResult r =
                engine.stepTowards(30.0, 120.0, 10.0, targetLat, 120.0, 30.0, 10.0, 3.0, 0.5);

        assertTrue(r.horizontalArrived());
        assertFalse(r.verticalArrived());
        assertFalse(r.arrived());
        assertEquals(11.5, r.altitude(), 1e-15);  // 10 + 3×0.5
    }

    @DisplayName("TC-CORE-043：integrateSticks 满杆前飞（与 TC-DRC-061 同参：10m/s×0.5s=5m 北）")
    @Test
    void integrateSticksForwardMatchesLegacyFormula() {
        // heading=0（正北），满杆前飞，dt=0.5s（调用方已封顶）
        LiteFlightEngine.StickMove m =
                engine.integrateSticks(0.5, 30.0, 120.0, 0.0, 1.0, 0.0, 0.0, 0.0);

        assertEquals(30.0 + 5.0 / METERS_PER_DEG_LAT, m.latitude(), 1e-15);
        assertEquals(120.0, m.longitude(), 1e-15);
        assertEquals(0.0, m.deltaAltitude(), 1e-15);
        assertEquals(0.0, m.heading(), 1e-15);
        assertEquals(-15.0, m.attitudePitch(), 1e-15);  // 前推杆机头下俯为负
        assertEquals(0.0, m.attitudeRoll(), 1e-15);
    }

    @DisplayName("TC-CORE-044：偏航积分（floorMod 归一化）与 dt=0 仅建基准；姿态瞬时映射")
    @Test
    void integrateSticksYawIntegrationAndZeroDt() {
        // heading=90°，满杆前飞+满偏航+半油门 1 步（封顶 0.5s）：90 + 60×0.5 = 120°
        LiteFlightEngine.StickMove m =
                engine.integrateSticks(0.5, 30.0, 120.0, 90.0, 1.0, 0.0, 0.5, 1.0);
        assertEquals(120.0, m.heading(), 1e-15);
        assertEquals(1.25, m.deltaAltitude(), 1e-15);  // 5×0.5×0.5

        // 机头 120°：前飞位移投影到东北坐标系
        double rad = Math.toRadians(120.0);
        double east = (10.0 * Math.sin(rad)) * 0.5;
        double north = (10.0 * Math.cos(rad)) * 0.5;
        double expectedLat = 30.0 + north / METERS_PER_DEG_LAT;
        double expectedLng = 120.0 + east / (METERS_PER_DEG_LAT * Math.cos(Math.toRadians(expectedLat)));
        assertEquals(expectedLat, m.latitude(), 1e-15);
        assertEquals(expectedLng, m.longitude(), 1e-15);

        // dt=0（首条建立基准）：位置不变、偏航/姿态仍更新
        LiteFlightEngine.StickMove z =
                engine.integrateSticks(0.0, 30.0, 120.0, 90.0, 0.5, -0.5, 0.0, 0.0);
        assertEquals(30.0, z.latitude(), 0.0);
        assertEquals(120.0, z.longitude(), 0.0);
        assertEquals(0.0, z.deltaAltitude(), 0.0);
        assertEquals(90.0, z.heading(), 0.0);
        assertEquals(-7.5, z.attitudePitch(), 1e-15);
        assertEquals(-7.5, z.attitudeRoll(), 1e-15);
    }

    @DisplayName("TC-CORE-045：ExecuteWaypoints 队列推进 → 到达自动切点 → 全部完成回 HOVER")
    @Test
    void waypointQueueAdvancesAndCompletes() {
        FlightRuntimeContext ctx = new FlightRuntimeContext();
        ctx.setHome(30.0, 120.0, 100.0);
        ctx.setPosition(30.0, 120.0, 100.0);

        // 2 个航点：北向 10m 和 20m，相对高度 20m
        List<Waypoint> waypoints = List.of(
                Waypoint.builder().index(0).longitude(120.0)
                        .latitude(30.0 + 10.0 / METERS_PER_DEG_LAT).altitude(20.0).speed(0.0).build(),
                Waypoint.builder().index(1).longitude(120.0)
                        .latitude(30.0 + 20.0 / METERS_PER_DEG_LAT).altitude(20.0).speed(0.0).build());
        engine.apply(new FlightIntent.ExecuteWaypoints(waypoints, 10.0), ctx);

        // 队首航点立即成为当前目标（椭球高 = 返航点高度 + 相对高度）
        assertTrue(ctx.hasTarget());
        assertEquals(1, ctx.queueSize());
        assertEquals(30.0 + 10.0 / METERS_PER_DEG_LAT, ctx.getTargetLatitude(), 1e-15);
        assertEquals(120.0, ctx.getTargetElevation(), 1e-15);
        assertEquals(FlightPhase.CRUISE, ctx.getPhase());

        // 首帧仅建立时间基准（dt=0 不推进）
        engine.advance(T0, ctx);
        assertEquals(30.0, ctx.getLatitude(), 1e-15);
        assertEquals(100.0, ctx.getElevation(), 1e-15);

        // 按 1s 逻辑步推进直至完成（上限 100 步防死循环）
        Instant now = T0;
        for (int i = 0; i < 100 && ctx.hasTarget(); i++) {
            now = now.plusSeconds(1);
            engine.advance(now, ctx);
        }

        // 终态：全部完成、目标清空、回悬停、精确落位在最后一个航点
        assertFalse(ctx.hasTarget());
        assertEquals(0, ctx.queueSize());
        assertEquals(FlightPhase.HOVER, ctx.getPhase());
        assertEquals(30.0 + 20.0 / METERS_PER_DEG_LAT, ctx.getLatitude(), 1e-12);
        assertEquals(120.0, ctx.getLongitude(), 1e-12);
        assertEquals(120.0, ctx.getElevation(), 1e-9);
        assertEquals(20.0, ctx.altitude(), 1e-9);

        // 快照与上下文一致
        FlightSnapshot snapshot = engine.snapshot(ctx);
        assertEquals("lite", snapshot.engine());
        assertEquals(FlightPhase.HOVER, snapshot.phase());
        assertEquals(null, snapshot.targetLatitude());
    }

    @DisplayName("TC-CORE-046：意图生效（Takeoff/暂停/恢复/SetSpeed/Yaw/Arm）")
    @Test
    void intentsApplyToContext() {
        FlightRuntimeContext ctx = new FlightRuntimeContext();
        ctx.setHome(30.0, 120.0, 100.0);
        ctx.setPosition(30.0, 120.0, 100.0);

        // Takeoff：原地升到目标相对高度（100+50=150 椭球高）
        engine.apply(new FlightIntent.Takeoff(50.0), ctx);
        assertEquals(30.0, ctx.getTargetLatitude(), 1e-15);
        assertEquals(150.0, ctx.getTargetElevation(), 1e-15);
        assertEquals(FlightPhase.CLIMB, ctx.getPhase());

        // 暂停：advance 不推进（保留目标）
        engine.apply(new FlightIntent.PauseMission(), ctx);
        engine.advance(T0, ctx);
        engine.advance(T0.plusSeconds(1), ctx);
        assertTrue(ctx.isPaused());
        assertEquals(100.0, ctx.getElevation(), 1e-15);
        assertTrue(ctx.hasTarget());

        // 恢复：1s 推进 → 垂直 3m/s × 1s = 3m
        engine.apply(new FlightIntent.ResumeMission(), ctx);
        engine.advance(T0.plusSeconds(2), ctx);
        assertEquals(103.0, ctx.getElevation(), 1e-12);
        assertEquals(FlightPhase.CLIMB, ctx.getPhase());

        // SetSpeed/Yaw/Arm/Disarm 直接生效
        engine.apply(new FlightIntent.SetSpeed(20.0), ctx);
        assertEquals(20.0, ctx.getHorizontalSpeedMps(), 1e-15);
        engine.apply(new FlightIntent.Yaw(90.0), ctx);
        assertEquals(90.0, ctx.getYawDeg(), 1e-15);
        engine.apply(new FlightIntent.Arm(), ctx);
        assertTrue(ctx.isArmed());
        engine.apply(new FlightIntent.Disarm(), ctx);
        assertFalse(ctx.isArmed());

        // Hover：清目标回悬停
        engine.apply(new FlightIntent.Hover(), ctx);
        assertFalse(ctx.hasTarget());
        assertEquals(FlightPhase.HOVER, ctx.getPhase());
    }

    @DisplayName("TC-CORE-047：速度参数化生效（覆盖配置 → 步长变化；非法值回落缺省）")
    @Test
    void speedParametersTakeEffect() {
        // 水平 20m/s：步长 10m（0.5s）
        LiteFlightEngine custom = new LiteFlightEngine(
                new FlightProperties(20.0, null, null, null, null, null, null, null));
        LiteFlightEngine.StepResult r = custom.stepTowards(
                30.0, 120.0, 0.0, 30.001, 120.0, 0.0,
                custom.horizontalSpeedMps(), custom.verticalSpeedMps(), 0.5);
        assertEquals(30.0 + 0.001 * (10.0 / 111.32), r.latitude(), 1e-12);

        // DRC 参数：dt 上限 1.0s、偏航 30°/s、倾角 10°
        LiteFlightEngine custom2 = new LiteFlightEngine(
                new FlightProperties(null, null, null, null, 30.0, 10.0, 1.0, null));
        assertEquals(1.0, custom2.dtCapSeconds(), 1e-15);
        LiteFlightEngine.StickMove m =
                custom2.integrateSticks(1.0, 30.0, 120.0, 0.0, 1.0, 0.0, 0.0, 1.0);
        assertEquals(30.0, m.heading(), 1e-15);
        assertEquals(-10.0, m.attitudePitch(), 1e-15);

        // 非正值/缺失 → 回落现状缺省（10.0/3.0/5.0/60/15/0.5/5.0）
        LiteFlightEngine def = new LiteFlightEngine(
                new FlightProperties(null, 0.0, -1.0, null, null, null, null, null));
        assertEquals(10.0, def.horizontalSpeedMps(), 1e-15);
        assertEquals(3.0, def.verticalSpeedMps(), 1e-15);
        assertEquals(5.0, def.fallbackSpeedMps(), 1e-15);
        assertEquals(0.5, def.dtCapSeconds(), 1e-15);
    }
}
