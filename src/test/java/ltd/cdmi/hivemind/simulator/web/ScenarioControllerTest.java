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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ltd.cdmi.hivemind.simulator.core.clock.ClockProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.core.clock.SimulationClockImpl;
import ltd.cdmi.hivemind.simulator.core.scenario.ScenarioEngineImpl;

/**
 * 场景 REST 端点单测（TC-SCN-020~024，T2.3）。
 *
 * <p>真实 {@link ScenarioEngineImpl}（手动 tick 时钟 + no-op sink）+ standalone MockMvc，
 * 验证加载/启动/停止/状态/列表的 HTTP 语义与错误映射。</p>
 */
class ScenarioControllerTest {

    private static final String VALID_YAML = "scenario:\n  id: ctrl-test\n  version: 1\n"
            + "  description: 控制器单测\n"
            + "devices:\n  - id: drone-001\n    vendor: DJI\nevents:\n"
            + "  - { at: 200ms, action: return_home }\n"
            + "  - { at: 400ms, action: gps_loss }\n";

    private final ObjectMapper mapper = new ObjectMapper();

    private MockMvc mockMvc;
    private SimulationClockImpl clock;
    private ClockScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new SimulationClockImpl(1.0);
        scheduler = new ClockScheduler(clock, new ClockProperties(null, null, null));
        ScenarioEngineImpl engine = new ScenarioEngineImpl(scheduler, (scenario, event) -> {
            // no-op sink：REST 层测试不关心动作执行
        });
        mockMvc = MockMvcBuilders.standaloneSetup(new ScenarioController(engine)).build();
    }

    private String loadBody(String yaml, String path) {
        ObjectNode node = mapper.createObjectNode();
        if (yaml != null) {
            node.put("yaml", yaml);
        }
        if (path != null) {
            node.put("path", path);
        }
        return node.toString();
    }

    @DisplayName("TC-SCN-020：POST /load —— yaml 文本加载成功返回摘要；非法场景 400 + 错误列表")
    @Test
    void loadYamlText() throws Exception {
        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody(VALID_YAML, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioId").value("ctrl-test"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.description").value("控制器单测"))
                .andExpect(jsonPath("$.deviceCount").value(1))
                .andExpect(jsonPath("$.eventCount").value(2));

        // 非法：未知 action + at 缺单位 → 400 且错误聚合返回
        String bad = "scenario:\n  id: bad-1\n  version: 1\n"
                + "devices:\n  - id: drone-001\n    vendor: DJI\nevents:\n"
                + "  - { at: 10, action: return_home }\n"
                + "  - { at: 10s, action: fly_to_moon }\n";
        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody(bad, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0]").value(containsString("at")))
                .andExpect(jsonPath("$.errors[1]").value(containsString("fly_to_moon")));

        // 请求体两字段都空 → 400
        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("yaml")));
    }

    @DisplayName("TC-SCN-021：start/stop/status 链路 —— 状态可查、stop 后 STOPPED、重复调用幂等")
    @Test
    void lifecyleEndpoints() throws Exception {
        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody(VALID_YAML, null)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/scenarios/ctrl-test/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LOADED"))
                .andExpect(jsonPath("$.totalEvents").value(2))
                .andExpect(jsonPath("$.firedEvents").value(0));

        mockMvc.perform(post("/api/scenarios/ctrl-test/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        // 幂等：重复 start 返回 RUNNING
        mockMvc.perform(post("/api/scenarios/ctrl-test/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));

        mockMvc.perform(post("/api/scenarios/ctrl-test/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
        mockMvc.perform(post("/api/scenarios/ctrl-test/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
    }

    @DisplayName("TC-SCN-022：未知场景 id —— start/stop/status 一律 404 + error")
    @Test
    void unknownScenarioReturns404() throws Exception {
        mockMvc.perform(post("/api/scenarios/ghost/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("ghost")));
        mockMvc.perform(post("/api/scenarios/ghost/stop"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/scenarios/ghost/status"))
                .andExpect(status().isNotFound());
    }

    @DisplayName("TC-SCN-023：GET /api/scenarios —— 列表返回全部已加载场景及状态")
    @Test
    void listLoadedScenarios() throws Exception {
        mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody(VALID_YAML, null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody("scenario:\n  id: second\n  version: 1\n"
                                + "devices:\n  - id: drone-002\n    vendor: SIKONG\nevents: []\n", null)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].scenarioId").value("ctrl-test"))
                .andExpect(jsonPath("$[1].scenarioId").value("second"))
                .andExpect(jsonPath("$[1].state").value("LOADED"));
    }

    @DisplayName("TC-SCN-024：POST /load 按 path 加载文件；文件不存在 400；同 id 重载复位 LOADED")
    @Test
    void loadByPathAndReload(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("scene.yaml");
        Files.writeString(file, VALID_YAML);

        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody(null, file.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioId").value("ctrl-test"));

        // 启动后重载同 id → 复位 LOADED、计数清零
        mockMvc.perform(post("/api/scenarios/ctrl-test/start"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody(null, file.toString())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/scenarios/ctrl-test/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LOADED"))
                .andExpect(jsonPath("$.firedEvents").value(0));

        // 文件不存在 → 400
        mockMvc.perform(post("/api/scenarios/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loadBody(null, tempDir.resolve("missing.yaml").toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)));
    }
}
