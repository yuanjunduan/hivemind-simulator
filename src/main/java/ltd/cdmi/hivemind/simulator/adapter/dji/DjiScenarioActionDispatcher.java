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

package ltd.cdmi.hivemind.simulator.adapter.dji;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.clock.SimulationClock;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightIntent;
import ltd.cdmi.hivemind.simulator.core.fault.FlightFaultInjector;
import ltd.cdmi.hivemind.simulator.core.fault.MediaFaultInjector;
import ltd.cdmi.hivemind.simulator.core.fault.NetworkFaultInjector;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioAction;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioActionSink;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioDefinition;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.media.FfmpegWhipPusher;

/**
 * DJI 场景动作分发器（T2.4）——真实 {@link ScenarioActionSink} 实现，
 * 把场景事件的 {@link ScenarioAction} 契约映射到既有执行路径：
 *
 * <ul>
 *   <li><b>飞行意图</b>（takeoff/goto/return_home/land/pause/resume）→
 *       {@link DjiFlightIntentHandler}（与 REST 意图通道共用执行体，行为一致）；</li>
 *   <li><b>任务引擎</b>：cancel_mission → {@link WaylineTaskSimulator#cancelMissionInternal()}；
 *       task_fail → {@code publishProgressFailedWithBreakReason()} + TASK_FAILED 告警；</li>
 *   <li><b>故障注入</b>：network_* → {@link NetworkFaultInjector}；gps/rtk/set_battery/trigger_alarm →
 *       {@link FlightFaultInjector}；video_stream 与 media_upload_fail → {@link MediaFaultInjector}
 *       （video_stream_stop 附加 {@link FfmpegWhipPusher#stopAll()} 停活跃推流）；</li>
 *   <li><b>场景级</b>：set_clock_speed → {@link SimulationClock#setSpeed(double)} 热切换。</li>
 * </ul>
 *
 * <p>设备归属：Schema v1 事件无 per-event device 字段，统一取场景 devices 首个设备 ID
 * （单机模拟器语义，与 DeviceState 单实例对齐）。</p>
 *
 * <p>执行容错：单个 action 失败只记日志不抛出（引擎时间轴继续推进）；
 * {@link #cleanup} 在场景停止/结束时复位全部注入门（幂等）。</p>
 */
@Component
public class DjiScenarioActionDispatcher implements ScenarioActionSink {

    private static final Logger log = LoggerFactory.getLogger(DjiScenarioActionDispatcher.class);

    private final DjiFlightIntentHandler intentHandler;
    private final WaylineTaskSimulator wayline;
    private final NetworkFaultInjector networkFault;
    private final FlightFaultInjector flightFault;
    private final MediaFaultInjector mediaFault;
    private final FfmpegWhipPusher ffmpegPusher;
    private final SimulationClock clock;

    @Autowired
    public DjiScenarioActionDispatcher(DjiFlightIntentHandler intentHandler,
                                       WaylineTaskSimulator wayline,
                                       NetworkFaultInjector networkFault,
                                       FlightFaultInjector flightFault,
                                       MediaFaultInjector mediaFault,
                                       FfmpegWhipPusher ffmpegPusher,
                                       SimulationClock clock) {
        this.intentHandler = intentHandler;
        this.wayline = wayline;
        this.networkFault = networkFault;
        this.flightFault = flightFault;
        this.mediaFault = mediaFault;
        this.ffmpegPusher = ffmpegPusher;
        this.clock = clock;
    }

