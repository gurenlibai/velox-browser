package com.velox.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * 形态快照（Live Snapshot）。
 *
 * 目的（RESEARCH §2.1）：让**首帧完全不依赖 Chromium**。
 * Chrome 用 Freeze-Dried Tabs 把启动提快 13%，但那份快照仍需引擎参与渲染；
 * 这里用纯位图 + 几何信息，绘制成本等于贴一张图（<16ms），
 * 引擎在后台继续启动，`onPageCommitVisible` 到达后淡出。
 *
 * 存储：filesDir/snapshots/<tabId>.webp（降采样到 ≤ 宽 720px，质量 70）。
 * 隐私：无痕标签页的快照只存内存 LRU，永不落盘。
 */
class SnapshotStore(private val context: Context) {

    private val dir: File = File(context.filesDir, "snapshots").apply { mkdirs() }
    private val memory = object : LruCache<Long, Bitmap>(8) {}
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "velox-snapshot").apply { priority = Thread.MIN_PRIORITY }
    }

    /** 同步取内存中的（命中率极高的当前会话），未命中返回 null，绝不阻塞。 */
    fun peek(tabId: Long): Bitmap? = memory.get(tabId)

    /** 后台读盘（冷启动路径调用一次）。 */
    fun load(tabId: Long, onReady: (Bitmap?) -> Unit) {
        memory.get(tabId)?.let { onReady(it); return }
        io.execute {
            val bmp = runCatching {
                val f = fileFor(tabId)
                if (!f.exists() || f.length() == 0L) null
                else BitmapFactory.decodeFile(f.absolutePath)
            }.getOrNull()
            if (bmp != null) memory.put(tabId, bmp)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(bmp) }
        }
    }

    fun save(tabId: Long, bitmap: Bitmap, private: Boolean) {
        memory.put(tabId, bitmap)
        if (private) { fileFor(tabId).delete(); return }
        io.execute {
            runCatching {
                val scaled = downscale(bitmap, 720)
                val tmp = File(dir, "$tabId.tmp")
                tmp.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.WEBP, 70, out) }
                tmp.renameTo(fileFor(tabId))
                if (scaled !== bitmap) scaled.recycle()
            }
        }
    }

    fun delete(tabId: Long) {
        memory.remove(tabId)
        io.execute { fileFor(tabId).delete() }
    }

    fun clearAll() {
        memory.evictAll()
        io.execute { dir.listFiles()?.forEach { it.delete() } }
    }

    fun diskUsageBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileFor(tabId: Long) = File(dir, "$tabId.webp")

    private fun downscale(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, maxWidth, (src.height * ratio).toInt().coerceAtLeast(1), true)
    }
}
