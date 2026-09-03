package com.unicorn.sender.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * 自定义可靠 UDP 发送端（A 机）。
 *
 * 设计：
 *   - 外部 put(seq, payload) 推入待发送帧
 *   - 内部维护一个 [WINDOW_SIZE] 大小的滑动窗口，未确认的 seq 保留
 *   - 超时 [ACK_TIMEOUT_MS] 未收到 ACK 的帧按退避重发（最多 [MAX_RETRIES] 次）
 *   - 丢包定义：经过 MAX_RETRIES 次重发仍未收到 ACK 的帧；计入 [lostFrames]
 *   - RTT 通过 `frame.ts_ms` 与收到 ACK 的时间差估算（外部填入 ts_ms）
 *
 * 注意：这里的可靠层是「尽力而为的弱可靠」——UDP 局域网下丢包极少，
 * 3 次退避重发基本可以覆盖绝大多数抖动；如果后续要传大文件再引入更严格的 FEC。
 */
class ReliableUdpSender(
    scope: CoroutineScope,
    private val target: InetSocketAddress,
) {
    private val parentJob = scope.coroutineContext[Job] ?: SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + parentJob)

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var senderJob: Job? = null

    // seq -> Payload（字节），同时带发送时间
    private val pending = LinkedHashMap<Long, Item>()
    private val mutex = Any()
    private val gate = Semaphore(WINDOW_SIZE) // 并发控制滑动窗口

    data class Item(
        val seq: Long,
        val bytes: ByteArray,
        val firstSentAt: Long,
        var lastSentAt: Long,
        var retries: Int,
        var acked: Boolean = false,
    )

    private val _ackedFrames = MutableStateFlow(0L)
    val ackedFrames: StateFlow<Long> = _ackedFrames

    private val _lostFrames = MutableStateFlow(0L)
    val lostFrames: StateFlow<Long> = _lostFrames

    private val _rttMs = MutableStateFlow<Long?>(null)
    val rttMs: StateFlow<Long?> = _rttMs

    fun start() {
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(null) // 随机端口
                broadcast = true
            }
            readerJob = ioScope.launch { ackReader() }
            senderJob = ioScope.launch { retransmitLoop() }
        } catch (t: Throwable) {
            throw t
        }
    }

    fun stop() {
        readerJob?.cancel()
        senderJob?.cancel()
        synchronized(mutex) { pending.clear() }
        socket?.close()
        socket = null
    }

    /** 推入一帧待发送。返回 false 表示窗口已满。 */
    suspend fun send(seq: Long, bytes: ByteArray): Boolean {
        gate.acquire() // 非阻塞语义：这里用挂起代替满时等待，便于调用方感知
        val now = System.currentTimeMillis()
        val item = Item(seq, bytes, firstSentAt = now, lastSentAt = now, retries = 0)
        synchronized(mutex) { pending[seq] = item }
        return sendNow(item)
    }

    private fun sendNow(item: Item): Boolean {
        val s = socket ?: return false
        return try {
            val p = DatagramPacket(item.bytes, item.bytes.size, target)
            s.send(p)
            item.lastSentAt = System.currentTimeMillis()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun ackReader() {
        val s = socket ?: return
        val buf = ByteArray(64) // "ACK:1234567890" 足够
        while (isActive) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val text = buf.copyOfRange(0, p.length).decodeToString()
                if (text.startsWith("ACK:")) {
                    val seq = text.removePrefix("ACK:").toLongOrNull() ?: continue
                    synchronized(mutex) {
                        val item = pending[seq] ?: return@synchronized
                        if (!item.acked) {
                            item.acked = true
                            val rtt = System.currentTimeMillis() - item.firstSentAt
                            _rttMs.value = rtt
                            _ackedFrames.value += 1
                            pending.remove(seq)
                        }
                    }
                    gate.release()
                }
            } catch (_: SocketException) {
                if (!isActive) return
            } catch (_: Throwable) {
                yield()
            }
        }
    }

    private suspend fun retransmitLoop() {
        while (isActive) {
            val now = System.currentTimeMillis()
            val toResend = mutableListOf<Item>()
            synchronized(mutex) {
                val iter = pending.entries.iterator()
                while (iter.hasNext()) {
                    val (_, item) = iter.next()
                    if (item.acked) continue
                    val backoff = ACK_TIMEOUT_MS + item.retries * ACK_TIMEOUT_MS
                    if (now - item.lastSentAt >= backoff) {
                        if (item.retries >= MAX_RETRIES) {
                            iter.remove()
                            _lostFrames.value += 1
                            gate.release()
                        } else {
                            item.retries += 1
                            toResend += item
                        }
                    }
                }
            }
            toResend.forEach { sendNow(it) }
            delay(RESCAN_MS)
        }
    }

    companion object {
        private const val WINDOW_SIZE = 16
        private const val ACK_TIMEOUT_MS = 50L
        private const val MAX_RETRIES = 3
        private const val RESCAN_MS = 20L
    }
}