    @Override
    public void dispatch(ScenarioDefinition scenario, ScenarioDefinition.EventSpec event) {
        ScenarioAction action = ScenarioAction.fromWireName(event.action());
        if (action == null) {
            // 解析器已 fail-fast；此处为防御性兜底（不静默）
            log.warn("[场景分发] 未知 action 被拒绝: scenario={}, action={}", scenario.id(), event.action());
            return;
        }
        String deviceId = deviceIdOf(scenario);
        Map<String, Object> params = event.params();
        log.info("[场景分发] scenario={}, at={}ms, action={}, device={}",
                scenario.id(), event.atMillis(), action.wireName(), deviceId);

        switch (action) {
            case TAKEOFF -> handleIntent(scenario, new FlightIntent.Takeoff(num(params, "altitude", 0)));
            case GOTO -> handleIntent(scenario, new FlightIntent.Goto(
                    num(params, "lng", 0), num(params, "lat", 0), num(params, "alt", 0),
                    num(params, "maxSpeed", 0)));
            case RETURN_HOME -> handleIntent(scenario, new FlightIntent.ReturnHome(
                    params.get("rthAltitude") instanceof Number n ? n.intValue() : null));
            case LAND -> handleIntent(scenario, new FlightIntent.Land());
            case PAUSE_MISSION -> handleIntent(scenario, new FlightIntent.PauseMission());
            case RESUME_MISSION -> handleIntent(scenario, new FlightIntent.ResumeMission());

            case CANCEL_MISSION -> wayline.cancelMissionInternal();
            case TASK_FAIL -> handleTaskFail(scenario, deviceId, params);

            case NETWORK_DISCONNECT, NETWORK_RECONNECT, NETWORK_DELAY, NETWORK_DROP ->
                    networkFault.inject(action.wireName(), deviceId, params);

            case SET_BATTERY, TRIGGER_ALARM, GPS_LOSS, GPS_RECOVER, RTK_LOSS, RTK_RECOVER ->
                    flightFault.inject(action.wireName(), deviceId, params);

            case VIDEO_STREAM_STOP -> {
                mediaFault.inject(action.wireName(), deviceId, params);
                ffmpegPusher.stopAll();
            }
            case VIDEO_STREAM_RECOVER, MEDIA_UPLOAD_FAIL ->
                    mediaFault.inject(action.wireName(), deviceId, params);

            case SET_CLOCK_SPEED -> {
                double speed = num(params, "speed", 1.0);
                clock.setSpeed(speed);
                log.info("[场景分发] 时钟倍率热切换: speed={}", speed);
            }
        }
    }

    @Override
    public void cleanup(ScenarioDefinition scenario) {
        networkFault.clear();
        flightFault.clear();
        mediaFault.clear();
        log.info("[场景分发] 故障注入已全部清理: scenario={}", scenario.id());
    }

    /** 任务失败：任务进度上报 failed + TASK_FAILED 告警（reason 进告警消息） */
    private void handleTaskFail(ScenarioDefinition scenario, String deviceId, Map<String, Object> params) {
        boolean sent = wayline.publishProgressFailedWithBreakReason();
        if (!sent) {
            log.warn("[场景分发] flighttask_progress(failed) 未发送（当前 Dock 型号不支持默认 break_reason）: scenario={}",
                    scenario.id());
        }
        flightFault.inject("task_fail", deviceId, params);
    }

    /** 飞行意图经统一处理器执行（失败只记日志，不阻断时间轴） */
    private void handleIntent(ScenarioDefinition scenario, FlightIntent intent) {
        DjiFlightIntentHandler.IntentResult result = intentHandler.handle(intent);
        if (!result.accepted()) {
            log.warn("[场景分发] 飞行意图被拒绝: scenario={}, intent={}, detail={}",
                    scenario.id(), intent.getClass().getSimpleName(), result.detail());
        }
    }

    /** 场景设备 ID（Schema v1 单机语义：取首个设备；无设备时用场景标识） */
    private static String deviceIdOf(ScenarioDefinition scenario) {
        return scenario.devices().isEmpty() ? scenario.id() : scenario.devices().get(0).id();
    }

    /** 读取数字参数（契约层已校验；缺失时使用默认值，防御性兜底） */
    private static double num(Map<String, Object> params, String key, double fallback) {
        return params.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }
}
