# 场景资产库（Schema v1）

V3.0 改造的声明式场景资产：YAML 描述设备与时间轴事件，由场景引擎按**逻辑时间**执行
（加速倍率下由仿真时钟换算），用于回归演示、故障演练与 E2E 编排。

## 目录结构

```
scenarios/
├── normal/   # 正常流程：闭环飞行、完整任务流、长任务加速
├── fault/    # 故障演练：低电量、GPS/RTK 丢失、网络劣化、媒体故障
└── swarm/    # 编队骨架（多设备解析；协同时间轴排 W4）
```

## Schema v1（冻结）

```yaml
scenario:
  id: 小写字母/数字/连字符（必填）
  version: 1                    # 必填，当前仅支持 1
  description: 场景说明（可选）
  clock:                        # 可选：场景启动时覆盖全局时钟
    mode: accelerated           #   realtime | accelerated
    speed: 10                   #   加速倍率（>0；realtime 时忽略）

devices:                        # 必填（非空）
  - id: 场景内设备 ID
    vendor: DJI | SIKONG | JOUAV | PX4 | INTERNAL
    deviceMode: DOCK | PILOT     # 可选
    dockModel / droneModel: 型号参数（可选，Adapter 解释）
    initial:                    # 可选初始状态
      location: { lng, lat, height }
      battery: 0-100
      online: true

events:                         # 时间轴（可空：空事件场景启动即 FINISHED）
  - at: 60s                     # 逻辑时间偏移，严格「数字+单位」：ms | s | m
    action: 契约 action 名
    # 其余键即 action 参数（平铺），如 altitude: 60
```

## Action 契约（v1，18 项）

| 类别 | action | 参数 |
|---|---|---|
| 状态回写 | `set_battery` | value(0-100) |
| | `trigger_alarm` | type |
| 飞行 | `takeoff` | altitude(>0) |
| | `goto` | lng/lat/alt + maxSpeed? |
| | `return_home` | rthAltitude? |
| | `land` | — |
| 任务 | `pause_mission` / `resume_mission` / `cancel_mission` | — |
| | `task_fail` | reason |
| 网络 | `network_disconnect` / `network_reconnect` | — |
| | `network_delay` | ms(>0) + jitterMs? |
| | `network_drop` | percent(0-100) |
| 飞行故障 | `gps_loss` / `gps_recover` / `rtk_loss` / `rtk_recover` | — |
| 媒体 | `video_stream_stop` / `video_stream_recover` | — |
| | `media_upload_fail` | percent(0-100) |
| 场景 | `set_clock_speed` | speed(>0) |

未知 action 校验直接失败（fail-fast 并列出全部支持项）；同 id 重复加载会替换并复位为 LOADED。

## 运行方式

```bash
# 1) 加载（文件路径 或 yaml 文本二选一）
curl -X POST http://localhost:8080/api/scenarios/load \
  -H "Content-Type: application/json" \
  -d '{"path":"scenarios/normal/takeoff-cruise-land.yaml"}'

# 2) 启动（幂等；事件按逻辑时间触发）
curl -X POST http://localhost:8080/api/scenarios/normal-takeoff-cruise-land/start

# 3) 状态（LOADED | RUNNING | FINISHED | STOPPED）
curl http://localhost:8080/api/scenarios/normal-takeoff-cruise-land/status

# 4) 停止（幂等；触发全部故障注入清理）
curl -X POST http://localhost:8080/api/scenarios/normal-takeoff-cruise-land/stop

# 5) 全部已加载场景
curl http://localhost:8080/api/scenarios
```

时钟可运行时热切换：`POST /api/unified/clock/speed {"speed": 10}`。

## 资产清单

| 文件 | id | 覆盖点 | 关联用例 |
|---|---|---|---|
| normal/takeoff-cruise-land.yaml | normal-takeoff-cruise-land | 起飞/巡航/返航/降落闭环 | E2E T01-T03 |
| normal/full-mission.yaml | normal-full-mission | 暂停/恢复/转场生命周期 | E2E T04-T06 |
| normal/long-mission-accelerated.yaml | normal-long-mission-accelerated | 30 分钟任务 10x 加速（验收口径 <3 分钟） | E2E T07 |
| fault/battery-low.yaml | fault-battery-low | 电量阶梯 + LOW_BATTERY 告警 + 返航 | E2E T08 |
| fault/gps-loss.yaml | fault-gps-loss | positionState 0/1 + GPS_LOST | E2E T09 |
| fault/rtk-loss.yaml | fault-rtk-loss | positionState 2→1→2 + RTK_LOST | E2E T10 |
| fault/network-drop.yaml | fault-network-drop | 丢弃率/延迟注入与恢复 | E2E T11 |
| fault/media-fail.yaml | fault-media-fail | 推流停止门 + 上传失败率 | E2E T12 |
| swarm/two-drones-skeleton.yaml | swarm-two-drones-skeleton | 多设备解析骨架（协同排 W4） | W4 前置 |

## 语义边界（如实声明）

- **单机分发**：Schema v1 事件无 per-event device 字段，动作统一作用于场景 devices 首个设备
  （与模拟器单设备 DeviceState 对齐）；多机协同在 W4 扩展。
- **故障为消息级/状态级注入**：作用于模拟器进程内路径，不发真实网络/硬件故障；
  网络故障与 MQTT 连接生命周期解耦（不触发真实断连重连）。
- **时钟语义**：`at` 为场景启动后的逻辑偏移；加速下按逻辑时间触发（10x 时 60s 逻辑 ≈ 6s 物理）。
- **清理**：场景 stop / 结束时全部故障门复位（幂等），不会跨场景残留。
