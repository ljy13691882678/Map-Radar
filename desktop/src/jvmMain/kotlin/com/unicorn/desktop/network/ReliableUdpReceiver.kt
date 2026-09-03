package com.unicorn.desktop.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Desktop (Windows/macOS/Linux) 端 UDP 接收器。
 *
 * 协议与 Android receiver 完全一致：
 *   - 启动时向 255.255.255.255:9527 每 500ms 广播一个 BEACON
 *   - 监听 UDP 9527 上的数据帧，解析后回 ACK
 *   - datagram socket 绑定 0.0.0.0（和 Android 行为一致）
 *
 * ⚠️ Windows 用户注意：默认防火墙会拦截 UDP 入站。请在"Windows Defender 防火墙 → 高级设置"
 *    里创建一个入站规则：允许 UDP 协议、指定本地端口 9527、适用配置文件勾选"专用"。
 *    或者更简单 —— 在首次启动 Windows 会弹一个"允许访问"对话框，点"允许专用网络"即可。
 */
class ReliableUdpReceiver(
    scope: CoroutineScope,
    private val onFrame: suspend (ByteArray, InetSocketAddress) -> Unit,
) {
    private val job = scope.coroutineContext[Job]?.let { SupervisorJob(it) } ?: SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var beaconJob: Job? = null
    @Volatile private var readerJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state

    private val _localIp = MutableStateFlow(detectLocalIp())
    val localIp: StateFlow<String> = _localIp

    private val _rttMs = MutableStateFlow<Long?>(null)
    val rttMs: StateFlow<Long?> = _rttMs

    private val _packetCount = MutableStateFlow(0L)
    val packetCount: StateFlow<Long> = _packetCount

    private val _lastSeq = MutableStateFlow<Long?>(null)
    val lastSeq: StateFlow<Long?> = _lastSeq

    fun start() {
        if (_state.value !is State.Stopped) return
        ioScope.launch {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    // 绑定 0.0.0.0 + PORT —— 必须监听所有网卡（不能绑 127.0.0.1）
                    bind(InetSocketAddress(PORT))
                    broadcast = true
                }
                _localIp.value = detectLocalIp()
                _state.value = State.Listening(_localIp.value, PORT)
                beaconJob = launch { beaconLoop() }
                readerJob = launch { readerLoop() }
            } catch (t: Throwable) {
                _state.value = State.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun stop() {
        job.cancel()
        beaconJob?.cancel()
        readerJob?.cancel()
        socket?.close()
        socket = null
        _state.value = State.Stopped
    }

    private suspend fun beaconLoop() {
        val s = socket ?: return
        val beacon = "BEACON:desktop:$PORT:${_localIp.value}".toByteArray()
        // broadcast 用 255.255.255.255 —— 和 Android 端保持一致
        val broadcast = InetSocketAddress("255.255.255.255", PORT)
        while (ioScope.isActive) {
            runCatching {
                val p = DatagramPacket(beacon, beacon.size, broadcast)
                s.send(p)
            }
            delay(BEACON_INTERVAL_MS)
        }
    }

    private suspend fun readerLoop() {
        val s = socket ?: return
        val buf = ByteArray(MAX_PACKET)
        while (ioScope.isActive) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val data = buf.copyOfRange(0, p.length)
                val sender = InetSocketAddress(p.address, p.port)
                handlePacket(data, sender)
            } catch (_: SocketException) {
                if (!ioScope.isActive) return
            } catch (t: Throwable) {
                yield()
            }
        }
    }

    private suspend fun handlePacket(data: ByteArray, from: InetSocketAddress) {
        val text = data.decodeToString()
        if (text.startsWith("{")) {
            val seq = runCatching { extractSeq(text) }.getOrNull()
            if (seq != null) sendAck(seq, from)
            onFrame(data, from)
            _packetCount.value += 1
            if (seq != null) _lastSeq.value = seq
        }
    }

    private fun sendAck(seq: Long, target: InetSocketAddress) {
        val s = socket ?: return
        val ack = "ACK:$seq".toByteArray()
        runCatching { s.send(DatagramPacket(ack, ack.size, target)) }
    }

    /**
     * 优先找第一个 non-loopback + up + 带 IPv4 的网卡。
     * Windows 上会从以太网/Wi-Fi 里挑第一个符合的；
     * 如果没找到（典型在虚拟机里），兜底返回 127.0.0.1 让用户知道。
     */
    private fun detectLocalIp(): String {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return "unknown (无法枚举网卡)"
        for (ni in ifaces) {
            runCatching {
                if (ni.isLoopback || !ni.isUp || ni.isVirtual || ni.isPointToPoint) return@runCatching
                for (addr in ni.inetAddresses) {
                    val ip = addr.hostAddress
                    if (ip != null && ip.startsWith("192.168.")) return ip
                    if (ip != null && ip.startsWith("10.")) return ip
                    if (ip != null && ip.startsWith("172.")) {
                        val parts = ip.split(".")
                        val second = parts.getOrNull(1)?.toIntOrNull() ?: continue
                        if (second in 16..31) return ip // 172.16-31.x.x
                    }
                }
            }
        }
        return "127.0.0.1 (未发现非回环网卡)"
    }

    sealed class State {
        data object Stopped : State()
        data class Listening(val ip: String, val port: Int) : State()
        data class Error(val msg: String) : State()
    }

    companion object {
        const val PORT = 9527
        private const val MAX_PACKET = 8192
        private const val BEACON_INTERVAL_MS = 500L

        /** 从 JSON 帧里抠出 seq —— 用正则避免再引入额外序列化开销。 */
        private val seqRegex = """"seq"\s*:\s*(\d+)""".toRegex()
        fun extractSeq(json: String): Long? =
            seqRegex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }
}
