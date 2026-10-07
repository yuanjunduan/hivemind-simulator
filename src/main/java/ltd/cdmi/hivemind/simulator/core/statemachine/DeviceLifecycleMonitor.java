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

package ltd.cdmi.hivemind.simulator.core.statemachine;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.core.event.DeviceLifecycleChangedEvent;
import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.WaylineTaskSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;

/**
 * 设备生命周期观察者（实施方案 §2.5.3，ADR-8 观察者先行）。
 *
 * <p>每 1s（FIXED_RATE，逻辑时间，加速模式下随倍率缩放）对 {@link DeviceState} +
 * MQTT 链路 + 任务标记做一次 {@link DjiStateMapper} 只读推导；<b>不拦截、不修改</b>
 * 现有写路径。状态变化时发布 {@link DeviceLifecycleChangedEvent}（时间戳来自逻辑时钟）并记日志；
 * 未命中文档化映射组合时发 M-3 级诊断码（按输入签名去重，避免 1s 扫描刷屏）。</p>
 *
 * <p>关键事件（注册完成、任务下发等）可调用 {@link #evaluateNow()} 做即时推导，
 * W1 供测试与后续 adapter 使用；第二期"收权"（新功能只允许经状态机写状态）留到 W2 场景引擎接入。</p>
 */
@Component
public class DeviceLifecycleMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeviceLifecycleMonitor.class);

    /** 扫描周期（逻辑时间毫秒）：§2.5.3 "每 1s 扫描一次" */
    private static final long SCAN_INTERVAL_MILLIS = 1000;

    private final DeviceState state;
    private final MqttClientManager mqtt;
    private final WaylineTaskSimulator wayline;
    private final RuntimeConfig runtimeConfig;
    private final EventBus eventBus;
    private final ClockScheduler clockScheduler;
    private final DiagnosticLogRecorder diagnosticRecorder;

    /** 最近一次推导出的状态（初始 CREATED，与 LifecycleState 枚举语义一致） */
    private volatile LifecycleState currentState = LifecycleState.CREATED;

    /** 最近一次未命中组合签名（去重用；命中时清空） */
    private volatile String lastUnmappedSignature;

    public DeviceLifecycleMonitor(DeviceState state, MqttClientManager mqtt,
                                  WaylineTaskSimulator wayline, RuntimeConfig runtimeConfig,
                                  EventBus eventBus, ClockScheduler clockScheduler,
                                  DiagnosticLogRecorder diagnosticRecorder) {
        this.state = state;
        this.mqtt = mqtt;
        this.wayline = wayline;
        this.runtimeConfig = runtimeConfig;
        this.eventBus = eventBus;
        this.clockScheduler = clockScheduler;
        this.diagnosticRecorder = diagnosticRecorder;
    }

    @PostConstruct
    public void init() {
        clockScheduler.register("lifecycle-scan", ClockScheduler.TaskSemantics.FIXED_RATE,
                SCAN_INTERVAL_MILLIS, this::evaluateNow);
        log.info("设备生命周期观察者已启动（1s 逻辑时间扫描，只读推导，观察者先行）");
    }

    /** 最近一次推导状态 */
    public LifecycleState currentState() {
        return currentState;
    }

    /**
     * 即时推导一次：状态变化时发布 {@link DeviceLifecycleChangedEvent}，未命中组合发 M-3 诊断。
     *
     * @return 本次推导状态
     */
    public LifecycleState evaluateNow() {
        DjiStateMapper.Input in = snapshot();
        DjiStateMapper.Mapping mapping = DjiStateMapper.map(in);
        handleUnmapped(in, mapping);

        LifecycleState prev = currentState;
        LifecycleState next = mapping.state();
        if (next != prev) {
            currentState = next;
            eventBus.publish(new DeviceLifecycleChangedEvent(
                    UnifiedDevice.deviceIdOf(DeviceVendor.DJI, runtimeConfig.getGatewaySn()),
                    eventBus.logicalNow(), prev, next));
            log.info("设备生命周期变化：{} → {}（modeCode={}, inDock={}, missionActive={}）",
                    prev, next, in.droneModeCode(), in.droneInDock(), in.missionActive());
        }
        return next;
    }

    /** 组装映射输入快照（只读） */
    private DjiStateMapper.Input snapshot() {
        return new DjiStateMapper.Input(
                state.isPowered(),
                state.isOnline(),
                mqtt.isConnected(),
                state.isDroneInDock(),
                state.isDroneActivated(),
                state.getDroneModeCode(),
                wayline.isMissionActive(),
                wayline.isMissionPaused(),
                false, // missionFailed：W1 无来源，W2 场景引擎注入故障时填充
                state.getDroneChargeState() == 1);
    }

    /** 未命中组合的 M-3 诊断（按输入签名去重；命中时清空签名允许下次再报） */
    private void handleUnmapped(DjiStateMapper.Input in, DjiStateMapper.Mapping mapping) {
        if (mapping.documented()) {
            lastUnmappedSignature = null;
            return;
        }
        String signature = in.toString();
        if (signature.equals(lastUnmappedSignature)) {
            return;
        }
        lastUnmappedSignature = signature;
        diagnosticRecorder.record(DiagnosticCode.MONITOR_LIFECYCLE_UNMAPPED, "lifecycle_map",
                "未命中文档化映射的字段组合：" + signature + "（§2.5.2 推导表之外，兜底状态 " + mapping.state() + "）");
        log.warn("[M-3] 生命周期状态未命中文档化映射：{}（兜底 {}）", signature, mapping.state());
    }
}
