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

package ltd.cdmi.hivemind.simulator.core.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 场景资产库校验测试（TC-SCN-030~031，T2.5）。
 *
 * <p>递归加载 {@code scenarios/} 下全部 YAML 资产，确保与 Schema v1 始终一致
 * （资产即文档，提交前被 CI 强制校验）；并抽查关键资产的动作/时钟语义。</p>
 */
class ScenarioAssetsTest {

    /** 相对项目根（surefire 工作目录为 ${basedir}） */
    private static final Path SCENARIOS_DIR = Path.of("scenarios");

    private final ScenarioParser parser = new ScenarioParser();

    @DisplayName("TC-SCN-030：全部场景资产通过 Schema v1 校验（normal×3/fault×5/swarm×1）")
    @Test
    void allAssetsParseClean() throws IOException {
        List<Path> yamls;
        try (Stream<Path> s = Files.walk(SCENARIOS_DIR)) {
            yamls = s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertTrue(yamls.size() >= 9, "资产数应不少于 9（含 swarm 骨架）: " + yamls);

        for (Path file : yamls) {
            ScenarioDefinition def = parser.parse(file); // 校验失败即抛 ScenarioValidationException
            assertFalse(def.id().isBlank(), file + " id 非空");
            assertEquals(1, def.version(), file + " version=1");
            assertFalse(def.devices().isEmpty(), file + " 至少一个设备");
        }
    }

    @DisplayName("TC-SCN-031：关键资产语义抽查（电量阶梯 / 长任务时钟覆盖 / 媒体故障恢复对）")
    @Test
    void keyAssetsSemantics() throws IOException {
        // 低电量：25→18→8 阶梯 + 5x 加速
        ScenarioDefinition battery = parser.parse(SCENARIOS_DIR.resolve("fault/battery-low.yaml"));
        assertEquals("accelerated", battery.clock().mode());
        assertEquals(5.0, battery.clock().speed());
        List<Double> batteryValues = battery.events().stream()
                .filter(e -> "set_battery".equals(e.action()))
                .map(e -> ((Number) e.params().get("value")).doubleValue())
                .toList();
        assertEquals(List.of(25.0, 18.0, 8.0), batteryValues);

        // 长任务：10x + 末事件 30m 逻辑（1800000ms）+ land
        ScenarioDefinition longMission =
                parser.parse(SCENARIOS_DIR.resolve("normal/long-mission-accelerated.yaml"));
        assertEquals(10.0, longMission.clock().speed());
        ScenarioDefinition.EventSpec last =
                longMission.events().get(longMission.events().size() - 1);
        assertEquals(1_800_000L, last.atMillis(), "30m = 1800000ms");
        assertEquals("land", last.action());

        // 媒体故障：推流停止/恢复成对 + 上传失败率 100→0 恢复
        ScenarioDefinition media = parser.parse(SCENARIOS_DIR.resolve("fault/media-fail.yaml"));
        List<String> actions = media.events().stream()
                .map(ScenarioDefinition.EventSpec::action)
                .toList();
        assertTrue(actions.contains("video_stream_stop"));
        assertTrue(actions.contains("video_stream_recover"));
        List<Double> failPercents = media.events().stream()
                .filter(e -> "media_upload_fail".equals(e.action()))
                .map(e -> ((Number) e.params().get("percent")).doubleValue())
                .toList();
        assertEquals(List.of(100.0, 0.0), failPercents);

        // 双机骨架：两设备可解析（多机语义 W4）
        ScenarioDefinition swarm =
                parser.parse(SCENARIOS_DIR.resolve("swarm/two-drones-skeleton.yaml"));
        assertEquals(2, swarm.devices().size());
    }
}
