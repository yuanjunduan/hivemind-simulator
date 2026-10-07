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

import java.time.Instant;

/**
 * 仿真时钟（V3.0 唯一时间源，实施方案 §2.3）。
 * <p>仿真内的一切时间读取（遥测时间戳、任务进度、插值积分 dt、场景事件触发）都必须
 * 经本接口取"逻辑时间"，禁止直接使用 {@code System.currentTimeMillis()}——
 * 这是时间加速（P0 第 12 项）与确定性回放的前提。</p>
 * <p>REALTIME 模式（speed=1）下逻辑时间 ≈ 真实时间，行为与 v1.4.6 逐位等价（红线 2）。</p>
 */
public interface SimulationClock {

    /** 逻辑时间（REALTIME 下 ≈ 真实时间） */
    Instant now();

    /** 自启动以来的逻辑毫秒数 */
    long elapsedMillis();

    /** 当前倍率（1.0 = REALTIME） */
    double speed();

    /**
     * 运行时切换倍率（场景指令 / REST API 调用）。
     * <p>实现须"先结算旧倍率下已流逝的逻辑时间，再重锚"（rebase），
     * 保证切换瞬间逻辑时间连续、不跳变、不回退。</p>
     *
     * @param speed 新倍率，必须为正有限值
     */
    void setSpeed(double speed);

    /** 真实墙钟度量（压测指标用；不受倍率影响） */
    WallClockMetrics wallMetrics();

    /**
     * 墙钟度量快照（压测/资源统计用，对齐需求 §26）。
     *
     * @param wallStartedAt    时钟启动的真实时间
     * @param wallNow          当前真实时间
     * @param wallUptimeMillis 真实运行时长（单调时钟计量，不受系统时间调整影响）
     */
    record WallClockMetrics(Instant wallStartedAt, Instant wallNow, long wallUptimeMillis) {
    }
}
