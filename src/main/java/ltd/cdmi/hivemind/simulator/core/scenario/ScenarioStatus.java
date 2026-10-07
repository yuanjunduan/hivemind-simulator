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
 * 场景运行状态快照（REST 响应 / E2E 断言消费）。
 *
 * @param scenarioId              场景 ID
 * @param state                   生命周期状态
 * @param totalEvents             事件总数（时间轴长度）
 * @param firedEvents             已触发事件数（含 sink 执行抛错的尝试）
 * @param startedAtElapsedMillis  本场景启动时的逻辑时间（{@code clock.elapsedMillis()}；未启动过为 -1）
 */
public record ScenarioStatus(String scenarioId, ScenarioState state, int totalEvents,
                             int firedEvents, long startedAtElapsedMillis) {
}
