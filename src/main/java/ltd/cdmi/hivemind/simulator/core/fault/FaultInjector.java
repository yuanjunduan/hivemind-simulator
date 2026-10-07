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

package ltd.cdmi.hivemind.simulator.core.fault;

import java.util.Map;

/**
 * 故障注入器（实施方案 §4.3，T2.4）——三类注入器的统一契约。
 *
 * <p>注入器持有故障状态（gate），由被注入方（MQTT 分发 / 媒体上传 / 推流 / 设备状态）
 * 在执行路径上查询；{@link #clear()} 在场景停止或结束时统一复位（幂等）。</p>
 *
 * <p>实现约定：{@link #inject} 对未知 action 只记警告不抛异常（契约层
 * {@code ScenarioAction} 已 fail-fast 校验，运行期防御性兜底）；注入生效后必须发布
 * {@code FaultInjectedEvent}（经 {@code EventBus}，供 E2E 编排器与平台侧观测）。</p>
 */
public interface FaultInjector {

    /** 注入器名："network" / "flight" / "media" */
    String name();

    /**
     * 执行注入（action → 内部状态变更）。
     *
     * @param action   契约 action 名（{@code ScenarioAction.wireName()}）
     * @param deviceId 注入目标（场景内设备 ID；用于事件与告警归属）
     * @param params   事件参数（契约层已校验；按 action 语义读取）
     */
    void inject(String action, String deviceId, Map<String, Object> params);

    /** 复位全部注入状态（幂等；场景 stop / 完成时调用） */
    void clear();
}
