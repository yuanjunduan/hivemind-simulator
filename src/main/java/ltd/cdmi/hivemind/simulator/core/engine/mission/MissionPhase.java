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

package ltd.cdmi.hivemind.simulator.core.engine.mission;

/**
 * 统一任务阶段（实施方案 §2.7，跨厂商语义）。
 *
 * <p>任务执行内部的阶段链（ordinal 即统一步序，供 {@code MissionProgressEvent.currentStep} 使用）：
 * {@link #PREPARING}（0 准备）→ {@link #EXECUTING}（1 执行航线）→ {@link #RETURNING}（2 返航）
 * → {@link #LANDING}（3 降落）→ {@link #COMPLETED}（4 完成）。</p>
 *
 * <p>DJI 6 步序列经 adapter/dji 的 {@code DjiMissionStepMapper} 映射到本枚举
 * （Dock2/3：7→PREPARING、24→EXECUTING、26→RETURNING、27/29→LANDING、35→COMPLETED）；
 * DJI 特有步骤值不进入 core，只存在于 adapter/dji 映射表。</p>
 */
public enum MissionPhase {
    PREPARING, EXECUTING, RETURNING, LANDING, COMPLETED
}
