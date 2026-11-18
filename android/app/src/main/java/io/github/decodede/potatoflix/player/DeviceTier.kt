package io.github.decodede.potatoflix.player

import android.app.ActivityManager
import android.content.Context
import io.github.decodede.potatoflix.App

/**
 * Rough RAM tier for buffer sizing, so the same build works on a 500 MB TV box and a 12 GB phone.
 * Overridden by Settings → Player → Buffer (-1 = this detection).
 */
object DeviceTier {
    enum class Tier { LOW, MID, HIGH }

    private val detected: Tier by lazy {
        val am = App.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        when {
            mem.totalMem <= 1_500_000_000L -> Tier.LOW
            mem.totalMem <= 4_000_000_000L -> Tier.MID
            else -> Tier.HIGH
        }
    }

    /** The tier to buffer for: the user's profile, else this device's. */
    val tier: Tier
        get() = when (io.github.decodede.potatoflix.Settings.bufferProfile) {
            0 -> Tier.LOW
            1 -> Tier.MID
            2 -> Tier.HIGH
            else -> detected
        }

    /** min buffer, max buffer, buffer needed to start, buffer needed to resume (milliseconds). */
    val bufferDurationsMs: LongArray
        get() = when (tier) {
            Tier.LOW -> longArrayOf(10_000, 25_000, 2_000, 2_500)
            Tier.MID -> longArrayOf(15_000, 50_000, 2_500, 5_000)
            // 4K needs the biggest cushion so the connection stays ahead of the bitrate.
            Tier.HIGH -> longArrayOf(20_000, 60_000, 2_500, 5_000)
        }

    /** RAM ceiling for the buffer; only kicks in after the duration targets (time is prioritised first). */
    val targetBufferBytes: Int
        get() = when (tier) {
            Tier.LOW -> 12 * 1024 * 1024
            Tier.MID -> 48 * 1024 * 1024
            Tier.HIGH -> 128 * 1024 * 1024
        }
}
