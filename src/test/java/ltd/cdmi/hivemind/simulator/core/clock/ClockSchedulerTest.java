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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 统一调度器单测（T1.2，TC-CORE-013~015）。
 * <p>用可控 {@link FakeClock} 手动驱动物理 tick（不启动后台线程、不睡眠），
 * 断言完全确定性——精确覆盖 §2.3.3 的两类任务语义与补发封顶上界。</p>
 */
class ClockSchedulerTest {

    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    private final FakeClock clock = new FakeClock(T0);
    private final ClockScheduler scheduler = new ClockScheduler(clock, new ClockProperties(null, null, null));

    @DisplayName("TC-CORE-013：FIXED_RATE 按逻辑周期触发；单 tick 到期多次补发封顶、超限跳帧重锚")
    @Test
    void tcCore013FixedRateCatchupCap() {
        AtomicInteger fired = new AtomicInteger();
        scheduler.register("osd", ClockScheduler.TaskSemantics.FIXED_RATE, 1000, fired::incrementAndGet);

        // 首帧语义：1 个逻辑周期后触发（与现有 scheduleAtFixedRate(task, p, p) 一致）
        clock.advanceMillis(999);
        scheduler.tick();
        assertEquals(0, fired.get(), "未到首帧周期不得触发");

        clock.advanceMillis(1);
        scheduler.tick();
        assertEquals(1, fired.get(), "到期 1 次触发 1 次");

        // 单 tick 到期 3 次（2s/3s/4s）→ 补发 3 次（未达封顶 5）
        clock.advanceMillis(3000);
        scheduler.tick();
        assertEquals(4, fired.get(), "单 tick 到期 3 次应全部补发");

        // 单 tick 到期 10 次（5s~14s）→ 补发封顶 5 次（4+5=9），其余跳帧丢弃
        clock.advanceMillis(10000);
        scheduler.tick();
        assertEquals(9, fired.get(), "补发必须封顶 5 次，超出部分跳帧丢弃");

        // 跳帧已重锚到当前时刻之后：同一逻辑时刻再次 tick 不再触发
        scheduler.tick();
        assertEquals(9, fired.get(), "跳帧后重锚：本时刻不得补触发");

        // 再过一个逻辑周期恢复触发（周期未被打乱）
        clock.advanceMillis(1000);
        scheduler.tick();
        assertEquals(10, fired.get(), "跳帧后按新锚继续周期触发");
    }

    @DisplayName("TC-CORE-014：INTEGRATOR 每个物理 tick 触发一次（任务自行从 clock 取 dt 积分）")
    @Test
    void tcCore014IntegratorPerTick() {
        AtomicInteger fired = new AtomicInteger();
        // INTEGRATOR 的 periodMillis 参数被忽略（恒为物理 tick 驱动）
        scheduler.register("interp", ClockScheduler.TaskSemantics.INTEGRATOR, 500, fired::incrementAndGet);

        // 逻辑时间不推进、物理 tick 3 次 → 触发 3 次
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();
        assertEquals(3, fired.get(), "INTEGRATOR 应每物理 tick 触发一次");

        // 逻辑时间大幅推进仍只触发一次（连续推进由任务内 dt 积分体现，非补发次数）
        clock.advanceMillis(10000);
        scheduler.tick();
        assertEquals(4, fired.get(), "INTEGRATOR 不得因逻辑时间跳变而补发");
    }

    @DisplayName("TC-CORE-015：scheduleOnce 延迟一次执行并自动注销；cancel 生效；同名注册替换旧任务")
    @Test
    void tcCore015OnceCancelReplace() {
        // 一次性任务：500ms 逻辑延迟后触发一次（对应返航延迟 5s 场景）
        AtomicInteger once = new AtomicInteger();
        scheduler.scheduleOnce("return-home", 500, once::incrementAndGet);
        assertEquals(1, scheduler.taskCount());

        clock.advanceMillis(400);
        scheduler.tick();
        assertEquals(0, once.get(), "延迟未到不得触发");

        clock.advanceMillis(100);
        scheduler.tick();
        assertEquals(1, once.get(), "延迟到点触发一次");

        clock.advanceMillis(60000);
        scheduler.tick();
        assertEquals(1, once.get(), "一次性任务执行后自动注销，不得再触发");
        assertEquals(0, scheduler.taskCount(), "一次性任务执行后应从任务表移除");

        // cancel：注销后不再触发
        AtomicInteger cancelled = new AtomicInteger();
        ClockScheduler.Cancellable handle = scheduler.register("temp",
                ClockScheduler.TaskSemantics.FIXED_RATE, 1000, cancelled::incrementAndGet);
        handle.cancel();
        clock.advanceMillis(10000);
        scheduler.tick();
        assertEquals(0, cancelled.get(), "已取消任务不得触发");
        assertEquals(0, scheduler.taskCount());

        // 同名注册替换：旧任务不再触发，新任务生效
        AtomicInteger oldTask = new AtomicInteger();
        AtomicInteger newTask = new AtomicInteger();
        scheduler.register("same-name", ClockScheduler.TaskSemantics.FIXED_RATE, 1000, oldTask::incrementAndGet);
        scheduler.register("same-name", ClockScheduler.TaskSemantics.FIXED_RATE, 1000, newTask::incrementAndGet);
        clock.advanceMillis(1000);
        scheduler.tick();
        assertEquals(0, oldTask.get(), "同名替换后旧任务不得触发");
        assertEquals(1, newTask.get(), "同名替换后新任务正常触发");
    }

    /** 可控假时钟：手动推进逻辑时间，使调度断言语义完全确定（调度器只消费 now()） */
    private static final class FakeClock implements SimulationClock {

        private final Instant startedAt;
        private Instant current;
        private double speed = 1.0;

        private FakeClock(Instant startedAt) {
            this.startedAt = startedAt;
            this.current = startedAt;
        }

        private void advanceMillis(long millis) {
            current = current.plusMillis(millis);
        }

        @Override
        public Instant now() {
            return current;
        }

        @Override
        public long elapsedMillis() {
            return Duration.between(startedAt, current).toMillis();
        }

        @Override
        public double speed() {
            return speed;
        }

        @Override
        public void setSpeed(double speed) {
            this.speed = speed;
        }

        @Override
        public WallClockMetrics wallMetrics() {
            return new WallClockMetrics(startedAt, current, Duration.between(startedAt, current).toMillis());
        }
    }
}
