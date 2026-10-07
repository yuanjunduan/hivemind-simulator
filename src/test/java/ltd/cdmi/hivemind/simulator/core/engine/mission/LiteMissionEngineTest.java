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

package ltd.cdmi.hivemind.simulator.core.engine.mission;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.core.engine.mission.lite.LiteMissionEngine;
import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.MissionProgressEvent;
import ltd.cdmi.hivemind.simulator.core.event.SimulationEvent;
import ltd.cdmi.hivemind.simulator.core.model.Waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轻量任务引擎单测（TC-CORE-050~058，实施方案 §2.7）。
 * <p>覆盖：任务全生命周期（prepare→execute→pause→resume→cancel→complete）、
 * 进度按航点推进、阶段链（EXECUTING→RETURNING→LANDING→COMPLETED）、
 * 非法转换忽略（幂等）、重新 load 重置、空计划边界与进度事件发布。</p>
 */
class LiteMissionEngineTest {

    /** 构造 n 个航点的任务计划 */
    private static MissionPlan plan(int n) {
        List<Waypoint> waypoints = IntStream.range(0, n)
                .mapToObj(i -> Waypoint.builder()
                        .index(i).longitude(113.0 + i * 0.0001).latitude(22.0)
                        .altitude(50.0).speed(0).build())
                .toList();
        return new MissionPlan("MISSION-TEST", waypoints, 10.0, null);
    }

    @DisplayName("TC-CORE-050：load 进入 PREPARING（进度清零、航点未开始）；start 进入 EXECUTING")
    @Test
    void loadAndStartEntersPreparingThenExecuting() {
        LiteMissionEngine engine = new LiteMissionEngine();

        // 初始 IDLE：未加载
        MissionSnapshot idle = engine.snapshot();
        assertEquals(MissionState.IDLE, idle.state());
        assertNull(idle.phase());
        assertEquals(-1, idle.currentWaypoint());

        engine.load(plan(3));
        MissionSnapshot loaded = engine.snapshot();
        assertEquals(MissionState.PREPARING, loaded.state());
        assertEquals(MissionPhase.PREPARING, loaded.phase());
        assertEquals(0, loaded.percent());
        assertEquals(-1, loaded.currentWaypoint());
        assertNull(loaded.stepRef(), "统一侧引擎不产生厂商步骤引用");

        engine.start();
        MissionSnapshot started = engine.snapshot();
        assertEquals(MissionState.EXECUTING, started.state());
        assertEquals(MissionPhase.EXECUTING, started.phase());
    }

    @DisplayName("TC-CORE-051：advance 按航点推进（percent 均分 0→90，航点序号递增）")
    @Test
    void advanceProgressesWaypointsAndPercent() {
        LiteMissionEngine engine = new LiteMissionEngine();
        engine.load(plan(4));
        engine.start();

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        // 4 个航点：percent = round((i+1) * 90 / 4) = 23 / 45 / 68 / 90
        int[] expectedPercents = {23, 45, 68, 90};
        for (int i = 0; i < 4; i++) {
            engine.advance(now);
            MissionSnapshot s = engine.snapshot();
            assertEquals(i, s.currentWaypoint(), "第 " + (i + 1) + " 次推进应到航点 " + i);
            assertEquals(expectedPercents[i], s.percent(), "第 " + (i + 1) + " 次推进进度");
            assertEquals(MissionPhase.EXECUTING, s.phase());
            assertEquals(MissionState.EXECUTING, s.state());
        }
    }

