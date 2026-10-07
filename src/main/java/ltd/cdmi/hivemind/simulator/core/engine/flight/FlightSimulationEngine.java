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

package ltd.cdmi.hivemind.simulator.core.engine.flight;

import java.time.Instant;

/**
 * 飞行仿真引擎（实施方案 §2.6.1）——统一飞行意图的执行体抽象。
 * <p>W1 仅 {@code LiteFlightEngine} 实现（抽取现有插值/杆量算法，逐位等价）；
 * PX4/FastSwarm 引擎为后置波次，接口先行（ADR-12）。</p>
 * <p>驱动方式：{@link #advance} 由 {@code ClockScheduler} 的
 * {@code INTEGRATOR} 语义任务按物理 tick 调用（任务内部按 dt 自行积分）；
 * REALTIME 与加速模式下行为一致（逻辑时间驱动）。</p>
 */
public interface FlightSimulationEngine {

    /** 引擎名："lite" / "px4" / "fastswarm" */
    String name();

    /** 接收飞行意图（设置目标/队列/速度参数，不直接推进位置） */
    void apply(FlightIntent intent, FlightRuntimeContext ctx);

    /** 推进一个积分步（由 ClockScheduler INTEGRATOR 驱动；首帧仅建立时间基准） */
    void advance(Instant logicalNow, FlightRuntimeContext ctx);

    /** 当前飞行快照（只读视图） */
    FlightSnapshot snapshot(FlightRuntimeContext ctx);
}
