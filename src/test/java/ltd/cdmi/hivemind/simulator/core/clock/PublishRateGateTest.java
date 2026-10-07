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

package ltd.cdmi.hivemind.simulator.core.clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PublishRateGate 单测（TC-CORE-090，T1.10 / 可行性方案 §4.4 ADR-5）。
 *
 * <p>确定性断言：注入可控物理时钟（假 nanoTime），验证"频率封顶 + REALTIME 恒定放行"
 * 两条核心语义，不依赖真实时间。</p>
 */
class PublishRateGateTest {

    /** 可控物理时钟读数（纳秒） */
    private long fakeNowNanos;

    private PublishRateGate newGate(double maxHz) {
        return new PublishRateGate(maxHz, () -> fakeNowNanos);
    }

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    @DisplayName("TC-CORE-090：REALTIME（倍率 ≤1）恒定放行——闸门不改变既有行为")
    @Test
    void realtimeAlwaysAllows() {
        PublishRateGate gate = newGate(2.0);
        for (int i = 0; i < 20; i++) {
            fakeNowNanos += ms(1); // 高频调用也无间隔约束
            assertTrue(gate.tryAcquire(1.0), "REALTIME 第 " + i + " 次调用应放行");
        }
        assertTrue(gate.tryAcquire(0.5), "倍率 <1 同样放行（防御性语义 ≤1.0）");
    }

    @DisplayName("TC-CORE-090：加速（倍率 >1）按物理间隔 1/capHz 放行，间隔内拒绝")
    @Test
    void acceleratedAllowsOnlyAfterPhysicalInterval() {
        PublishRateGate gate = newGate(2.0); // 最小间隔 500ms
        assertTrue(gate.tryAcquire(10.0), "首次调用应放行");

        fakeNowNanos = ms(100);
        assertFalse(gate.tryAcquire(10.0), "100ms < 500ms 应拒绝");

        fakeNowNanos = ms(499);
        assertFalse(gate.tryAcquire(10.0), "499ms < 500ms 应拒绝");

        fakeNowNanos = ms(500);
        assertTrue(gate.tryAcquire(10.0), "恰好 500ms 应放行");

        fakeNowNanos = ms(999);
        assertFalse(gate.tryAcquire(10.0), "距上次放行 499ms 应拒绝");

        fakeNowNanos = ms(1000);
        assertTrue(gate.tryAcquire(10.0), "距上次放行 500ms 应放行");
    }

    @DisplayName("TC-CORE-090：10x 加速 + cap 2Hz 下 OSD 触发流（物理 200ms 步进）实际放行 ≤2Hz")
    @Test
    void acceleratedOsdCadenceStaysWithinCap() {
        PublishRateGate gate = newGate(2.0);
        // 10x 加速下 OSD 逻辑周期 2s → 物理每 200ms 触发一轮；统计 10 物理秒的放行次数
        int allowed = 0;
        for (long t = 0; t <= ms(10_000); t += ms(200)) {
            fakeNowNanos = t;
            if (gate.tryAcquire(10.0)) {
                allowed++;
            }
        }
        // 容量上限 = 10s × 2Hz + 首帧 1 = 21；实际放行间隔被 200ms 网格量化后为 600ms（17 次）
        assertTrue(allowed <= 21, "放行次数不得超过频率封顶（≤2Hz），实际 " + allowed);
        assertTrue(allowed >= 15, "封顶不应导致过度欠采样（≥1.5Hz），实际 " + allowed);
    }

    @DisplayName("TC-CORE-090：REALTIME→加速热切换时以最近一次放行为基准（不突刺放行）")
    @Test
    void switchFromRealtimeToAcceleratedKeepsLastAcquireBaseline() {
        PublishRateGate gate = newGate(2.0);
        fakeNowNanos = ms(10_000);
        assertTrue(gate.tryAcquire(1.0), "REALTIME 放行并更新基准时刻");

        fakeNowNanos = ms(10_100);
        assertFalse(gate.tryAcquire(10.0), "切换后距最近放行 100ms 应拒绝");

        fakeNowNanos = ms(10_500);
        assertTrue(gate.tryAcquire(10.0), "距最近放行 500ms 应放行");
    }

    @DisplayName("TC-CORE-090：非法封顶配置兜底为缺省 2Hz")
    @Test
    void invalidMaxHzFallsBackToDefault() {
        PublishRateGate gate = newGate(Double.NaN);
        assertTrue(gate.tryAcquire(10.0), "首次调用应放行");

        fakeNowNanos = ms(499);
        assertFalse(gate.tryAcquire(10.0), "NaN 兜底为 2Hz（500ms 间隔）");

        fakeNowNanos = ms(500);
        assertTrue(gate.tryAcquire(10.0), "距上次放行 500ms 应放行");
    }
}
