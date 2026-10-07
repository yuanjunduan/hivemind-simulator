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

package ltd.cdmi.hivemind.simulator.core.engine.flight;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞行引擎速度参数（绑定 application.yml 中 simulation.flight.*，实施方案 §2.6.2/§2.6.3）。
 * <p>所有速度常量参数化，缺省值与 v1.4.6 硬编码现状<b>逐位一致</b>
 * （10.0/3.0/5.0/60/15/0.5/5.0——默认值不变是 REALTIME 等价验收的一部分，红线 2）；
 * 配置缺失/非法值不阻断启动，回落缺省。</p>
 *
 * @param horizontalSpeedMps    航点/航线插值水平速度（m/s，缺省 10.0）
 * @param verticalSpeedMps      航点/航线插值垂直速度（m/s，缺省 3.0）
 * @param maxHorizontalSpeedMps DRC 杆量满杆水平速度（m/s，缺省 10.0）
 * @param maxVerticalSpeedMps   DRC 杆量满杆垂直速度（m/s，缺省 5.0）
 * @param yawRateDegPerSec      DRC 满杆偏航角速度（度/秒，缺省 60）
 * @param attitudeMaxDeg        DRC 满杆姿态倾角（度，缺省 15）
 * @param dtCapSeconds          杆量积分步长上限（秒，缺省 0.5，防断流恢复瞬移）
 * @param fallbackSpeedMps      flyto 指令速度缺省兜底（m/s，缺省 5.0）
 */
@ConfigurationProperties(prefix = "simulation.flight")
public record FlightProperties(
        Double horizontalSpeedMps,
        Double verticalSpeedMps,
        Double maxHorizontalSpeedMps,
        Double maxVerticalSpeedMps,
        Double yawRateDegPerSec,
        Double attitudeMaxDeg,
        Double dtCapSeconds,
        Double fallbackSpeedMps) {

    public FlightProperties {
        if (!isPositive(horizontalSpeedMps)) {
            horizontalSpeedMps = 10.0;
        }
        if (!isPositive(verticalSpeedMps)) {
            verticalSpeedMps = 3.0;
        }
        if (!isPositive(maxHorizontalSpeedMps)) {
            maxHorizontalSpeedMps = 10.0;
        }
        if (!isPositive(maxVerticalSpeedMps)) {
            maxVerticalSpeedMps = 5.0;
        }
        if (!isPositive(yawRateDegPerSec)) {
            yawRateDegPerSec = 60.0;
        }
        if (!isPositive(attitudeMaxDeg)) {
            attitudeMaxDeg = 15.0;
        }
        if (!isPositive(dtCapSeconds)) {
            dtCapSeconds = 0.5;
        }
        if (!isPositive(fallbackSpeedMps)) {
            fallbackSpeedMps = 5.0;
        }
    }

    private static boolean isPositive(Double value) {
        return value != null && value > 0 && Double.isFinite(value);
    }
}
