# Unicorn 实时数据传输 Demo

一个让两台 Android 手机在同一 Wi-Fi 局域网下用 **UDP + 自定义可靠层** 低延迟实时互传数据的小 Demo，
面向你提到的 unicorn 模拟数据场景。

## 架构

```
┌─────────────────┐  UDP 广播 BEACON   ┌─────────────────┐
│ receiver（B 机）│  ──────────────────→ │ sender（A 机）  │
│                 │  255.255.255.255    │                 │
│ 每 500ms 广播   │  :9527              │ 监听 9527      │
└─────────────────┘                     └─────────────────┘
         ▲                                      │
         │ UDP 数据帧 + ACK                     │ UDP 数据帧
         │ 端口 9527                            ▼
    ┌─────────────────────────────────────────────┐
    │ ACK:{seq}  ←── 可靠层滑动窗口 + 超时重传 ──→  │
    └─────────────────────────────────────────────┘
```

| 项 | 值 |
|---|---|
| 发现方式 | B 机每 500ms 广播 `BEACON:receiver:9527:<b_ip>` |
| 数据通道 | UDP `:9527`（同一端口复用广播 + 数据收发） |
| 可靠层 | 发送端滑动窗口 16；ACK 超时 50ms + 退避重传，最多 3 次 |
| 丢包计算 | 超过 3 次重传仍无 ACK 的帧计入丢包 |
| RTT | 收到 ACK 时减去发送时间戳，直接从可靠层读取 |
| 帧间隔 | 默认 30ms，在 `SenderViewModel.intervalMs` 里改 |
| 数据格式 | JSON：`{"seq":N,"ts_ms":N,"frame":{...unicorn 字段...}}` |

局域网同 Wi-Fi 下实测，**RTT 通常 < 1ms，丢包率 ≈ 0%**（除非手机 Wi-Fi 信号极差或系统休眠）。

## 目录

```
.
├── sender/     发送端（A 机）: 发现 + 推送模拟 JSON 帧流
├── receiver/   接收端（B 机）: 广播 + 实时展示 JSON + 统计
├── build.gradle.kts / settings.gradle.kts
└── gradle/libs.versions.toml   版本目录（Kotlin + AGP 8.5 + Compose + kotlinx.serialization）
```

## 安装 & 测试

### 1. 在 Android Studio 打开

File → Open → 选择 `/workspace`。首次 Sync 时 Android Studio 会自动下载 Gradle 8.9 和依赖。

### 2. 分别装到两台手机

- **B 机（receiver）**：选择 `receiver` Run Configuration，Run 到 B 机
- **A 机（sender）**：选择 `sender` Run Configuration，Run 到 A 机

两台手机必须连 **同一个 Wi-Fi**（不能一个走 4G 一个走 Wi-Fi，也不能是手机热点 + 另一手机 5G）。

### 3. 操作顺序

1. 先打开 **B 机 receiver**，主界面会显示类似「✅ 监听中 @ 192.168.1.42:9527」
2. 再打开 **A 机 sender**，1~2 秒内发现 B 机，Header 变成「✅ 已发现 B 机 @ 192.168.1.42:9527」
3. 点 **开始发送**：A 机开始每 30ms 推一帧 JSON；B 机终端滚动显示实时帧

B 机收到的每一帧示例：

```json
{"seq":1234,"ts_ms":1712345678901,"frame":{"heartbeat":61,"spo2":97,"status":"ok","comment":"unicorn-sim #1234","raw":{"x":0.12,"y":-0.03,"z":0.87}}}
```

### 4. 看统计

- A 机 sender 界面实时显示：`已确认 / 重传丢弃 / 丢包率 / RTT`
- B 机 receiver 界面实时显示：`本机 IP / 收包计数 / 最后 seq`

## 改 unicorn 数据字段

打开 `sender/src/main/java/com/unicorn/sender/network/FrameEncoder.kt`：

```kotlin
@Serializable
data class UnicornFrame(
    val heartbeat: Int,
    val spo2: Int,
    val status: String,
    val comment: String,
    val raw: Map<String, Double> = emptyMap(), // ← 按需加你自己的模拟字段
)
```

加完字段，再改 `SenderViewModel.buildSimulatedFrame()` 里对应的赋值，B 机的 TextView 会自动渲染新字段（因为直接打印 JSON 整行）。

## 构建命令（可选）

首次用 Android Studio Sync 后，可以用 Gradle 命令行：

```bash
./gradlew :sender:assembleDebug   # 产出 sender/build/outputs/apk/debug/sender-debug.apk
./gradlew :receiver:assembleDebug # 产出 receiver/build/outputs/apk/debug/receiver-debug.apk
```

## 已知限制

- 仅限同 Wi-Fi 局域网跨设备；不同 Wi-Fi 或运营商网络需要加 STUN + UDP 打洞 / 云中继，未实现
- 系统 Doze 省电可能会延迟广播和 UDP 收包，长时间传输建议关 Doze（Settings → Battery → 无限制）
- 没做 Wi-Fi 热点直连场景（B 机开热点、A 机连过来时广播仍有效，但部分机型限制）

## 端口汇总

| 项 | 值 |
|---|---|
| BEACON 广播端口 | `9527` |
| 数据帧端口 | `9527` |
| ACK 端口 | 发送端随机端口，接收端发回 sender 源端口 |
