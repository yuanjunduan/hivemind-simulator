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

package ltd.cdmi.hivemind.simulator.core.fault;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ltd.cdmi.hivemind.simulator.core.event.EventBus;
import ltd.cdmi.hivemind.simulator.core.event.FaultInjectedEvent;

/**
 * 媒体故障注入器（T2.4）——推流与上传路径的故障门。
 *
 * <ul>
 *   <li>{@code video_stream_stop}：置推流停止门——{@code FfmpegWhipPusher.startPush} 拒绝新推流
 *       （已活跃推流的停止由场景分发器调用 {@code stopAll} 完成）；</li>
 *   <li>{@code video_stream_recover}：解除推流停止门（不主动重启推流——真实语义中推流由平台命令触发）；</li>
 *   <li>{@code media_upload_fail}：按 {@code percent} 概率使 {@code MediaUploader.upload} 失败。</li>
 * </ul>
 */
@Component
public class MediaFaultInjector implements FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(MediaFaultInjector.class);

    private final EventBus eventBus;

    /** 推流停止门（true=拒绝新推流） */
    private final AtomicBoolean videoStopped = new AtomicBoolean(false);
    /** 上传失败率（0-100） */
    private final AtomicLong uploadFailPercent = new AtomicLong(0);

    public MediaFaultInjector(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public String name() {
        return "media";
    }

    @Override
    public void inject(String action, String deviceId, Map<String, Object> params) {
        switch (action) {
            case "video_stream_stop" -> videoStopped.set(true);
            case "video_stream_recover" -> videoStopped.set(false);
            case "media_upload_fail" ->
                    uploadFailPercent.set(Math.min(100, Math.max(0, asLong(params.get("percent")))));
            default -> {
                log.warn("MediaFaultInjector 收到未支持的 action: {}", action);
                return;
            }
        }
        log.info("[故障注入-媒体] action={}, device={}, videoStopped={}, uploadFailPercent={}",
                action, deviceId, videoStopped.get(), uploadFailPercent.get());
        eventBus.publish(new FaultInjectedEvent(deviceId, eventBus.logicalNow(), action, params, "media"));
    }

    /** 推流是否被停止门阻断 */
    public boolean isVideoStopped() {
        return videoStopped.get();
    }

    /** 本次上传是否应失败（按 percent 概率） */
    public boolean shouldFailUpload() {
        long percent = uploadFailPercent.get();
        return percent > 0 && ThreadLocalRandom.current().nextLong(100) < percent;
    }

    @Override
    public void clear() {
        videoStopped.set(false);
        uploadFailPercent.set(0);
    }

    /** 快照（供状态查询与测试断言） */
    public Snapshot snapshot() {
        return new Snapshot(videoStopped.get(), uploadFailPercent.get());
    }

    /** 注入状态快照 */
    public record Snapshot(boolean videoStopped, long uploadFailPercent) {
    }

    private static long asLong(Object raw) {
        return raw instanceof Number n ? n.longValue() : 0L;
    }
}
