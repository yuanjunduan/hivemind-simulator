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

package ltd.cdmi.hivemind.simulator.core.statemachine;

import ltd.cdmi.hivemind.simulator.core.model.LifecycleState;

/**
 * DJI 字段 → 统一生命周期状态映射表（实施方案 §2.5.2，第一期只读）。
 *
 * <p>纯函数映射：输入为 {@link Input}（DeviceState 字段 + MQTT 链路 + 任务标记的快照），
 * 输出 {@link Mapping}（状态 + 是否命中文档化组合）。优先级顺序即 §2.5.2 推导表顺序：</p>
 * <ol>
 *   <li>未开机 → {@link LifecycleState#CREATED}；</li>
 *   <li>MQTT 断连 → {@link LifecycleState#NETWORK_LOST}（重连成功后自动回落正常判定）；</li>
 *   <li>任务失败/暂停标记 → {@link LifecycleState#FAILED}/{@link LifecycleState#PAUSED}；</li>
 *   <li>开机未上线 → {@link LifecycleState#OFFLINE}；</li>
 *   <li>其余按 droneModeCode 细分（起飞/航线/返航/降落/在舱三态等）。</li>
 * </ol>
 *
 * <p>未命中文档化组合（{@code documented=false}）由调用方发 M-3 级诊断码并兜底——
 * 这是"防矛盾断言"的实现：状态与 droneModeCode/任务状态的组合必须命中文档化映射。</p>
 *
 * <p>已知推断项（M-2）：§2.5.2 表中 {@code droneModeCode∈{9,13}} 标注为返航，
 * 但 DJI M30 文档中 13=升级中；此处从方案表实现，待真机验证后修正。
 * 11/12（强制降落/三桨叶降落）方案表未列，按降落语义归入 LANDING；
 * 3/6/7/8/15/16/17 按 DJI 枚举归入飞行中（FLYING）。</p>
 */
public final class DjiStateMapper {

    private DjiStateMapper() {
    }

    /**
     * 映射输入快照（不可变；由观察者从 DeviceState + MQTT 链路 + 任务状态组装）。
     *
     * @param powered        设备已开机
     * @param online         已执行上线流程（平台在线）
     * @param mqttConnected  MQTT 链路已连接
     * @param droneInDock    飞行器在舱内
     * @param droneActivated 飞行器已激活
     * @param droneModeCode  飞行器模式码（DJI mode_code 枚举）
     * @param missionActive  任务执行中（flightId 非空）
     * @param missionPaused  任务暂停标记
     * @param missionFailed  任务失败标记（W1 无来源，W2 场景引擎注入故障时填充）
     * @param charging       在舱充电中（droneChargeState=1）
     */
    public record Input(boolean powered, boolean online, boolean mqttConnected,
                        boolean droneInDock, boolean droneActivated, int droneModeCode,
                        boolean missionActive, boolean missionPaused, boolean missionFailed,
                        boolean charging) {
    }

    /**
     * 映射结果。
     *
     * @param state      推导状态
     * @param documented 是否命中文档化组合（false=调用方发 M-3 诊断码）
     */
    public record Mapping(LifecycleState state, boolean documented) {
    }

    /**
     * 执行映射（优先级见类注释）。
     *
     * @param in 输入快照
     * @return 状态与"是否命中文档化组合"
     */
    public static Mapping map(Input in) {
        if (!in.powered()) {
            return documented(LifecycleState.CREATED);
        }
        if (!in.mqttConnected()) {
            return documented(LifecycleState.NETWORK_LOST);
        }
        if (in.missionFailed()) {
            return documented(LifecycleState.FAILED);
        }
        if (in.missionPaused()) {
            return documented(LifecycleState.PAUSED);
        }
        if (!in.online()) {
            return documented(LifecycleState.OFFLINE);
        }
        return byDroneMode(in);
    }

    /** 在线后的飞行器模式细分（§2.5.2 推导表下半段） */
    private static Mapping byDroneMode(Input in) {
        return switch (in.droneModeCode()) {
            // 待机：在舱充电中 → CHARGING；在舱未充电 → READY；舱外 → LANDED（如失联落地未归舱）
            case 0 -> {
                if (!in.droneInDock()) {
                    yield documented(LifecycleState.LANDED);
                }
                yield documented(in.charging() ? LifecycleState.CHARGING : LifecycleState.READY);
            }
            // 起飞准备（1/2）：已激活 → ARMED；未激活却处于起飞准备模式 → 未命中
            case 1, 2 -> in.droneActivated()
                    ? documented(LifecycleState.ARMED)
                    : undocumented(LifecycleState.ONLINE);
            // 飞行中模式：3=手动飞行, 6=全景拍照, 7=智能跟随, 8=兴趣点环绕, 15=APAS, 16=虚拟摇杆, 17=指令飞行
            case 3, 6, 7, 8, 15, 16, 17 -> documented(LifecycleState.FLYING);
            // 4=自动起飞
            case 4 -> documented(LifecycleState.TAKING_OFF);
            // 5=航线飞行：有任务 → MISSION；无任务 → FLYING
            case 5 -> documented(in.missionActive() ? LifecycleState.MISSION : LifecycleState.FLYING);
            // 9=自动返航（13 从方案表；见类注释 M-2 推断项）
            case 9, 13 -> documented(LifecycleState.RETURNING);
            // 10=自动降落, 11=强制降落, 12=三桨叶降落
            case 10, 11, 12 -> documented(LifecycleState.LANDING);
            // 14=飞行器未连接（异常态）
            case 14 -> documented(LifecycleState.OFFLINE);
            // 未知模式码：兜底 ONLINE 并标记未命中（调用方发 M-3）
            default -> undocumented(LifecycleState.ONLINE);
        };
    }

    private static Mapping documented(LifecycleState state) {
        return new Mapping(state, true);
    }

    private static Mapping undocumented(LifecycleState fallback) {
        return new Mapping(fallback, false);
    }
}
