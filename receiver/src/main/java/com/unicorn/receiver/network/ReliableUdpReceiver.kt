package com.unicorn.receiver.network

import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * B 机端：每 [BEACON_INTERVAL_MS] ms 向 255.255.255.255:[PORT] 广播一个 BEACON 包，
 * 让 A 机发现自己。同时监听来自 A 机的数据帧并回 ACK。
 *
 * 协议：
 *   BEACON 发送  ->  "BEACON:{packageName}:{port}:{localIp}"
 *   数据帧接收  ->  直接 JSON 字节；解析后回 ACK
 *   ACK 发送    ->  "ACK:{seq}"
 */
class ReliableUdpReceiver(
    private val wifiManager: WifiManager?,
    scope: CoroutineScope,
    private val onFrame: suspend (ByteArray, InetSocketAddress) -> Unit,
) {
    private val job = scope.coroutineContext[Job]?.let { SupervisorJob(it) } ?: SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var beaconJob: Job? = null
    @Volatile private var readerJob: Job? = null

    private val _state = MutableStateFlow(State.Stopped)
    val state: StateFlow<State> = _state

    private val _localIp = MutableStateFlow("?")
    val localIp: StateFlow<String> = _localIp

    private val _rttMs = MutableStateFlow<Long?>(null)
    val rttMs: StateFlow<Long?> = _rttMs

    private val _packetCount = MutableStateFlow(0L)
    val packetCount: StateFlow<Long> = _packetCount

    private val _lastSeq = MutableStateFlow<Long?>(null)
    val lastSeq: StateFlow<Long?> = _lastSeq

    fun start() {
        if (_state.value != State.Stopped) return
        ioScope.launch {
            try {
                socket = DatagramSocket(null).apply {
                    // 复用端口，允许接收广播
                    reuseAddress = true
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
        val beacon = "BEACON:receiver:$PORT:${_localIp.value}".toByteArray()
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
                // 忽略单次异常，继续监听
                yield()
            }
        }
    }

    private suspend fun handlePacket(data: ByteArray, from: InetSocketAddress) {
        val text = data.decodeToString()
        // 帧头：JSON 帧以 '{' 开头；我们协议不混合其它文本，ACK 由本端发送。
        if (text.startsWith("{")) {
            // 尝试解析 seq 用于 ACK（尽量从 JSON 里抠，失败则跳过 ACK，
            // 数据仍正常交给上层 —— 兼容未来的非 JSON 帧）
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

    private fun detectLocalIp(): String {
        // 优先从 WifiManager 获取
        val wifiIp = wifiManager?.connectionInfo?.ipAddress
        if (wifiIp != null && wifiIp != 0) {
            val a = wifiIp and 0xff
            val b = (wifiIp shr 8) and 0xff
            val c = (wifiIp shr 16) and 0xff
            val d = (wifiIp shr 24) and 0xff
            return "$a.$b.$c.$d"
        }
        // 兜底：从网络接口遍历
        NetworkInterface.getNetworkInterfaces().iterator().forEachRemaining { ni ->
            if (ni.isLoopback || !ni.isUp) return@forEachRemaining
            ni.inetAddresses.iterator().forEachRemaining { addr ->
                if (addr.hostAddress?.startsWith("192.168.") == true) {
                    return addr.hostAddress!!
                }
            }
        }
        return "unknown"
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

        /** 从 JSON 帧里抠出 seq。简单正则够用，避免再引入序列化开销。 */
        private val seqRegex = """"seq"\s*:\s*(\d+)""".toRegex()
        fun extractSeq(json: String): Long? =
            seqRegex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }
}
