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

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * MQTT 报文频率闸门（可行性方案 §4.4 ADR-5：加速模式下"频率封顶 + 逻辑时间跳进"）。
 *
 * <p>目的：时间加速时限制周期性批量报文的实际发送频率，避免平台被高频报文冲击；
 * 报文之间以"逻辑时间跳进"携带最新状态（相邻报文间的状态是跳变而非连续）。</p>
 *
 * <p>语义：</p>
 * <ul>
 *   <li>REALTIME（当前倍率 &le;1.0）：恒定放行——与改造前行为逐位一致（红线 2）；</li>
 *   <li>加速（倍率 &gt;1.0）：同一闸门两次放行之间的<b>物理时间</b>间隔不小于
 *       {@code 1/maxHz} 秒，间隔内到达的调用返回 false（调用方跳过本轮发送，下一轮重试）；</li>
 *   <li>仅用于周期性报文路径（如 DeviceSimulator 整轮 OSD/DRC 批推送）；离散事件
 *       （指令触发推送、flighttask_progress 等）不经过本闸门，不做降采样（ADR-5）。</li>
 * </ul>
 *
 * <p>线程安全：{@link #tryAcquire(double)} 以 synchronized 串行化，可被调度线程与测试线程并发调用。</p>
 */
public final class PublishRateGate {

    /** 缺省封顶频率（Hz），与 {@link ClockProperties.Mqtt} 兜底一致 */
    private static final double DEFAULT_MAX_HZ = 2.0;

    /** 两次放行之间的最小物理间隔（纳秒） */
    private final long minIntervalNanos;

    /** 物理时钟源（生产=System::nanoTime；测试=可控假时钟） */
    private final LongSupplier nanoTime;

    /** 最近一次放行时的物理时刻（纳秒；Long.MIN_VALUE=尚未放行过） */
    private long lastAcquiredNanos = Long.MIN_VALUE;

    /** 生产构造器：系统单调时钟（不受系统时间调整影响）。 */
    public PublishRateGate(double maxHz) {
        this(maxHz, System::nanoTime);
    }

    /** 测试构造器：注入可控物理时钟以做确定性断言（包内可见）。 */
    PublishRateGate(double maxHz, LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        double effectiveMaxHz = (maxHz > 0 && Double.isFinite(maxHz)) ? maxHz : DEFAULT_MAX_HZ;
        this.minIntervalNanos = (long) (1_000_000_000.0 / effectiveMaxHz);
    }

    /**
     * 尝试获取一次发送许可（物理时间维度判定）。
     *
     * @param currentSpeed 当前时钟倍率（&le;1.0 恒定放行，保证 REALTIME 行为等价）
     * @return true=允许发送本轮报文；false=处于封顶间隔内，调用方跳过本轮
     */
    public synchronized boolean tryAcquire(double currentSpeed) {
        long now = nanoTime.getAsLong();
        if (currentSpeed <= 1.0) {
            lastAcquiredNanos = now;
            return true;
        }
        if (lastAcquiredNanos != Long.MIN_VALUE && now - lastAcquiredNanos < minIntervalNanos) {
            return false;
        }
        lastAcquiredNanos = now;
        return true;
    }
}
