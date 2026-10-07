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

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import ltd.cdmi.hivemind.simulator.core.engine.telemetry.TelemetryEngine;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * 统一设备模型 REST 端点（实施方案 §2.8）。
 *
 * <p>面向 web-console 读取 V3.0 统一快照（与现有 {@code /api/state} 并存，
 * 旧控制台行为不受影响）：</p>
 * <ul>
 *   <li>{@code GET /api/unified/devices} → 全部设备快照（W1 单机；W3 多实例聚合后语义不变）</li>
 *   <li>{@code GET /api/unified/devices/{deviceId}} → 单设备快照（deviceId 约定 {@code vendor:deviceSn}）</li>
 * </ul>
 *
 * <p>实时流式消费走 WS {@code /ws/simulator/telemetry}（{@code TelemetryWebSocketHandler}）。</p>
 */
@Tag(name = "统一设备", description = "V3.0 统一设备模型快照（遥测引擎最新值），deviceId 约定 vendor:deviceSn")
@RestController
@RequestMapping("/api/unified/devices")
public class UnifiedDeviceController {

    private final TelemetryEngine telemetryEngine;

    public UnifiedDeviceController(TelemetryEngine telemetryEngine) {
        this.telemetryEngine = telemetryEngine;
    }

    /** 全部设备快照（尚无投影时为空数组） */
    @GetMapping
    public List<UnifiedDevice> list() {
        return telemetryEngine.all();
    }

    /** 单设备快照（deviceId 形如 {@code DJI:1581F5BKD22A000A0001}；未匹配返回 404） */
    @GetMapping("/{deviceId}")
    public ResponseEntity<UnifiedDevice> detail(@PathVariable String deviceId) {
        UnifiedDevice device = telemetryEngine.find(deviceId);
        return device != null ? ResponseEntity.ok(device) : ResponseEntity.notFound().build();
    }
}
