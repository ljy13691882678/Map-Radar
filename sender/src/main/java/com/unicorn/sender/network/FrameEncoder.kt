package com.unicorn.sender.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Unicorn 模拟帧：一个最小可扩展的结构化 JSON 载荷。
 * 后续你可以按需往 [UnicornFrame] 里加字段（比如模拟心跳、spo2、位置、原始传感器…），
 * 接收端的 TextView 会自动渲染，不需要改网络层。
 */
@Serializable
data class UnicornFrame(
    val heartbeat: Int,
    val spo2: Int,
    val status: String,
    val comment: String,
    /** 可选：原始传感器读数（模拟 unicorm 可能输出的原始数据） */
    val raw: Map<String, Double> = emptyMap(),
)

@Serializable
data class Envelope(
    val seq: Long,
    val ts_ms: Long,
    val frame: UnicornFrame,
)

object FrameEncoder {
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun encode(seq: Long, tsMs: Long, frame: UnicornFrame): ByteArray {
        val env = Envelope(seq = seq, ts_ms = tsMs, frame = frame)
        return json.encodeToString(env).encodeToByteArray()
    }
}
