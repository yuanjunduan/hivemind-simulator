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
import java.util.List;

/**
 * 场景引擎（实施方案 §4.2 冻结接口）。
 *
 * <p>职责：场景 YAML 的加载与校验（{@link ScenarioParser}）、时间轴事件按
 * <b>逻辑时间</b>登记与触发（接 {@code ClockScheduler}）、生命周期管理
 * （start/stop/status）与结束时注入清理。</p>
 *
 * <p>线程安全：load/start/stop 可并发调用（幂等）；status/list 无锁快照。</p>
 */
public interface ScenarioEngine {

    /**
     * 从文件加载场景（解析 + 校验；同 id 重复加载替换旧实例并复位为 LOADED）。
     *
     * @param yaml 场景 YAML 文件路径
     * @return 校验通过的场景定义
     * @throws ScenarioValidationException 读取失败/校验失败
     */
    ScenarioDefinition load(Path yaml);

    /**
     * 从文本加载场景（REST 上传与测试复用）。
     *
     * @param yamlText 场景 YAML 文本
     * @return 校验通过的场景定义
     * @throws ScenarioValidationException 语法错误/校验失败
     */
    ScenarioDefinition loadText(String yamlText);

    /**
     * 启动场景：应用 clock 覆盖（若有）→ 按 {@code atMillis} 登记全部事件（逻辑时间触发）。
     * <p>幂等：RUNNING 状态下重复调用为 no-op；STOPPED/FINISHED 后允许重跑（计数清零、重新登记）。</p>
     *
     * @param scenarioId 场景 ID
     * @throws IllegalArgumentException 场景未加载
     */
    void start(String scenarioId);

    /**
     * 停止场景：取消全部未触发事件 + 执行注入清理（断开恢复/故障标志复位）。
     * <p>幂等：STOPPED/LOADED 状态下重复调用为 no-op。</p>
     *
     * @param scenarioId 场景 ID
     * @throws IllegalArgumentException 场景未加载
     */
    void stop(String scenarioId);

    /**
     * 场景运行状态。
     *
     * @param scenarioId 场景 ID
     * @return 状态快照
     * @throws IllegalArgumentException 场景未加载
     */
    ScenarioStatus status(String scenarioId);

    /** 全部已加载场景的状态（按加载顺序） */
    List<ScenarioStatus> list();
}