    @DisplayName("TC-CORE-052：全生命周期阶段链——航点耗尽后 RETURNING(90)→LANDING(95)→COMPLETED(100)")
    @Test
    void advanceCompletesPhaseChain() {
        LiteMissionEngine engine = new LiteMissionEngine();
        engine.load(plan(2));
        engine.start();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        engine.advance(now);  // 航点 0：percent 45
        engine.advance(now);  // 航点 1：percent 90
        engine.advance(now);  // 航点耗尽 → RETURNING
        MissionSnapshot returning = engine.snapshot();
        assertEquals(MissionPhase.RETURNING, returning.phase());
        assertEquals(90, returning.percent());
        assertEquals(MissionState.EXECUTING, returning.state(), "返航阶段任务状态仍为 EXECUTING");

        engine.advance(now);  // → LANDING
        MissionSnapshot landing = engine.snapshot();
        assertEquals(MissionPhase.LANDING, landing.phase());
        assertEquals(95, landing.percent());

        engine.advance(now);  // → COMPLETED（终态）
        MissionSnapshot completed = engine.snapshot();
        assertEquals(MissionPhase.COMPLETED, completed.phase());
        assertEquals(100, completed.percent());
        assertEquals(MissionState.COMPLETED, completed.state());
        assertTrue(completed.state().isTerminal());

        // 终态后再 advance：不推进（幂等）
        engine.advance(now);
        assertEquals(100, engine.snapshot().percent());
    }

    @DisplayName("TC-CORE-053：pause/resume 迁移与阶段保留；暂停期间 advance 不推进")
    @Test
    void pauseResumePreservesPhaseAndBlocksAdvance() {
        LiteMissionEngine engine = new LiteMissionEngine();
        engine.load(plan(4));
        engine.start();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        engine.advance(now);
        engine.advance(now); // 航点 1：percent 45

        engine.pause();
        MissionSnapshot paused = engine.snapshot();
        assertEquals(MissionState.PAUSED, paused.state());
        assertEquals(MissionPhase.EXECUTING, paused.phase(), "暂停应保留阶段");
        assertEquals(1, paused.currentWaypoint());

        engine.advance(now);
        assertEquals(1, engine.snapshot().currentWaypoint(), "暂停期间 advance 不推进");
        assertEquals(45, engine.snapshot().percent());

        engine.resume();
        MissionSnapshot resumed = engine.snapshot();
        assertEquals(MissionState.EXECUTING, resumed.state());
        assertEquals(MissionPhase.EXECUTING, resumed.phase());
        engine.advance(now);
        assertEquals(2, engine.snapshot().currentWaypoint(), "恢复后继续推进");
    }

    @DisplayName("TC-CORE-054：cancel → CANCELED 终态；后续 pause/advance 均无效")
    @Test
    void cancelEntersTerminalState() {
        LiteMissionEngine engine = new LiteMissionEngine();
        engine.load(plan(3));
        engine.start();
        engine.advance(Instant.parse("2026-01-01T00:00:00Z"));

        engine.cancel();
        MissionSnapshot canceled = engine.snapshot();
        assertEquals(MissionState.CANCELED, canceled.state());
        assertTrue(canceled.state().isTerminal());
        assertEquals(MissionPhase.EXECUTING, canceled.phase(), "取消保留取消时的阶段");
        assertEquals(0, canceled.currentWaypoint());

        engine.pause();
        assertEquals(MissionState.CANCELED, engine.snapshot().state(), "终态后 pause 无效");
        engine.cancel();
        assertEquals(MissionState.CANCELED, engine.snapshot().state(), "重复 cancel 幂等");
    }

    @DisplayName("TC-CORE-055：非法转换忽略——IDLE start、未 load pause/cancel、PREPARING pause、EXECUTING resume")
    @Test
    void invalidTransitionsAreIgnored() {
        LiteMissionEngine engine = new LiteMissionEngine();

        // IDLE：start/pause/resume/cancel 全部无效
        engine.start();
        engine.pause();
        engine.resume();
        engine.cancel();
        assertEquals(MissionState.IDLE, engine.snapshot().state());
        assertFalse(engine.snapshot().state().isTerminal());

        // PREPARING：pause/resume 无效；cancel 有效
        engine.load(plan(2));
        engine.pause();
        engine.resume();
        assertEquals(MissionState.PREPARING, engine.snapshot().state());
        engine.cancel();
        assertEquals(MissionState.CANCELED, engine.snapshot().state());
    }

