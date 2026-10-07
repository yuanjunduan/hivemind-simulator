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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;

/**
 * 场景引擎实现（T2.2 时间轴执行器 + T2.3 生命周期）。
 *
 * <p>时间轴登记：start 时对每个事件调用 {@link ClockScheduler#scheduleOnce}
 * （delayMillis = {@code atMillis}，相对启动时刻的逻辑时间）——加速倍率下由
 * ClockScheduler 按逻辑时间触发（10x 时 60s 逻辑 ≈ 6s 物理），事件体不感知倍率。</p>
 *
 * <p>结束判定：已触发数达到事件总数时置 FINISHED（空事件场景 start 即 FINISHED）。
 * stop 取消全部未触发登记并调用 {@link ScenarioActionSink#cleanup}（注入清理）。</p>
 */
@Component
public class ScenarioEngineImpl implements ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEngineImpl.class);

    private final ScenarioParser parser = new ScenarioParser();
    private final ClockScheduler clockScheduler;
    private final ScenarioActionSink actionSink;

    /** 已加载场景（按 id；LinkedHashMap 语义由 List 顺序维护，保证 list() 输出稳定） */
    private final ConcurrentHashMap<String, Loaded> scenarios = new ConcurrentHashMap<>();
    private final List<String> loadOrder = new CopyOnWriteArrayList<>();

    @Autowired
    public ScenarioEngineImpl(ClockScheduler clockScheduler, ScenarioActionSink actionSink) {
        this.clockScheduler = clockScheduler;
        this.actionSink = actionSink;
    }

    // ==================== 加载 ====================

    @Override
    public synchronized ScenarioDefinition load(Path yaml) {
        return register(parser.parse(yaml));
    }

    @Override
    public synchronized ScenarioDefinition loadText(String yamlText) {
        return register(parser.parseText(yamlText));
    }

    /** 注册（同 id 重载：替换定义并复位为 LOADED；运行中的旧实例先取消登记） */
    private ScenarioDefinition register(ScenarioDefinition definition) {
        Loaded existing = scenarios.get(definition.id());
        if (existing != null) {
            cancelPending(existing);
            log.info("场景重载（替换旧实例）: id={}", definition.id());
        } else {
            loadOrder.add(definition.id());
        }
        scenarios.put(definition.id(), new Loaded(definition));
        log.info("场景已加载: id={}, devices={}, events={}", definition.id(),
                definition.devices().size(), definition.events().size());
        return definition;
    }

    // ==================== 生命周期 ====================

    @Override
    public synchronized void start(String scenarioId) {
        Loaded loaded = require(scenarioId);
        if (loaded.state == ScenarioState.RUNNING) {
            return; // 幂等
        }
        // 可重跑：清计数与失效句柄
        loaded.cancellables.clear();
        loaded.firedEvents = 0;

        // clock 覆盖（可选）：realtime 固定 1.0；accelerated 取场景倍率
        ScenarioDefinition.ClockOverride clockOverride = loaded.definition.clock();
        if (clockOverride != null) {
            double speed = "accelerated".equals(clockOverride.mode()) ? clockOverride.speed() : 1.0;
            clockScheduler.clock().setSpeed(speed);
            log.info("场景时钟覆盖生效: id={}, speed={}x", scenarioId, speed);
        }

        loaded.startedAtElapsedMillis = clockScheduler.clock().elapsedMillis();
        loaded.state = ScenarioState.RUNNING;

        List<ScenarioDefinition.EventSpec> events = loaded.definition.events();
        if (events.isEmpty()) {
            loaded.state = ScenarioState.FINISHED; // 空时间轴：启动即结束
            log.info("场景启动（无事件，直接完成）: id={}", scenarioId);
            return;
        }
        for (int i = 0; i < events.size(); i++) {
            ScenarioDefinition.EventSpec event = events.get(i);
            // scheduleOnce 的 delayMillis 相对"当前逻辑时间"，等价于场景启动后的 atMillis 偏移
            ClockScheduler.Cancellable cancellable = clockScheduler.scheduleOnce(
                    taskName(scenarioId, i), event.atMillis(), () -> fire(loaded, event));
            loaded.cancellables.add(cancellable);
        }
        log.info("场景已启动: id={}, events={}（按逻辑时间登记）", scenarioId, events.size());
    }

    @Override
    public synchronized void stop(String scenarioId) {
        Loaded loaded = require(scenarioId);
        if (loaded.state == ScenarioState.STOPPED || loaded.state == ScenarioState.LOADED) {
            loaded.state = ScenarioState.STOPPED; // 幂等（LOADED 直接置停）
            return;
        }
        cancelPending(loaded);
        loaded.state = ScenarioState.STOPPED;
        try {
            actionSink.cleanup(loaded.definition);
        } catch (Throwable t) {
            log.warn("场景清理执行异常: id={} - {}", scenarioId, t.getMessage(), t);
        }
        log.info("场景已停止: id={}（已触发 {}/{}）", scenarioId, loaded.firedEvents,
                loaded.definition.events().size());
    }

    // ==================== 状态 ====================

    @Override
    public ScenarioStatus status(String scenarioId) {
        return require(scenarioId).snapshot();
    }

    @Override
    public List<ScenarioStatus> list() {
        List<ScenarioStatus> result = new ArrayList<>();
        for (String id : loadOrder) {
            Loaded loaded = scenarios.get(id);
            if (loaded != null) {
                result.add(loaded.snapshot());
            }
        }
        return result;
    }

    // ==================== 内部 ====================

    /** 事件触发（在 ClockScheduler 的 tick 线程执行）：派发 → 计数 → 结束判定 */
    private void fire(Loaded loaded, ScenarioDefinition.EventSpec event) {
        try {
            actionSink.dispatch(loaded.definition, event);
        } catch (Throwable t) {
            // 单个事件失败不阻断时间轴（与调度器"任务异常不影响同 tick 其他任务"一致）
            log.error("场景事件执行异常: id={}, action={} - {}",
                    loaded.definition.id(), event.action(), t.getMessage(), t);
        }
        synchronized (loaded) {
            loaded.firedEvents++;
            if (loaded.state == ScenarioState.RUNNING
                    && loaded.firedEvents >= loaded.definition.events().size()) {
                loaded.state = ScenarioState.FINISHED;
                log.info("场景已跑完: id={}（{} 个事件全部触发）",
                        loaded.definition.id(), loaded.firedEvents);
            }
        }
    }

    /** 取消全部未触发登记（幂等） */
    private static void cancelPending(Loaded loaded) {
        for (ClockScheduler.Cancellable c : loaded.cancellables) {
            c.cancel();
        }
        loaded.cancellables.clear();
    }

    private Loaded require(String scenarioId) {
        Loaded loaded = scenarios.get(scenarioId);
        if (loaded == null) {
            throw new IllegalArgumentException("场景未加载: " + scenarioId);
        }
        return loaded;
    }

    private static String taskName(String scenarioId, int index) {
        return "scenario:" + scenarioId + ":event-" + index;
    }

    /** 已加载场景的运行时状态（synchronized(loaded) 保护计数与状态读写） */
    private static final class Loaded {

        private final ScenarioDefinition definition;
        private final List<ClockScheduler.Cancellable> cancellables = new CopyOnWriteArrayList<>();
        private volatile ScenarioState state = ScenarioState.LOADED;
        private int firedEvents;
        private long startedAtElapsedMillis = -1;

        private Loaded(ScenarioDefinition definition) {
            this.definition = definition;
        }

        private synchronized ScenarioStatus snapshot() {
            return new ScenarioStatus(definition.id(), state, definition.events().size(),
                    firedEvents, startedAtElapsedMillis);
        }
    }
}
