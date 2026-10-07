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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import ltd.cdmi.hivemind.simulator.adapter.dji.DjiFlightIntentHandler;
import ltd.cdmi.hivemind.simulator.adapter.dji.DjiFlightIntentHandler.IntentResult;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.core.engine.flight.FlightIntent;
import ltd.cdmi.hivemind.simulator.core.engine.telemetry.TelemetryEngine;
import ltd.cdmi.hivemind.simulator.core.model.DeviceVendor;
import ltd.cdmi.hivemind.simulator.core.model.UnifiedDevice;

/**
 * 统一设备意图 REST 端点（实施方案 §2.9.2）——W2 场景引擎的主要下行通道。
 *
 * <p>{@code POST /api/unified/devices/{deviceId}/intents}，body 为 {@link FlightIntent} JSON
 * （以 {@code type} 属性区分意图类型，如 {@code {"type":"Takeoff","targetAltitude":100}}）。</p>
 *
 * <p>与 MQTT 协议路径并存：意图经 {@code adapter/dji/DjiFlightIntentHandler} 转换后调用
 * 与协议驱动完全相同的内部执行体（行为一致）。HTTP 语义：设备不存在返回 404；
 * 意图被拒绝（W1 未接入/参数非法）返回 200 + {@code accepted=false}。</p>
 */
@Tag(name = "统一设备意图", description = "V3.0 意图下行通道（§2.9.2），body 为 FlightIntent JSON（type 字段区分）")
@RestController
@RequestMapping("/api/unified/devices")
public class UnifiedIntentController {

    private final TelemetryEngine telemetryEngine;
    private final RuntimeConfig runtimeConfig;
    private final DjiFlightIntentHandler intentHandler;

    @Autowired
    public UnifiedIntentController(TelemetryEngine telemetryEngine, RuntimeConfig runtimeConfig,
                                   DjiFlightIntentHandler intentHandler) {
        this.telemetryEngine = telemetryEngine;
        this.runtimeConfig = runtimeConfig;
        this.intentHandler = intentHandler;
    }

    /** 下发飞行意图并同步执行（简化语义：内部入口立即生效，异步事件由既有调度器推进） */
    @PostMapping("/{deviceId}/intents")
    public ResponseEntity<IntentResult> postIntent(@PathVariable String deviceId,
                                                   @RequestBody FlightIntent intent) {
        if (!deviceKnown(deviceId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(IntentResult.rejected("设备不存在或未配置: " + deviceId));
        }
        return ResponseEntity.ok(intentHandler.handle(intent));
    }

    /**
     * 设备校验：遥测快照命中，或与运行时网关 SN 合成的设备 id 一致
     * （OSD 首帧投影前的启动窗口期，设备已配置但尚无快照）。
     */
    private boolean deviceKnown(String deviceId) {
        if (telemetryEngine.find(deviceId) != null) {
            return true;
        }
        return deviceId.equals(UnifiedDevice.deviceIdOf(DeviceVendor.DJI, runtimeConfig.getGatewaySn()));
    }
}
