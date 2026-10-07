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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * core/clock 装配（实施方案 §1 包树）：注册仿真时钟与统一调度器单例。
 * <p>{@link ClockProperties} 由 {@code @ConfigurationPropertiesScan}（SimulatorApplication）自动注册，
 * 本类只负责组件装配。</p>
 */
@Configuration
public class ClockConfiguration {

    /** 仿真时钟：全仿真唯一时间源（REALTIME/加速统一实现，§2.3.2） */
    @Bean
    public SimulationClock simulationClock(ClockProperties properties) {
        return new SimulationClockImpl(properties);
    }

    /** 统一调度器：物理 tick 驱动所有仿真心跳（T1.3 起逐步替换现有各独立调度器） */
    @Bean
    public ClockScheduler clockScheduler(SimulationClock clock, ClockProperties properties) {
        return new ClockScheduler(clock, properties);
    }
}
