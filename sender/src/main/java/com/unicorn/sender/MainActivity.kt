package com.unicorn.sender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unicorn.sender.network.DiscoveryClient
import com.unicorn.sender.network.FrameEncoder
import com.unicorn.sender.network.ReliableUdpSender
import com.unicorn.sender.network.UnicornFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import kotlin.random.Random

class SenderViewModel : AndroidViewModel(android.app.Application()) {
    private val discovery = DiscoveryClient(viewModelScope)

    // 可配置的推送间隔（ms）
    val intervalMs = MutableStateFlow(30L)

    val discoveryState = discovery.state
    val peer = discovery.peer

    private val sender = MutableStateFlow<ReliableUdpSender?>(null)
    val ackedFrames = MutableStateFlow<Long>(0L)
    val lostFrames = MutableStateFlow<Long>(0L)
    val rttMs = MutableStateFlow<Long?>(null)

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private var seqCounter = 0L
    private var sendLoop: Job? = null

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    fun startSending() {
        val target = peer.value
            ?: run { addLog("未发现 B 机，先等自动发现或手动输入 IP"); return }
        if (_sending.value) return

        try {
            val s = ReliableUdpSender(viewModelScope, target)
            s.start()
            sender.value = s
            _sending.value = true

            // 把内部统计流桥接出来
            viewModelScope.launch(Dispatchers.Default) {
                launch { s.ackedFrames.collect { ackedFrames.value = it } }
                launch { s.lostFrames.collect { lostFrames.value = it } }
                launch { s.rttMs.collect { rttMs.value = it } }
            }

            sendLoop = viewModelScope.launch {
                while (this.coroutineContext.isActive) {
                    val ts = System.currentTimeMillis()
                    val frame = buildSimulatedFrame()
                    val bytes = FrameEncoder.encode(seqCounter, ts, frame)
                    val ok = s.send(seqCounter, bytes)
                    if (!ok) addLog("⚠️ 发送窗口已满，丢弃 seq=$seqCounter")
                    seqCounter += 1
                    delay(intervalMs.value)
                }
            }
            addLog("✅ 开始向 ${target.hostString}:${target.port} 推送帧，间隔 ${intervalMs.value}ms")
        } catch (t: Throwable) {
            addLog("❌ 启动失败：${t.message}")
        }
    }

    fun stopSending() {
        sendLoop?.cancel()
        sendLoop = null
        sender.value?.stop()
        sender.value = null
        _sending.value = false
        addLog("⏹ 已停止推送")
    }

    private fun buildSimulatedFrame(): UnicornFrame {
        return UnicornFrame(
            heartbeat = 60 + Random.nextInt(-3, 3),
            spo2 = 96 + Random.nextInt(-2, 3),
            status = listOf("ok", "ok", "ok", "ok", "warn").random(),
            comment = "unicorn-sim #$seqCounter",
            raw = mapOf(
                "x" to Random.nextDouble(-1.0, 1.0),
                "y" to Random.nextDouble(-1.0, 1.0),
                "z" to Random.nextDouble(-1.0, 1.0),
            ),
        )
    }

    fun addLog(text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val ts = (System.currentTimeMillis() % 100_000)
            val entry = "[${ts.toString().padStart(5, '0')}] $text"
            _logs.value = (_logs.value + entry).takeLast(100)
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: SenderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SenderScreen(vm) }
        vm.startDiscovery()
    }

    override fun onDestroy() {
        vm.stopSending()
        vm.stopDiscovery()
        super.onDestroy()
    }
}

@Composable
private fun SenderScreen(vm: SenderViewModel) {
    val discState by vm.discoveryState.collectAsState()
    val peer by vm.peer.collectAsState()
    val acked by vm.ackedFrames.collectAsState()
    val lost by vm.lostFrames.collectAsState()
    val rtt by vm.rttMs.collectAsState()
    val sending by vm.sending.collectAsState()
    val logs by vm.logs.collectAsState()

    MaterialTheme {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(discState, peer, acked, lost, rtt)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.startDiscovery() }) { Text("重新发现") }
                Button(onClick = { vm.startSending() }, enabled = peer != null && !sending) {
                    Text(if (sending) "发送中…" else "开始发送")
                }
                Button(onClick = { vm.stopSending() }, enabled = sending) { Text("停止") }
            }
            LogList(logs)
        }
    }
}

@Composable
private fun Header(
    disc: DiscoveryClient.DiscoveryState,
    peer: InetSocketAddress?,
    acked: Long,
    lost: Long,
    rtt: Long?,
) {
    val lossRate = if (acked + lost > 0) "%.2f%%".format(100.0 * lost / (acked + lost)) else "0.00%"
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Unicorn Sender (A 端)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            val discText = when (disc) {
                is DiscoveryClient.DiscoveryState.Found -> "✅ 已发现 B 机 @ ${disc.addr.hostString}:${disc.addr.port}"
                is DiscoveryClient.DiscoveryState.Scanning -> "🔍 正在扫描局域网…"
                is DiscoveryClient.DiscoveryState.Error -> "❌ ${disc.msg}"
                DiscoveryClient.DiscoveryState.Idle -> "⏸ 未扫描"
            }
            Text(discText, color = if (disc is DiscoveryClient.DiscoveryState.Found) Color(0xFF2E7D32) else Color(0xFFC62828))
            Text("peer: ${peer?.hostString ?: "-"}:${peer?.port ?: "-"}")
            Text("已确认: $acked  •  重传丢弃: $lost  •  丢包率: $lossRate  •  RTT: ${rtt?.let { "${it}ms" } ?: "-"}")
        }
    }
}

@Composable
private fun LogList(logs: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1) }
    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
        LazyColumn(
            Modifier.fillMaxSize().background(Color(0xFF0B1020)).padding(12.dp),
            state = state,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logs) { Text(it, color = Color(0xFFF6AD55), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
