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

package ltd.cdmi.hivemind.simulator.core.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 仿真时钟实现单测（T1.2，TC-CORE-010~012）。
 * <p>验收标准（实施方案 §3.1）：now 单调、rebase 正确、speed 切换无缝。
 * 使用真实墙钟（短睡眠）验证倍率语义，断言留出宽松容差以适配 CI 调度抖动，
 * 但区间下界保证"加速"与"等价"两种行为可区分。</p>
 */
class SimulationClockTest {

    @DisplayName("TC-CORE-010：REALTIME 缺省兜底、speed 强制 1.0、now 与真实时间偏差 <1s")
    @Test
    void tcCore010RealtimeDefaults() {
        // 配置全缺省时的兜底（realtime/1.0、tick 100ms、补发 5、封顶 2Hz）
        ClockProperties defaults = new ClockProperties(null, null, null);
        assertEquals(ClockProperties.ClockMode.REALTIME, defaults.clock().mode());
        assertEquals(1.0, defaults.clock().speed());
        assertEquals(100, defaults.scheduler().tickMillis());
        assertEquals(5, defaults.scheduler().fixedRateCatchupMax());
        assertEquals(2, defaults.mqtt().publishRateCapHz());

        // REALTIME 下即使配置 speed=5 也强制 1.0（红线 2：与 v1.4.6 行为逐位等价）
        SimulationClockImpl clock = new SimulationClockImpl(new ClockProperties(
                new ClockProperties.Clock(ClockProperties.ClockMode.REALTIME, 5.0), null, null));
        assertEquals(1.0, clock.speed(), "REALTIME 必须强制 speed=1.0");
        assertTrue(Duration.between(Instant.now(), clock.now()).abs().toMillis() < 1000,
                "REALTIME 逻辑时间应与真实时间偏差 <1s");

        sleep(120);
        long elapsed = clock.elapsedMillis();
        assertTrue(elapsed >= 80 && elapsed <= 2000,
                "REALTIME 逻辑推进应 ≈ 物理时间，实测 " + elapsed + "ms");

        // 墙钟度量：启动时刻不变、运行时长单调不减
        SimulationClock.WallClockMetrics m1 = clock.wallMetrics();
        sleep(30);
        SimulationClock.WallClockMetrics m2 = clock.wallMetrics();
        assertEquals(m1.wallStartedAt(), m2.wallStartedAt());
        assertTrue(m2.wallUptimeMillis() >= m1.wallUptimeMillis(), "墙钟运行时长必须单调不减");
    }

    @DisplayName("TC-CORE-011：ACCELERATED 按倍率推进逻辑时间（10x：150ms 物理 ≈ 1.5s 逻辑）")
    @Test
    void tcCore011AcceleratedAdvance() {
        SimulationClockImpl clock = new SimulationClockImpl(new ClockProperties(
                new ClockProperties.Clock(ClockProperties.ClockMode.ACCELERATED, 10.0), null, null));
        assertEquals(10.0, clock.speed());

        sleep(150);
        long elapsed = clock.elapsedMillis();
        assertTrue(elapsed >= 800 && elapsed <= 8000,
                "10x 下 150ms 物理应推进 ≈1500ms 逻辑（须远离物理时间 150ms），实测 " + elapsed + "ms");
    }

    @DisplayName("TC-CORE-012：setSpeed 无缝重锚（不跳变不回退）、非法倍率拒绝、逻辑时间单调")
    @Test
    void tcCore012RebaseAndValidation() {
        SimulationClockImpl clock = new SimulationClockImpl(1.0);

        // 升档：先结算旧倍率已流逝逻辑时间再重锚，切换瞬间连续（容差 50ms）
        sleep(80);
        Instant before = clock.now();
        clock.setSpeed(20.0);
        long jumpUp = Duration.between(before, clock.now()).toMillis();
        assertTrue(jumpUp >= 0 && jumpUp <= 50, "升档瞬间逻辑时间不跳变，实测 " + jumpUp + "ms");

        // 20x 推进验证：120ms 物理 → ≈2400ms 逻辑
        long base = clock.elapsedMillis();
        sleep(120);
        long advanced = clock.elapsedMillis() - base;
        assertTrue(advanced >= 1200 && advanced <= 10000,
                "20x 下 120ms 物理应推进 ≈2400ms 逻辑，实测 " + advanced + "ms");

        // 降档：同样无缝（不因回锚跳变或回退）
        Instant beforeDown = clock.now();
        clock.setSpeed(1.0);
        long jumpDown = Duration.between(beforeDown, clock.now()).toMillis();
        assertTrue(jumpDown >= 0 && jumpDown <= 50, "降档瞬间逻辑时间不跳变，实测 " + jumpDown + "ms");

        // 非法倍率拒绝，且不改变现倍率
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(0));
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(-2.5));
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(Double.POSITIVE_INFINITY));
        assertEquals(1.0, clock.speed(), "非法 setSpeed 不得改变现倍率");

        // 单调性：反复切换 + 小步推进期间 now 不降
        Instant prev = clock.now();
        for (int i = 0; i < 5; i++) {
            clock.setSpeed(i % 2 == 0 ? 5.0 : 1.0);
            sleep(20);
            Instant cur = clock.now();
            assertTrue(!cur.isBefore(prev), "逻辑时间必须单调不降");
            prev = cur;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被中断", e);
        }
    }
}
