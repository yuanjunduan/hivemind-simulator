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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 仿真时钟与调度器配置（绑定 application.yml 中 simulation.* 配置）。
 * <p>对齐可行性方案 §4.4 ADR-4/ADR-5 与实施方案 §2.10：
 * <ul>
 *   <li>{@code simulation.clock.mode=realtime}（缺省）时行为与 v1.4.6 逐位等价（红线 2）；</li>
 *   <li>{@code simulation.clock.mode=accelerated} 时按 {@code speed} 倍率推进逻辑时间；</li>
 *   <li>{@code simulation.mqtt.publish-rate-cap-hz} 为加速模式下的报文频率封顶（ADR-5，W1/T1.10 使用）；</li>
 *   <li>{@code simulation.scheduler.*} 控制物理 tick 周期与单 tick 补发上限（防追帧卡死）。</li>
 * </ul>
 * <p>所有子结构均做缺省兜底：配置缺失/非法值不阻断启动，回落到与现状一致的默认值。</p>
 */
@ConfigurationProperties(prefix = "simulation")
public record ClockProperties(Clock clock, Scheduler scheduler, Mqtt mqtt) {

    /** 时钟模式：realtime=与现有行为等价（speed 强制 1.0）；accelerated=按 speed 倍率加速 */
    public enum ClockMode {
        REALTIME,
        ACCELERATED
    }

    /**
     * 时钟配置。
     *
     * @param mode  realtime | accelerated（缺省 realtime）
     * @param speed 加速倍率（仅 accelerated 生效；缺省/非法值兜底为 1.0）
     */
    public record Clock(ClockMode mode, double speed) {
        public Clock {
            if (mode == null) {
                mode = ClockMode.REALTIME;
            }
            if (!(speed > 0) || !Double.isFinite(speed)) {
                speed = 1.0;
            }
        }
    }

    /**
     * 调度器配置。
     *
     * @param tickMillis          物理 tick 周期（与倍率无关，缺省 100ms）
     * @param fixedRateCatchupMax 单物理 tick 内 FIXED_RATE 任务最多补发次数（缺省 5，防追帧卡死）
     */
    public record Scheduler(long tickMillis, int fixedRateCatchupMax) {
        public Scheduler {
            if (tickMillis <= 0) {
                tickMillis = 100;
            }
            if (fixedRateCatchupMax <= 0) {
                fixedRateCatchupMax = 5;
            }
        }
    }

    /**
     * 加速模式下的 MQTT 报文频率封顶（ADR-5；realtime 不生效）。
     *
     * @param publishRateCapHz 报文发送频率上限（Hz，缺省 2）
     */
    public record Mqtt(int publishRateCapHz) {
        public Mqtt {
            if (publishRateCapHz <= 0) {
                publishRateCapHz = 2;
            }
        }
    }

    public ClockProperties {
        if (clock == null) {
            clock = new Clock(ClockMode.REALTIME, 1.0);
        }
        if (scheduler == null) {
            scheduler = new Scheduler(100, 5);
        }
        if (mqtt == null) {
            mqtt = new Mqtt(2);
        }
    }
}
