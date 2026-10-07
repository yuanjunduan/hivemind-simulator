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

/**
 * 飞行阶段（统一语义，实施方案 §2.6.2）。
 * <p>由飞行引擎依据"当前位置与目标的水平/垂直差"推导：无目标=悬停、
 * 垂直差优先（爬升/下降）、水平推进=巡航。厂商特有状态编码由 Adapter 映射，不进 core。</p>
 */
public enum FlightPhase {

    /** 悬停/静止（无目标或已到达） */
    HOVER,

    /** 水平巡航（向目标点平飞） */
    CRUISE,

    /** 爬升 */
    CLIMB,

    /** 下降 */
    DESCEND
}
