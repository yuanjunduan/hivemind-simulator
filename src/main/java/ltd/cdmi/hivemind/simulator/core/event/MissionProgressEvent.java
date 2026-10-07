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

package ltd.cdmi.hivemind.simulator.core.event;

import java.time.Instant;
import java.util.Objects;

import lombok.Getter;

/**
 * 任务进度变化事件（实施方案 §2.4）。
 * <p>发布时机：任务进度变化时（Wayline 3s 进度步进、暂停/恢复/取消等状态迁移）。
 * {@code state} 与 {@code UnifiedMission.state} 同源（IDLE/PREPARING/EXECUTING/PAUSED/COMPLETED/CANCELED/FAILED），
 * {@code currentStep} 为统一任务阶段序号（{@code MissionPhase.ordinal()}：0=准备 1=执行 2=返航 3=降落 4=完成）；
 * DJI 厂商步骤（current_step）与统一步序的双向转换由 adapter/dji 的 DjiMissionStepMapper 承担。</p>
 */
@Getter
public final class MissionProgressEvent extends SimulationEvent {

    /** 任务 ID */
    private final String missionId;

    /** 任务状态（与 UnifiedMission.state 同源） */
    private final String state;

    /** 进度百分比 0~100 */
    private final int percent;

    /** 当前步序（统一任务阶段序号 MissionPhase.ordinal()，供厂商映射器使用） */
    private final int currentStep;

    public MissionProgressEvent(String deviceId, Instant occurredAtInstant,
                                String missionId, String state, int percent, int currentStep) {
        super(deviceId, occurredAtInstant);
        this.missionId = Objects.requireNonNull(missionId, "missionId");
        this.state = Objects.requireNonNull(state, "state");
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("任务进度 percent 必须在 0~100: " + percent);
        }
        this.percent = percent;
        this.currentStep = currentStep;
    }
}
