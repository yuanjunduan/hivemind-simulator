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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一调度器（实施方案 §2.3.3）——所有仿真心跳的唯一来源，逐步替换现有各独立
 * {@code ScheduledExecutorService}（T1.3 施工表）。
 *
 * <p>物理 tick 周期固定（{@code simulation.scheduler.tick-millis}，缺省 100ms，与倍率无关），
 * 每个 tick 先取一次逻辑时间快照，再按任务语义推进：</p>
 * <ul>
 *   <li>{@link TaskSemantics#FIXED_RATE}：离散事件，按<b>逻辑周期</b>触发；单物理 tick 内到期多个周期时
 *       按序补发，最多 {@code fixed-rate-catchup-max}（缺省 5）次，超出部分跳帧丢弃
 *       （下一个未触发的逻辑周期重锚到当前时刻之后，防止加速时追帧卡死）。
 *       对应现有 DeviceSimulator 2s OSD、Wayline 3s 进度、返航延迟等；</li>
 *   <li>{@link TaskSemantics#INTEGRATOR}：连续推进，每个物理 tick 只触发一次，任务内部按
 *       {@code dt = clock.now() - lastFire} 自行积分（对应位置插值、杆量积分）。</li>
 * </ul>
 *
 * <p>REALTIME 等价性：speed=1 时 FIXED_RATE 首帧语义与现有
 * {@code scheduleAtFixedRate(task, period, period)} 一致（注册后 1 个逻辑周期触发首帧）——
 * 这是 T1.3 "替换调度源、不重写任务体" 可实现逐位等价的结构保证。</p>
 *
 * <p>可测性：测试直接构造实例并调用{@link #tick()} 手动驱动（不启动后台线程），
 * 配合可控时钟实现即可做确定性断言。</p>
 */
public class ClockScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClockScheduler.class);

    /** 任务语义（§2.3.3 两类任务，加速正确性的核心区分） */
    public enum TaskSemantics {
        /** 离散事件：按逻辑周期触发，单 tick 到期多次时补发封顶、超限跳帧 */
        FIXED_RATE,
        /** 连续推进：每个物理 tick 触发一次，任务内部按 dt 自行积分 */
        INTEGRATOR
    }

    /** 任务注销句柄 */
    public interface Cancellable {
        /** 注销任务（幂等；已执行完的一次性任务等价于已注销） */
        void cancel();
    }

    private final SimulationClock clock;
    private final ClockProperties.Scheduler config;
    private final ScheduledExecutorService ticker;
    private final Map<String, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

    public ClockScheduler(SimulationClock clock, ClockProperties properties) {
        this.clock = clock;
        this.config = properties.scheduler();
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "clock-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * REALTIME 自持调度器工厂（组件兼容构造器用）：倍率 1.0、缺省 tick 配置，并立即启动后台 tick——
     * 与改造前各组件独立 {@code ScheduledExecutorService} 的真实时间推进行为等价（测试零修改）。
     */
    public static ClockScheduler realtime() {
        ClockScheduler scheduler = new ClockScheduler(new SimulationClockImpl(1.0),
                new ClockProperties(null, null, null));
        scheduler.start();
        return scheduler;
    }

    /**
     * 启动物理 tick 线程（幂等）。
     * <p>Spring 经 {@code @PostConstruct} 自动调用；组件兼容构造器经 {@link #realtime()} 手动启动，
     * 恢复与改造前独立调度器等价的真实时间推进（测试不调用时改手动 {@link #tick()}）。</p>
     */
    @PostConstruct
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ticker.scheduleAtFixedRate(this::tickQuietly,
                config.tickMillis(), config.tickMillis(), TimeUnit.MILLISECONDS);
        log.info("仿真时钟调度器已启动：tick={}ms，倍率={}x", config.tickMillis(), clock.speed());
    }

    /** Spring 关闭：停止后台 tick 线程 */
    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    /**
     * 注册周期任务。
     *
     * @param name         任务名（唯一；同名注册会替换并注销旧任务）
     * @param semantics    任务语义（FIXED_RATE=逻辑周期触发；INTEGRATOR=每物理 tick 触发）
     * @param periodMillis FIXED_RATE：逻辑周期毫秒数（必须 &gt;0）；INTEGRATOR：忽略（恒为物理 tick）
     * @param task         任务体（异常被捕获记录，不影响同 tick 内其他任务）
     * @return 注销句柄
     */
    public Cancellable register(String name, TaskSemantics semantics, long periodMillis, Runnable task) {
        ScheduledTask st;
        if (semantics == TaskSemantics.FIXED_RATE) {
            if (periodMillis <= 0) {
                throw new IllegalArgumentException("FIXED_RATE 任务逻辑周期必须 >0: " + name);
            }
            // 首帧语义与现有 scheduleAtFixedRate(task, period, period) 一致：1 个逻辑周期后触发
            st = new ScheduledTask(name, semantics, periodMillis, task, false,
                    clock.now().plusMillis(periodMillis));
        } else {
            st = new ScheduledTask(name, semantics, 0, task, false, null);
        }
        registerTask(st);
        return st;
    }

    /**
     * 注册一次性任务：延迟 {@code delayMillis}（逻辑时间）后触发一次并自动注销——
     * 对应现有 Wayline 返航延迟 5s 等 {@code scheduler.schedule(...)} 场景。
     */
    public Cancellable scheduleOnce(String name, long delayMillis, Runnable task) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("一次性任务延迟必须 >=0: " + name);
        }
        ScheduledTask st = new ScheduledTask(name, TaskSemantics.FIXED_RATE, 0, task, true,
                clock.now().plusMillis(delayMillis));
        registerTask(st);
        return st;
    }

    /** 当前注册任务数（运行观测与测试用） */
    public int taskCount() {
        return tasks.size();
    }

    /** 统一时钟访问器（被调度组件取逻辑时间用，如 OSD 报文 timestamp、插值 dt） */
    public SimulationClock clock() {
        return clock;
    }

    /**
     * 单次物理 tick：tick 内所有任务基于同一逻辑时间快照判定到期（帧内一致性）。
     * <p>生产路径由后台线程经 {@link #tickQuietly()} 驱动；测试（含跨包场景）可手动调用。</p>
     */
    public void tick() {
        Instant now = clock.now();
        for (ScheduledTask t : tasks.values()) {
            try {
                t.fireIfDue(now);
            } catch (Throwable ex) {
                log.error("调度任务执行异常：name={}", t.name, ex);
            }
        }
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (Throwable ex) {
            // 防 scheduleAtFixedRate 因单次异常取消整个 tick 循环
            log.error("物理 tick 异常", ex);
        }
    }

    private void registerTask(ScheduledTask newTask) {
        ScheduledTask old = tasks.put(newTask.name, newTask);
        if (old != null) {
            old.cancel();
            log.debug("同名调度任务已替换：name={}", newTask.name);
        }
    }

    /** 调度任务：nextFire 仅在 tick 线程读写；cancelled 标志跨线程可见 */
    private final class ScheduledTask implements Cancellable {

        private final String name;
        private final TaskSemantics semantics;
        private final long periodMillis;
        private final Runnable task;
        private final boolean oneShot;
        private volatile boolean cancelled;
        /** 下一个到期的逻辑时刻（FIXED_RATE / 一次性任务用；INTEGRATOR 为 null） */
        private Instant nextFire;

        private ScheduledTask(String name, TaskSemantics semantics, long periodMillis,
                              Runnable task, boolean oneShot, Instant nextFire) {
            this.name = name;
            this.semantics = semantics;
            this.periodMillis = periodMillis;
            this.task = task;
            this.oneShot = oneShot;
            this.nextFire = nextFire;
        }

        private void fireIfDue(Instant now) {
            if (cancelled) {
                return;
            }
            if (semantics == TaskSemantics.INTEGRATOR) {
                // 连续推进语义：每物理 tick 执行一次，任务自行从 clock 取 dt 积分
                task.run();
                return;
            }
            int catchupMax = config.fixedRateCatchupMax();
            int fired = 0;
            while (!cancelled && !now.isBefore(nextFire) && fired < catchupMax) {
                task.run();
                fired++;
                if (oneShot) {
                    cancel();
                    return;
                }
                nextFire = nextFire.plusMillis(periodMillis);
            }
            if (!cancelled && !now.isBefore(nextFire)) {
                // 到期次数超过补发上限：跳帧丢弃积压，下一个逻辑周期从当前时刻起算
                nextFire = now.plusMillis(periodMillis);
                log.debug("调度任务追帧达上限（{} 次），跳帧重锚至 {}：name={}", catchupMax, nextFire, name);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            tasks.remove(name, this);
        }
    }
}
