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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ltd.cdmi.hivemind.simulator.core.clock.ClockProperties;
import ltd.cdmi.hivemind.simulator.core.clock.ClockScheduler;
import ltd.cdmi.hivemind.simulator.core.clock.SimulationClockImpl;

/**
 * UnifiedClockController 单测（TC-CORE-092，ADR-4 时钟运行时通道）。
 *
 * <p>验证 REST 契约：{@code GET /api/unified/clock} 状态查询；
 * {@code POST /api/unified/clock/speed} 倍率热切换（成功 200 / 非法值 400 且不改变倍率）。
 * 使用真实 {@link SimulationClockImpl} 验证端到端切换生效（不启动后台 tick 线程）。</p>
 */
class UnifiedClockControllerTest {

    private SimulationClockImpl clock;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clock = new SimulationClockImpl(1.0);
        ClockScheduler scheduler = new ClockScheduler(clock, new ClockProperties(null, null, null));
        mockMvc = MockMvcBuilders.standaloneSetup(new UnifiedClockController(scheduler)).build();
    }

    @DisplayName("TC-CORE-092：GET 时钟快照（REALTIME 倍率 1.0 + 逻辑时间/墙钟度量 ISO-8601）")
    @Test
    void statusReturnsCurrentSnapshot() throws Exception {
        mockMvc.perform(get("/api/unified/clock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speed").value(1.0))
                .andExpect(jsonPath("$.now").isString())
                .andExpect(jsonPath("$.wallStartedAt").isString())
                .andExpect(jsonPath("$.elapsedMillis").isNumber())
                .andExpect(jsonPath("$.wallUptimeMillis").isNumber());
    }

    @DisplayName("TC-CORE-092：POST speed=10 热切换成功（返回新倍率且时钟真实生效）")
    @Test
    void setSpeedSwitchesClock() throws Exception {
        mockMvc.perform(post("/api/unified/clock/speed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speed\":10.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speed").value(10.0));

        assertEquals(10.0, clock.speed());
    }

    @DisplayName("TC-CORE-092：POST 非法值（0/负数）→ 400 且倍率保持不变")
    @Test
    void invalidSpeedRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/unified/clock/speed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speed\":0.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());

        mockMvc.perform(post("/api/unified/clock/speed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speed\":-5.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());

        assertEquals(1.0, clock.speed());
    }

    @DisplayName("TC-CORE-092：切换后 GET 快照与新倍率一致（热切换链路闭环）")
    @Test
    void getReflectsSwitchedSpeed() throws Exception {
        mockMvc.perform(post("/api/unified/clock/speed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"speed\":2.5}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/unified/clock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speed").value(2.5));
    }
}
