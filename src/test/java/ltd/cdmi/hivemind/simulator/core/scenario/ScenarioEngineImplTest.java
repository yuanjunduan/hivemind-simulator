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

package ltd.cdmi.hivemind.simulator.core.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ltd.cdmi.hivemind.simulator.core.clock.ClockProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.core.clock.SimulationClockImpl;

/**
 * 场景引擎单测（TC-SCN-011~016，T2.2 时间轴执行器 / T2.3 生命周期）。
 *
 * <p>使用真实 {@link SimulationClockImpl} + 手动 tick 的 {@link ClockScheduler}
 * （不启动后台线程）做确定性断言；sink 用记录型假实现验证触发时序与清理调用。</p>
 */
class ScenarioEngineImplTest {

    private SimulationClockImpl clock;
    private ClockScheduler scheduler;
    private RecordingSink sink;
    private ScenarioEngineImpl engine;

    @BeforeEach
    void setUp() {
        clock = new SimulationClockImpl(1.0);
        scheduler = new ClockScheduler(clock, new ClockProperties(null, null, null));
        sink = new RecordingSink();
        engine = new ScenarioEngineImpl(scheduler, sink);
    }

    @AfterEach
    void tearDown() {
        // 恢复 1x（避免用例间通过共享实例泄漏倍率；每个用例独立实例，此步仅为兜底）
        clock.setSpeed(1.0);
    }

    private static String scenarioYaml(String id, String... eventLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("scenario:\n  id: ").append(id).append("\n  version: 1\n");
        sb.append("devices:\n  - id: drone-001\n    vendor: DJI\n");
        sb.append("events:\n");
        for (String line : eventLines) {
            sb.append("  - ").append(line).append("\n");
        }
        return sb.toString();
    }

