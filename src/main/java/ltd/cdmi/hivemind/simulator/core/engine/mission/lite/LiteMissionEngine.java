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

package ltd.cdmi.hivemind.simulator.core.engine.mission.lite;

import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionEngine;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionPhase;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionPlan;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionSnapshot;
import ltd.cdmi.hivemind.simulator.core.engine.mission.MissionState;
import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.MissionProgressEvent;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * 轻量任务引擎（实施方案 §2.7）——统一状态机骨架 + 逻辑进度推进。
 *
 * <p>驱动语义：{@link #load} 进入 PREPARING；{@link #start()} 进入 EXECUTING 后每次
 * {@link #advance(Instant)} 推进一个航点（percent 按航点均分 0→90），航点耗尽后按
 * RETURNING(90) → LANDING(95) → COMPLETED(100) 逐格推进（与 DJI 6 步序列的阶段语义对齐，
 * 见 adapter/dji 的 DjiMissionStepMapper）。</p>
 *
 * <p>进度/状态变化时发布 {@link MissionProgressEvent}（时间戳取逻辑时钟；
 * {@code currentStep} = {@link MissionPhase#ordinal()}）。W1 现有 DJI 执行路径
 * （WaylineTaskSimulator）行为不变；本引擎供 W2 场景引擎驱动。</p>
 */
@Component
public class LiteMissionEngine implements MissionEngine {

    private static final Logger log = LoggerFactory.getLogger(LiteMissionEngine.class);

    /** 执行阶段进度上限（按航点均分 0→90，与 DJI 90% 档对齐） */
    private static final double PERCENT_EXECUTING_MAX = 90.0;
    /** 返航阶段统一进度档 */
    private static final int PERCENT_RETURNING = 90;
    /** 降落阶段统一进度档 */
    private static final int PERCENT_LANDING = 95;

    /** 事件总线（纯状态机模式下为 null） */
    private final EventBus eventBus;
    /** 运行时配置（提供 deviceId 的 SN；纯状态机模式下为 null） */
    private final RuntimeConfig runtimeConfig;

    private MissionPlan plan;
    private MissionState state = MissionState.IDLE;
    private MissionPhase phase;
    private int percent;
    private int currentWaypoint = -1;

    /**
     * Spring 注入构造器：发布 {@link MissionProgressEvent} 需要事件总线与设备 SN。
     */
    @Autowired
    public LiteMissionEngine(EventBus eventBus, RuntimeConfig runtimeConfig) {
        this.eventBus = eventBus;
        this.runtimeConfig = runtimeConfig;
    }

    /** 兼容构造器（测试直接 new 用）：纯状态机，不发布事件。 */
    public LiteMissionEngine() {
        this(null, null);
    }

    @Override
    public synchronized void load(MissionPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.state = MissionState.PREPARING;
        this.phase = MissionPhase.PREPARING;
        this.percent = 0;
        this.currentWaypoint = -1;
        log.info("任务已加载: missionId={}, waypoints={}", plan.missionId(), plan.waypoints().size());
        publishProgress();
    }

    @Override
    public synchronized void start() {
        if (state != MissionState.PREPARING) {
            log.debug("start 忽略：当前状态 {}", state);
            return;
        }
        state = MissionState.EXECUTING;
        phase = MissionPhase.EXECUTING;
        log.info("任务开始执行: missionId={}", plan.missionId());
        publishProgress();
    }

    @Override
    public synchronized void pause() {
        if (state != MissionState.EXECUTING) {
            log.debug("pause 忽略：当前状态 {}", state);
            return;
        }
        state = MissionState.PAUSED;
        log.info("任务暂停: missionId={}, phase={}", plan.missionId(), phase);
        publishProgress();
    }

    @Override
    public synchronized void resume() {
        if (state != MissionState.PAUSED) {
            log.debug("resume 忽略：当前状态 {}", state);
            return;
        }
        state = MissionState.EXECUTING;
        log.info("任务恢复: missionId={}, phase={}", plan.missionId(), phase);
        publishProgress();
    }

    @Override
    public synchronized void cancel() {
        if (state == MissionState.IDLE || state.isTerminal()) {
            log.debug("cancel 忽略：当前状态 {}", state);
            return;
        }
        state = MissionState.CANCELED;
        log.info("任务已取消: missionId={}, phase={}", plan.missionId(), phase);
        publishProgress();
    }

    @Override
    public synchronized void advance(Instant logicalNow) {
        if (state != MissionState.EXECUTING || phase == null) {
            return; // 未开始/暂停/终态：不推进（幂等）
        }
        switch (phase) {
            case EXECUTING -> {
                int total = plan.waypoints().size();
                if (currentWaypoint + 1 >= total) {
                    phase = MissionPhase.RETURNING;
                    percent = PERCENT_RETURNING;
                } else {
                    currentWaypoint++;
                    percent = (int) Math.round((currentWaypoint + 1) * PERCENT_EXECUTING_MAX / total);
                }
            }
            case RETURNING -> {
                phase = MissionPhase.LANDING;
                percent = PERCENT_LANDING;
            }
            case LANDING -> {
                phase = MissionPhase.COMPLETED;
                percent = 100;
                state = MissionState.COMPLETED;
            }
            default -> {
                return; // PREPARING/COMPLETED：不推进
            }
        }
        publishProgress();
    }

    @Override
    public synchronized MissionSnapshot snapshot() {
        return new MissionSnapshot(state, phase, percent, currentWaypoint, null);
    }

    /** 发布进度事件（纯状态机模式或未加载时跳过） */
    private void publishProgress() {
        if (eventBus == null || runtimeConfig == null || plan == null) {
            return;
        }
        eventBus.publish(new MissionProgressEvent(
                UnifiedDevice.deviceIdOf(DeviceVendor.DJI, runtimeConfig.getGatewaySn()),
                eventBus.logicalNow(),
                plan.missionId(),
                state.name(),
                percent,
                phase != null ? phase.ordinal() : -1));
    }
}
