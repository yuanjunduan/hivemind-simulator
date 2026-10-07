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
 * 相机信息（统一模型）。
 * <p>payloadIndex 为负载索引（DJI 格式 {type-subtype-gimbalindex}，其他厂商允许自定义格式，
 * 上层按字符串透传）；mode/recordingState/photoState 为统一语义摘要，厂商枚举差异进 vendorExt。</p>
 */
@Getter
@Builder
@Jacksonized
@EqualsAndHashCode
public final class CameraInfo {

    /** 负载索引（如 DJI 的 98-0-0） */
    private final String payloadIndex;

    /** 相机模式：0=拍照, 1=录像（统一语义） */
    private final int mode;

    /** 录像状态：0=空闲, 1=录像中 */
    private final int recordingState;

    /** 拍照状态：0=空闲, 1=拍照中 */
    private final int photoState;
}