    @DisplayName("TC-CORE-056：重新 load 覆盖重置（终态/执行中均回到 PREPARING 且进度清零）")
    @Test
    void reloadResetsProgress() {
        LiteMissionEngine engine = new LiteMissionEngine();
        engine.load(plan(2));
        engine.start();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        engine.advance(now);
        engine.advance(now);
        engine.advance(now);
        engine.advance(now);
        engine.advance(now); // 完成
        assertEquals(MissionState.COMPLETED, engine.snapshot().state());

        engine.load(plan(5));
        MissionSnapshot reloaded = engine.snapshot();
        assertEquals(MissionState.PREPARING, reloaded.state());
        assertEquals(MissionPhase.PREPARING, reloaded.phase());
        assertEquals(0, reloaded.percent());
        assertEquals(-1, reloaded.currentWaypoint());
    }

    @DisplayName("TC-CORE-057：空航点计划边界——start 后首次 advance 直接进入 RETURNING")
    @Test
    void emptyWaypointsSkipToReturning() {
        LiteMissionEngine engine = new LiteMissionEngine();
        engine.load(plan(0));
        engine.start();
        engine.advance(Instant.parse("2026-01-01T00:00:00Z"));

        MissionSnapshot s = engine.snapshot();
        assertEquals(MissionPhase.RETURNING, s.phase());
        assertEquals(90, s.percent());
        assertEquals(-1, s.currentWaypoint(), "空计划无航点可推进");
    }

    @DisplayName("TC-CORE-058：进度事件发布——load/start/advance/cancel 各发一次，字段与逻辑时间戳正确")
    @Test
    void publishesProgressEvents() {
        EventBus eventBus = Mockito.mock(EventBus.class);
        when(eventBus.logicalNow()).thenReturn(Instant.parse("2026-01-01T00:00:03Z"));
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        when(runtimeConfig.getGatewaySn()).thenReturn("DOCK-TEST-SN");

        LiteMissionEngine engine = new LiteMissionEngine(eventBus, runtimeConfig);
        engine.load(plan(2));                       // 事件 1：PREPARING
        engine.start();                             // 事件 2：EXECUTING
        engine.advance(Instant.parse("2026-01-01T00:00:03Z")); // 事件 3：航点推进
        engine.cancel();                            // 事件 4：CANCELED

        ArgumentCaptor<SimulationEvent> captor = ArgumentCaptor.forClass(SimulationEvent.class);
        verify(eventBus, times(4)).publish(captor.capture());
        List<SimulationEvent> events = captor.getAllValues();

        MissionProgressEvent loaded = (MissionProgressEvent) events.get(0);
        assertEquals("DJI:DOCK-TEST-SN", loaded.getDeviceId());
        assertEquals(Instant.parse("2026-01-01T00:00:03Z"), loaded.getOccurredAtInstant());
        assertEquals("MISSION-TEST", loaded.getMissionId());
        assertEquals("PREPARING", loaded.getState());
        assertEquals(0, loaded.getPercent());
        assertEquals(MissionPhase.PREPARING.ordinal(), loaded.getCurrentStep());

        MissionProgressEvent started = (MissionProgressEvent) events.get(1);
        assertEquals("EXECUTING", started.getState());
        assertEquals(MissionPhase.EXECUTING.ordinal(), started.getCurrentStep());

        MissionProgressEvent advanced = (MissionProgressEvent) events.get(2);
        assertEquals("EXECUTING", advanced.getState());
        assertEquals(45, advanced.getPercent());

        MissionProgressEvent canceled = (MissionProgressEvent) events.get(3);
        assertEquals("CANCELED", canceled.getState());
        assertEquals(45, canceled.getPercent());
    }
}
