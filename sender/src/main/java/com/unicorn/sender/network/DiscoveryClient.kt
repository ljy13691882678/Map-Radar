package com.unicorn.sender.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * A 机端：监听 PORT 上的 UDP 广播，从 BEACON 包中解析出 B 机地址。
 * 发现成功后通过 [onDiscovered] 回调把地址交给发送端使用。
 */
class DiscoveryClient(scope: CoroutineScope) {
    private val job = scope.coroutineContext[Job]?.let { SupervisorJob(it) } ?: SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var reader: Job? = null

    private val _peer = MutableStateFlow<InetSocketAddress?>(null)
    val peer: StateFlow<InetSocketAddress?> = _peer

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state

    fun start() {
        if (reader?.isActive == true) return
        _state.value = DiscoveryState.Scanning
        ioScope.launch {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                reader = launch { readLoop() }
            } catch (t: Throwable) {
                _state.value = DiscoveryState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun stop() {
        reader?.cancel()
        socket?.close()
        socket = null
        _state.value = DiscoveryState.Idle
    }

    private suspend fun readLoop() {
        val s = socket ?: return
        val buf = ByteArray(1024)
        while (isActive) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val text = buf.copyOfRange(0, p.length).decodeToString()
                if (text.startsWith("BEACON:")) {
                    // BEACON:receiver:9527:<ip>
                    val parts = text.split(":")
                    if (parts.size >= 4 && parts[2].toIntOrNull() != null) {
                        val addr = InetSocketAddress(parts[3], parts[2].toInt())
                        _peer.value = addr
                        _state.value = DiscoveryState.Found(addr)
                    }
                }
            } catch (_: SocketException) {
                if (!isActive) return
            } catch (_: Throwable) {
                yield()
            }
        }
    }

    sealed class DiscoveryState {
        data object Idle : DiscoveryState()
        data object Scanning : DiscoveryState()
        data class Found(val addr: InetSocketAddress) : DiscoveryState()
        data class Error(val msg: String) : DiscoveryState()
    }

    companion object {
        const val PORT = 9527
    }
}
