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

package ltd.cdmi.hivemind.simulator.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.core.clock.SimulationClock;

/**
 * 仿真时钟控制 REST 端点（ADR-4 时间加速的运行时通道）。
 *
 * <p>{@code GET /api/unified/clock} 返回当前时钟快照（倍率/逻辑时间/墙钟度量）；
 * {@code POST /api/unified/clock/speed} 运行时热切换倍率（body {@code {"speed":10.0}}）。</p>
 *
 * <p>消费方：W2 场景引擎（场景指令按需切倍率）、E2E 编排器、web-console 控制面板（W5）、
 * 以及 ADR-5 真实平台联调期的人工/脚本操作——避免"改 yml + 重启"的过渡期切换方式。
 * 热切换语义由 {@link SimulationClock#setSpeed(double)} 保证：先结算旧倍率下已流逝的
 * 逻辑时间再重锚（rebase），切换瞬间逻辑时间连续（不跳变、不回退）。</p>
 *
 * <p>REALTIME（speed=1）行为与 v1.4.6 逐位等价（红线 2）；本端点只改倍率，
 * 报文封顶（{@code simulation.mqtt.publish-rate-cap-hz}）仍由配置决定、运行时不改。
 * 时间字段以 ISO-8601 字符串返回（与 WS 遥测推送的 occurredAt 序列化一致）。</p>
 */
@Tag(name = "仿真时钟", description = "V3.0 时钟状态查询与倍率热切换（ADR-4），场景引擎/联调/控制台消费")
@RestController
@RequestMapping("/api/unified/clock")
public class UnifiedClockController {

    private final ClockScheduler clockScheduler;

    @Autowired
    public UnifiedClockController(ClockScheduler clockScheduler) {
        this.clockScheduler = clockScheduler;
    }

    /** 当前时钟快照（倍率 + 逻辑时间 + 墙钟度量） */
    @GetMapping
    public ClockStatus status() {
        return ClockStatus.of(clockScheduler.clock());
    }

    /**
     * 运行时切换倍率（热切换，幂等：与当前倍率相同则直接返回）。
     *
     * @param request 倍率请求体（speed 必须为正有限值）
     * @return 200 + 切换后的时钟快照；非法值 400（不改变当前倍率）
     */
    @PostMapping("/speed")
    public ResponseEntity<?> setSpeed(@RequestBody SpeedRequest request) {
        try {
            clockScheduler.clock().setSpeed(request.speed());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(ClockStatus.of(clockScheduler.clock()));
    }

    /** 倍率切换请求体（speed 必须为正有限值，如 1.0=REALTIME、10.0=10x 加速） */
    public record SpeedRequest(double speed) {
    }

    /**
     * 时钟状态快照（REST 响应体）。
     *
     * @param speed            当前倍率（1.0 = REALTIME）
     * @param now              当前逻辑时间（ISO-8601）
     * @param elapsedMillis    自启动以来的逻辑毫秒数（受倍率影响）
     * @param wallStartedAt    时钟启动的真实时间（ISO-8601）
     * @param wallUptimeMillis 真实运行时长（不受倍率影响，压测指标用）
     */
    public record ClockStatus(double speed, String now, long elapsedMillis,
                              String wallStartedAt, long wallUptimeMillis) {

        /** 从时钟接口构造快照（时间转 ISO-8601 字符串，与 WS 推送格式一致） */
        static ClockStatus of(SimulationClock clock) {
            SimulationClock.WallClockMetrics wall = clock.wallMetrics();
            Instant logicalNow = clock.now();
            return new ClockStatus(clock.speed(), logicalNow.toString(), clock.elapsedMillis(),
                    wall.wallStartedAt().toString(), wall.wallUptimeMillis());
        }
    }
}
