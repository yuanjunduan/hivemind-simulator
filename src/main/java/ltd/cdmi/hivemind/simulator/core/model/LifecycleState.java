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

/**
 * 设备生命周期状态（统一状态机，对齐需求 §5）。
 * <p>第一期（W1）由 core/statemachine 从现有 DeviceState 字段组合只读推导（ADR-8 观察者模式），
 * 不拦截现有写路径；第二期"收权"后成为唯一写入口，保证
 * "设备状态、任务状态、飞行状态、告警状态之间不互相矛盾"（需求 §5）。</p>
 */
public enum LifecycleState {

    /** 未开机（初始态） */
    CREATED,

    /** 注册中（config → bind_status 流程进行中） */
    REGISTERING,

    /** 离线（开机但未上线） */
    OFFLINE,

    /** 在线（上线流程完成） */
    ONLINE,

    /** 就绪（在舱待命，可执行任务） */
    READY,

    /** 已解锁/起飞准备中 */
    ARMED,

    /** 起飞中 */
    TAKING_OFF,

    /** 飞行中（无任务） */
    FLYING,

    /** 任务执行中（航线飞行且有任务） */
    MISSION,

    /** 任务暂停 */
    PAUSED,

    /** 返航中 */
    RETURNING,

    /** 失败（任务失败/设备异常） */
    FAILED,

    /** 降落中 */
    LANDING,

    /** 已降落 */
    LANDED,

    /** 充电中 */
    CHARGING,

    /** 网络丢失（MQTT 断连） */
    NETWORK_LOST,

    /** 重连中 */
    RECONNECTING
}
