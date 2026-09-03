package com.unicorn.receiver

import android.net.wifi.WifiManager
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
import com.unicorn.receiver.network.ReliableUdpReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReceiverViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val wifi = app.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager
    private val receiver = ReliableUdpReceiver(
        wifiManager = wifi,
        scope = viewModelScope,
        onFrame = { bytes, _ ->
            val text = bytes.decodeToString().take(MAX_LOG_LEN)
            addLog(text)
        },
    )

    val state = receiver.state
    val localIp = receiver.localIp
    val packetCount = receiver.packetCount
    val lastSeq = receiver.lastSeq

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    fun start() = receiver.start()
    fun stop() = receiver.stop()
    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun addLog(text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val ts = (System.currentTimeMillis() % 100_000)
            val entry = "[${ts.toString().padStart(5, '0')}] $text"
            _logs.value = (_logs.value + entry).takeLast(MAX_LOG_LINES)
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 200
        private const val MAX_LOG_LEN = 800
    }
}

class MainActivity : ComponentActivity() {
    private val vm: ReceiverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReceiverScreen(vm) }
        // 默认启动，减少点击步骤
        vm.start()
    }

    override fun onDestroy() {
        vm.stop()
        super.onDestroy()
    }
}

@Composable
private fun ReceiverScreen(vm: ReceiverViewModel) {
    val state by vm.state.collectAsState()
    val ip by vm.localIp.collectAsState()
    val count by vm.packetCount.collectAsState()
    val lastSeq by vm.lastSeq.collectAsState()
    val logs by vm.logs.collectAsState()

    MaterialTheme {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state, ip, count, lastSeq)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.start() }) { Text("启动接收") }
                Button(onClick = { vm.stop() }) { Text("停止") }
                OutlinedButton(onClick = { vm.clearLogs() }) { Text("清空日志") }
            }
            LogList(logs)
        }
    }
}

@Composable
private fun Header(
    state: ReliableUdpReceiver.State,
    ip: String,
    count: Long,
    lastSeq: Long?,
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Unicorn Receiver (B 端)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            val statusText = when (state) {
                is ReliableUdpReceiver.State.Listening -> "✅ 监听中 @ ${state.ip}:${state.port}"
                is ReliableUdpReceiver.State.Error -> "❌ ${state.msg}"
                ReliableUdpReceiver.State.Stopped -> "⏸ 已停止"
            }
            Text(statusText, color = if (state is ReliableUdpReceiver.State.Listening) Color(0xFF2E7D32) else Color(0xFFC62828))
            Text("本机 IP: $ip  •  收包: $count  •  最后 seq: ${lastSeq ?: "-"}")
            Text("协议: UDP 9527  •  每 500ms 广播 BEACON")
        }
    }
}

@Composable
private fun LogList(logs: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1)
    }
    Card(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1020))
                .padding(12.dp),
            state = state,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    color = Color(0xFF9AE6B4),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
