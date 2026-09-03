package com.unicorn.desktop.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 和 Android sender 端完全对齐的帧格式（字节兼容）。
 * 两边都用 kotlinx-serialization-json 序列化 Envelope。
 * 改这个结构时记得同步改 Android sender 的 FrameEncoder.kt。
 */
@Serializable
data class UnicornFrame(
    val heartbeat: Int,
    val spo2: Int,
    val status: String,
    val comment: String,
    val raw: Map<String, Double> = emptyMap(),
)

@Serializable
data class Envelope(
    val seq: Long,
    val ts_ms: Long,
    val frame: UnicornFrame,
)

object FrameCodec {
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun decode(bytes: ByteArray): Envelope? = runCatching {
        json.decodeFromString<Envelope>(bytes.decodeToString())
    }.getOrNull()
}
