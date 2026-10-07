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

import java.time.Duration;
import java.time.Instant;

/**
 * 仿真时钟实现（实施方案 §2.3.2）——REALTIME 与加速模式统一实现。
 *
 * <p>逻辑时间模型：</p>
 * <pre>
 * 逻辑时间 = anchor.logical + (System.nanoTime() - anchor.wallNanos) × anchor.speed
 *
 * setSpeed 时先结算旧倍率下已流逝的逻辑时间，再重锚（rebase）：
 *   anchor.logical += (wallNow - wallAnchor) × oldSpeed
 *   anchor.wallAnchor = wallNow; anchor.speed = newSpeed
 * </pre>
 *
 * <p>线程安全：{@code anchor} 为写时复制的不可变快照（volatile 引用），
 * {@link #now()} 无锁读取、一次读即获得一致的三元组；rebase 由 {@code synchronized} 串行化。
 * 物理计量使用 {@link System#nanoTime()}（单调，不受系统时间调整影响），
 * 在倍率恒正的前提下逻辑时间单调不降（毫秒取整允许相邻两次读数相等）。</p>
 *
 * <p>REALTIME 模式（speed 强制 1.0）下逻辑时间 ≈ 真实时间，行为与现有实现逐位等价（红线 2）。</p>
 */
public class SimulationClockImpl implements SimulationClock {

    /** 重锚快照：一次 volatile 读即获得一致的（logical, wallNanos, speed）三元组 */
    private record Anchor(Instant logical, long wallNanos, double speed) {
    }

    /** 墙钟启动时刻（wallMetrics 用） */
    private final Instant wallStartedAt;

    /** 墙钟启动纳秒（nanoTime；单调运行时长计量用） */
    private final long wallStartedNanos;

    /** 逻辑时间起点（构造时的真实时间；REALTIME 下 now() ≈ Instant.now()） */
    private final Instant logicalStartedAt;

    /** 当前锚（写时复制；volatile 保证跨线程可见性） */
    private volatile Anchor anchor;

    /**
     * 从配置构造（Spring @Bean 入口）。
     * <p>REALTIME 模式强制 speed=1.0（与 v1.4.6 行为等价，红线 2）；ACCELERATED 模式取配置倍率。</p>
     */
    public SimulationClockImpl(ClockProperties properties) {
        this(properties.clock().mode() == ClockProperties.ClockMode.REALTIME
                ? 1.0
                : properties.clock().speed());
    }

    /**
     * 倍率构造（测试与内嵌场景用）。
     *
     * @param initialSpeed 初始倍率，必须为正有限值
     */
    public SimulationClockImpl(double initialSpeed) {
        validateSpeed(initialSpeed);
        long wallNanos = System.nanoTime();
        Instant wallNow = Instant.now();
        this.wallStartedAt = wallNow;
        this.wallStartedNanos = wallNanos;
        this.logicalStartedAt = wallNow;
        this.anchor = new Anchor(wallNow, wallNanos, initialSpeed);
    }

    @Override
    public Instant now() {
        Anchor a = anchor;
        return a.logical().plusMillis(elapsedLogicalMillis(a, System.nanoTime()));
    }

    @Override
    public long elapsedMillis() {
        return Duration.between(logicalStartedAt, now()).toMillis();
    }

    @Override
    public double speed() {
        return anchor.speed();
    }

    @Override
    public void setSpeed(double newSpeed) {
        validateSpeed(newSpeed);
        synchronized (this) {
            Anchor current = anchor;
            if (current.speed() == newSpeed) {
                return;
            }
            long wallNanos = System.nanoTime();
            // 先结算旧倍率下已流逝的逻辑时间，再重锚：切换瞬间逻辑时间连续（不跳变、不回退）
            Instant rebasedLogical = current.logical().plusMillis(elapsedLogicalMillis(current, wallNanos));
            anchor = new Anchor(rebasedLogical, wallNanos, newSpeed);
        }
    }

    @Override
    public WallClockMetrics wallMetrics() {
        return new WallClockMetrics(wallStartedAt, Instant.now(),
                (System.nanoTime() - wallStartedNanos) / 1_000_000L);
    }

    /**
     * 锚到给定墙钟纳秒之间已流逝的逻辑毫秒数。
     * <p>先按倍率放大再取整：避免加速时"先整除丢亚毫秒、再乘倍率"造成的系统性偏差被放大。</p>
     */
    private static long elapsedLogicalMillis(Anchor anchor, long wallNanosNow) {
        long wallElapsedNanos = wallNanosNow - anchor.wallNanos();
        return (long) (wallElapsedNanos / 1_000_000.0 * anchor.speed());
    }

    private static void validateSpeed(double speed) {
        if (!(speed > 0) || !Double.isFinite(speed)) {
            throw new IllegalArgumentException("仿真时钟倍率必须为正有限值: " + speed);
        }
    }
}
