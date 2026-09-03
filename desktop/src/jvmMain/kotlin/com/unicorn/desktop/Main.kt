package com.unicorn.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.unicorn.desktop.network.FrameCodec
import com.unicorn.desktop.network.ReliableUdpReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

/**
 * Desktop 应用入口（Compose Desktop）。
 *
 * 启动方式二选一：
 *   1) Gradle task 跑：  ./gradlew :desktop:run
 *   2) 打 fat-jar 然后： ./gradlew :desktop:shadowJar  →  java -jar desktop/build/libs/unicorn-desktop-receiver-all.jar
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Unicorn Desktop Receiver",
        state = rememberWindowState(width = 900.dp, height = 700.dp),
    ) {
        App()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val viewModel = remember { DesktopViewModel() }
    val state by viewModel.state.collectAsState()
    val ip by viewModel.localIp.collectAsState()
    val count by viewModel.packetCount.collectAsState()
    val lastSeq by viewModel.lastSeq.collectAsState()
    val rtt by viewModel.rttMs.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val parsed by viewModel.parsedFrames.collectAsState()

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("🦄 Unicorn Desktop Receiver") },
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(state, ip, count, lastSeq, rtt)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::start) { Text("▶ 启动接收") }
                    Button(onClick = viewModel::stop) { Text("⏹ 停止") }
                    OutlinedButton(onClick = viewModel::clearLogs) { Text("🗑 清空日志") }
                }
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { LogPanel(logs) }
                    Box(Modifier.weight(1.2f).fillMaxHeight()) { FramePanel(parsed) }
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: ReliableUdpReceiver.State,
    ip: String,
    count: Long,
    lastSeq: Long?,
    rtt: Long?,
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val statusText = when (state) {
                is ReliableUdpReceiver.State.Listening -> "✅ 监听中 @ ${state.ip}:${state.port}"
                is ReliableUdpReceiver.State.Error -> "❌ ${state.msg}"
                ReliableUdpReceiver.State.Stopped -> "⏸ 已停止"
            }
            Text("状态", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(
                statusText,
                color = if (state is ReliableUdpReceiver.State.Listening) Color(0xFF2E7D32) else Color(0xFFC62828),
            )
            Text("本机 IP: $ip  •  收包: $count  •  最后 seq: ${lastSeq ?: "-"}  •  RTT: ${rtt?.let { "${it}ms" } ?: "-"}")
            Text("协议: UDP 9527  •  每 500ms 广播 BEACON")
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1)
    }
    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
        Column {
            Text(
                "📜 原始日志",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B1020))
                    .padding(12.dp),
                state = state,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(logs) { line ->
                    Text(text = line, color = Color(0xFF9AE6B4), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FramePanel(parsed: List<ParsedFrame>) {
    val state = rememberLazyListState()
    LaunchedEffect(parsed.size) {
        if (parsed.isNotEmpty()) state.animateScrollToItem(parsed.size - 1)
    }
    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
        Column {
            Text(
                "📊 解析后的 Unicorn 帧 (${parsed.size})",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121228))
                    .padding(12.dp),
                state = state,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(parsed.reversed().take(100)) { p ->
                    Card(shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "seq=${p.seq}  ts_ms=${p.ts}  来自 ${p.from}",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text("heartbeat=${p.frame.heartbeat}  spo2=${p.frame.spo2}  status=${p.frame.status}")
                            Text("comment=${p.frame.comment}")
                            if (p.frame.raw.isNotEmpty()) {
                                Text("raw=${p.frame.raw}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ViewModel —— desktop 版，用 Compose 的 remember 来持有，不依赖 AndroidX Lifecycle。 */
class DesktopViewModel {
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val receiver = ReliableUdpReceiver(scope = scope) { bytes, from ->
        addLog(bytes.decodeToString().take(600))
        val parsed = FrameCodec.decode(bytes)
        if (parsed != null) addParsedFrame(parsed, from)
    }

    val state: StateFlow<ReliableUdpReceiver.State> = receiver.state
    val localIp: StateFlow<String> = receiver.localIp
    val packetCount: StateFlow<Long> = receiver.packetCount
    val lastSeq: StateFlow<Long?> = receiver.lastSeq
    val rttMs: StateFlow<Long?> = receiver.rttMs

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _parsed = MutableStateFlow<List<ParsedFrame>>(emptyList())
    val parsedFrames: StateFlow<List<ParsedFrame>> = _parsed

    fun start() {
        receiver.start()
    }

    fun stop() {
        receiver.stop()
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _parsed.value = emptyList()
    }

    private fun addLog(text: String) {
        scope.launch {
            val ts = (System.currentTimeMillis() % 100_000).toString().padStart(5, '0')
            _logs.value = (_logs.value + "[$ts] $text").takeLast(300)
        }
    }

    private fun addParsedFrame(env: com.unicorn.desktop.network.Envelope, from: InetSocketAddress) {
        scope.launch {
            _parsed.value = (_parsed.value + ParsedFrame(env.seq, env.ts_ms, env.frame, from)).takeLast(500)
        }
    }
}

data class ParsedFrame(
    val seq: Long,
    val ts: Long,
    val frame: com.unicorn.desktop.network.UnicornFrame,
    val from: InetSocketAddress,
)
