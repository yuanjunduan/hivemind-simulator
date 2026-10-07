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
 * 场景生命周期状态（实施方案 §4.2：LOADED → RUNNING → FINISHED / STOPPED）。
 *
 * <p>状态迁移：</p>
 * <pre>
 * LOADED ──start──► RUNNING ──全部事件触发完──► FINISHED
 *   │                 │
 *   │                 └──stop──► STOPPED ──start──► RUNNING（可重跑：计数清零、重新登记）
 *   └──stop──► STOPPED
 * </pre>
 */
public enum ScenarioState {

    /** 已加载（解析校验通过，未启动） */
    LOADED,

    /** 运行中（事件已按逻辑时间登记） */
    RUNNING,

    /** 已跑完（全部事件触发完毕） */
    FINISHED,

    /** 已停止（人工 stop；未触发事件全部取消） */
    STOPPED
}
