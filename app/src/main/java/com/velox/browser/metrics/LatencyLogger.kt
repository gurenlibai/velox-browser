package com.velox.browser.metrics

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本地时延采样器。
 *
 * 设计约束（来自 docs/BENCHMARK.md 的反作弊条款）：
 *  - 数据**永不出网**，只在进程内保留环形缓冲；
 *  - 打点为常数时间；[enabled] 为 false 时全部退化为空操作；
 *  - 只用于本机自检与「超越」主张的可复现验证。
 */
object LatencyLogger {

    const val TAG = "VeloxLatency"

    @Volatile var enabled: Boolean = true

    private const val MAX_SAMPLES = 512
    private const val MAX_GROUPS = 64

    private val marks = ConcurrentHashMap<String, Long>()
    private val durations = ConcurrentHashMap<String, MutableList<Long>>()

    fun mark(name: String) {
        if (!enabled) return
        marks[name] = SystemClock.uptimeMillis()
    }

    fun duration(name: String, sinceUptime: Long) {
        if (!enabled) return
        record(name, SystemClock.uptimeMillis() - sinceUptime)
    }

    fun since(name: String): Long {
        val m = marks[name] ?: return -1L
        return SystemClock.uptimeMillis() - m
    }

    fun record(name: String, ms: Long) {
        if (!enabled) return
        val list = durations[name] ?: run {
            if (durations.size >= MAX_GROUPS) return
            CopyOnWriteArrayList<Long>().also { durations[name] = it }
        }
        if (list.size < MAX_SAMPLES) list.add(ms)
        Log.d(TAG, "$name=${ms}ms")
    }

    fun stats(name: String): Stats? {
        val sorted = durations[name]?.toList()?.sorted() ?: return null
        if (sorted.isEmpty()) return null
        return Stats(
            name = name,
            count = sorted.size,
            p50 = sorted[sorted.size / 2],
            p90 = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)],
            min = sorted.first(),
            max = sorted.last(),
            avg = sorted.sum() / sorted.size
        )
    }

    fun allStats(): List<Stats> = durations.keys.mapNotNull { stats(it) }.sortedBy { it.name }

    fun dump(): String = buildString {
        appendLine("Velox 本地时延采样（不外传）")
        for (s in allStats()) {
            appendLine("  %-30s n=%3d p50=%4dms p90=%4dms avg=%4dms max=%5dms"
                .format(s.name, s.count, s.p50, s.p90, s.avg, s.max))
        }
    }

    fun reset() {
        marks.clear(); durations.clear()
    }
}
