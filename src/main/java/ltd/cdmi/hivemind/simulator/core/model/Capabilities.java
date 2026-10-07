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

import java.util.List;

/**
 * 设备能力集（统一模型，能力协商基础）。
 * <p>司空/JOUAV Mock 可声明子集能力，控制台按能力渲染功能入口；DJI 全量能力由
 * dji-adapter 声明。字段为语义级能力，型号级差异用列表字段表达（payloadTypes/dockModels）。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class Capabilities {

    /** 是否支持 RTK */
    private final boolean supportsRtk;

    /** 是否支持"飞行器在舱"形态（Dock 类设备为 true） */
    private final boolean supportsDroneInDock;

    /** 支持的负载类型列表（统一语义名） */
    private final List<String> payloadTypes;

    /** 支持的机场型号列表（厂商内型号名） */
    private final List<String> dockModels;
}