    /** 驱动逻辑时间前进：物理 sleep + tick（1x 下近似 1:1） */
    private void advance(long physicalMillis) throws InterruptedException {
        long deadline = System.nanoTime() + physicalMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Thread.sleep(10);
            scheduler.tick();
        }
    }

    @DisplayName("TC-SCN-011：时间轴按逻辑时间顺序触发（1x，未到期不触发，逐级放行）")
    @Test
    void timelineFiresInLogicalOrder() throws Exception {
        engine.loadText(scenarioYaml("order-test",
                "{ at: 200ms, action: return_home }",
                "{ at: 400ms, action: gps_loss }",
                "{ at: 600ms, action: gps_recover }"));
        engine.start("order-test");

        scheduler.tick();
        assertEquals(0, sink.actions.size(), "未到期不应触发");

        advance(300);
        assertEquals(List.of("return_home"), List.copyOf(sink.actions), "第一个事件应已触发");

        advance(200);
        assertEquals(List.of("return_home", "gps_loss"), List.copyOf(sink.actions));

        advance(200);
        assertEquals(List.of("return_home", "gps_loss", "gps_recover"), List.copyOf(sink.actions));
        assertEquals(ScenarioState.FINISHED, engine.status("order-test").state());
    }

    @DisplayName("TC-SCN-012：10x 加速下事件按逻辑时间触发（3s 逻辑 ≤900ms 物理内完成）")
    @Test
    void acceleratedFiresByLogicalTime() throws Exception {
        clock.setSpeed(10.0);
        engine.loadText(scenarioYaml("accel-test",
                "{ at: 1000ms, action: return_home }",
                "{ at: 2000ms, action: gps_loss }",
                "{ at: 3000ms, action: return_home }"));
        engine.start("accel-test");

        long start = System.nanoTime();
        long wallDeadline = start + 900_000_000L; // 物理上限 900ms（3s 逻辑 / 10x = 300ms + 余量）
        while (sink.actions.size() < 3 && System.nanoTime() < wallDeadline) {
            Thread.sleep(10);
            scheduler.tick();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(3, sink.actions.size(), "10x 下 3s 逻辑事件应全部触发");
        assertEquals(List.of("return_home", "gps_loss", "return_home"), List.copyOf(sink.actions));
        assertTrue(elapsedMillis >= 250, "10x 加速生效（3s 逻辑至少 ~300ms 物理）: " + elapsedMillis + "ms");
        assertTrue(elapsedMillis < 900, "触发总耗时应远小于实时: " + elapsedMillis + "ms");
        assertEquals(ScenarioState.FINISHED, engine.status("accel-test").state());
    }

    @DisplayName("TC-SCN-013：stop 取消未触发事件并调用清理（cleanup 恰一次）")
    @Test
    void stopCancelsPendingAndCleansUp() throws Exception {
        engine.loadText(scenarioYaml("stop-test",
                "{ at: 200ms, action: return_home }",
                "{ at: 400ms, action: gps_loss }",
                "{ at: 600ms, action: gps_recover }"));
        engine.start("stop-test");

        advance(300);
        assertEquals(1, sink.actions.size(), "停止前仅第一个事件触发");

        engine.stop("stop-test");
        assertEquals(ScenarioState.STOPPED, engine.status("stop-test").state());
        assertEquals(1, sink.cleanupCount, "stop 应触发一次清理");

        advance(500);
        assertEquals(1, sink.actions.size(), "停止后剩余事件不得再触发");
    }

    @DisplayName("TC-SCN-014：start/stop 幂等（重复 start 不重复登记，重复 stop 不重复清理）")
    @Test
    void startStopIdempotent() throws Exception {
        engine.loadText(scenarioYaml("idem-test",
                "{ at: 200ms, action: return_home }",
                "{ at: 300ms, action: gps_loss }"));
        engine.start("idem-test");
        engine.start("idem-test"); // 幂等：no-op

        advance(400);
        assertEquals(2, sink.actions.size(), "重复 start 不得导致事件重复触发");
        assertEquals(ScenarioState.FINISHED, engine.status("idem-test").state());

        engine.stop("idem-test"); // 已 FINISHED：置 STOPPED 并清理一次
        engine.stop("idem-test"); // 幂等：no-op
        assertEquals(1, sink.cleanupCount);
    }

    @DisplayName("TC-SCN-015：状态流转（LOADED→RUNNING→FINISHED；空事件即完成；未知 id 拒绝）")
    @Test
    void lifecycleStateTransitions() throws Exception {
        engine.loadText(scenarioYaml("state-test", "{ at: 150ms, action: return_home }"));
        ScenarioStatus loaded = engine.status("state-test");
        assertEquals(ScenarioState.LOADED, loaded.state());
        assertEquals(1, loaded.totalEvents());
        assertEquals(0, loaded.firedEvents());
        assertEquals(-1, loaded.startedAtElapsedMillis());
        assertTrue(engine.list().stream().anyMatch(s -> s.scenarioId().equals("state-test")));

        engine.start("state-test");
        assertEquals(ScenarioState.RUNNING, engine.status("state-test").state());
        assertTrue(engine.status("state-test").startedAtElapsedMillis() >= 0);

        advance(250);
        ScenarioStatus done = engine.status("state-test");
        assertEquals(ScenarioState.FINISHED, done.state());
        assertEquals(1, done.firedEvents());

        // 空事件场景：启动即完成
        engine.loadText(scenarioYaml("empty-test"));
        engine.start("empty-test");
        assertEquals(ScenarioState.FINISHED, engine.status("empty-test").state());

        // 未知 id：fail-fast
        assertThrows(IllegalArgumentException.class, () -> engine.start("no-such-scenario"));
        assertThrows(IllegalArgumentException.class, () -> engine.stop("no-such-scenario"));
        assertThrows(IllegalArgumentException.class, () -> engine.status("no-such-scenario"));
    }

    @DisplayName("TC-SCN-016：场景 clock 覆盖生效（accelerated 取倍率、realtime 归 1）")
    @Test
    void clockOverrideApplied() {
        String accelerated = "scenario:\n  id: accel-clock\n  version: 1\n"
                + "  clock:\n    mode: accelerated\n    speed: 20\n"
                + "devices:\n  - id: drone-001\n    vendor: DJI\nevents:\n"
                + "  - { at: 10s, action: return_home }\n";
        engine.loadText(accelerated);
        engine.start("accel-clock");
        assertEquals(20.0, clock.speed(), "accelerated 场景应把时钟切到场景倍率");

        String realtime = "scenario:\n  id: realtime-clock\n  version: 1\n"
                + "  clock:\n    mode: realtime\n"
                + "devices:\n  - id: drone-001\n    vendor: DJI\nevents:\n"
                + "  - { at: 10s, action: return_home }\n";
        engine.loadText(realtime);
        engine.start("realtime-clock");
        assertEquals(1.0, clock.speed(), "realtime 场景应把时钟归一到 1x");
    }

    /** 记录型假 sink：验证触发顺序 / 物理时刻 / 清理次数 */
    private static final class RecordingSink implements ScenarioActionSink {

        private final List<String> actions = new CopyOnWriteArrayList<>();
        private volatile int cleanupCount;

        @Override
        public void dispatch(ScenarioDefinition scenario, ScenarioDefinition.EventSpec event) {
            actions.add(event.action());
        }

        @Override
        public void cleanup(ScenarioDefinition scenario) {
            cleanupCount++;
        }
    }
}
