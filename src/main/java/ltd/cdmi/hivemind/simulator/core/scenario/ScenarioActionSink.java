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

/**
 * 场景 action 执行回调（时间轴与执行层解耦的缝）。
 *
 * <p>时间轴执行器只负责"按逻辑时间把事件送达"，具体语义由实现承担：
 * W2 主实现为 {@code adapter/dji} 侧的 action 分发器（意图通道 + 故障注入 + 状态回写），
 * 测试可注入记录型假实现做时序断言。</p>
 */
@FunctionalInterface
public interface ScenarioActionSink {

    /**
     * 执行一个时间轴事件（执行异常由时间轴捕获记录，不影响后续事件）。
     *
     * @param scenario 事件所属场景（含 devices 定义，实现据此解析设备作用域 action 的目标）
     * @param event    事件（action + 参数，解析时已通过契约校验）
     */
    void dispatch(ScenarioDefinition scenario, ScenarioDefinition.EventSpec event);

    /**
     * 场景结束清理（stop 时调用；幂等）。语义：恢复本场景注入过的所有故障
     * （断开的连接重连、gps/rtk 标志复位、上传失败率清零等）。
     *
     * @param scenario 结束的场景
     */
    default void cleanup(ScenarioDefinition scenario) {
    }
}
