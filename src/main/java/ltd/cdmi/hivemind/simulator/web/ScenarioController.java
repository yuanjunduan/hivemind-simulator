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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioDefinition;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioEngine;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioStatus;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioValidationException;

/**
 * 场景引擎 REST 端点（实施方案 §4.2）。
 *
 * <ul>
 *   <li>{@code POST /api/scenarios/load} — 加载场景（body 二选一：{@code {"yaml":"..."}} 文本
 *       或 {@code {"path":"scenarios/..."}} 文件路径），校验失败 400 + 全部错误项；</li>
 *   <li>{@code GET  /api/scenarios} — 全部已加载场景状态；</li>
 *   <li>{@code POST /api/scenarios/{id}/start} — 启动（幂等）；事件按逻辑时间触发；</li>
 *   <li>{@code POST /api/scenarios/{id}/stop} — 停止（幂等）+ 注入清理；</li>
 *   <li>{@code GET  /api/scenarios/{id}/status} — 运行状态（LOADED/RUNNING/FINISHED/STOPPED）。</li>
 * </ul>
 *
 * <p>HTTP 语义：场景未加载（start/stop/status）返回 404；校验失败 400（fail-fast，不静默）。</p>
 */
@Tag(name = "场景", description = "V3.0 场景引擎 REST（§4.2）：加载/启动/停止/状态")
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioEngine scenarioEngine;

    @Autowired
    public ScenarioController(ScenarioEngine scenarioEngine) {
        this.scenarioEngine = scenarioEngine;
    }

    /** 全部已加载场景状态 */
    @GetMapping
    public List<ScenarioStatus> list() {
        return scenarioEngine.list();
    }

    /**
     * 加载场景（yaml 文本优先；否则按 path 读取文件）。
     * 同 id 重复加载：替换旧实例并复位为 LOADED。
     */
    @PostMapping("/load")
    public ResponseEntity<?> load(@RequestBody LoadRequest request) {
        try {
            ScenarioDefinition def;
            if (request.yaml() != null && !request.yaml().isBlank()) {
                def = scenarioEngine.loadText(request.yaml());
            } else if (request.path() != null && !request.path().isBlank()) {
                def = scenarioEngine.load(Path.of(request.path()));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "必须提供 yaml（文本）或 path（文件路径）之一"));
            }
            return ResponseEntity.ok(ScenarioLoadResult.of(def));
        } catch (ScenarioValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("errors", e.errors()));
        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "path 非法: " + e.getInput()));
        }
    }

    /** 启动场景（幂等） */
    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id) {
        try {
            scenarioEngine.start(id);
            return ResponseEntity.ok(scenarioEngine.status(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** 停止场景（幂等）+ 注入清理 */
    @PostMapping("/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable String id) {
        try {
            scenarioEngine.stop(id);
            return ResponseEntity.ok(scenarioEngine.status(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** 场景运行状态 */
    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        try {
            return ResponseEntity.ok(scenarioEngine.status(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** 加载请求体（yaml 文本与 path 二选一，yaml 优先） */
    public record LoadRequest(String yaml, String path) {
    }

    /**
     * 加载结果摘要（不返回完整定义——事件参数映射对调用方无意义）。
     *
     * @param scenarioId  场景 ID
     * @param version     Schema 版本
     * @param description 场景描述（可空）
     * @param deviceCount 设备数
     * @param eventCount  事件数
     */
    public record ScenarioLoadResult(String scenarioId, int version, String description,
                                     int deviceCount, int eventCount) {

        static ScenarioLoadResult of(ScenarioDefinition def) {
            return new ScenarioLoadResult(def.id(), def.version(), def.description(),
                    def.devices().size(), def.events().size());
        }
    }
}
