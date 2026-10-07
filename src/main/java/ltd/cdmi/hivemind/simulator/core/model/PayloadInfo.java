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

package ltd.cdmi.hivemind.simulator.core.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 负载信息摘要（统一模型）。
 * <p>type 为统一负载类型名（如 CAMERA/SEARCHLIGHT/SPEAKER），index 为厂商内负载编号，
 * working 表示负载当前是否工作/在线；负载明细（如探照灯亮度）放 vendorExt。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class PayloadInfo {

    /** 负载类型（统一语义名） */
    private final String type;

    /** 负载名称/型号 */
    private final String name;

    /** 厂商内负载编号 */
    private final int index;

    /** 是否工作/在线 */
    private final boolean working;
}
