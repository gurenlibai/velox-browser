package com.velox.browser.filter

import android.content.Context
import android.content.res.AssetManager
import com.velox.browser.metrics.LatencyLogger
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 编译后的域名拦截规则集。
 *
 * 匹配语义：命中「host 本身或其任一父域」即拦截；例外表优先级最高。
 * 性能约束：整个判定过程**零分配**（ThreadLocal 复用 char 缓冲），
 * 因为 [android.webkit.WebViewClient.shouldInterceptRequest] 是同步串行回调，
 * 任何阻塞/分配都会拖慢该页面的**所有**资源请求。
 */
class BlockList private constructor(
    private val count: Int,
    private val excCount: Int,
    private val bloomBits: Int,
    private val bloomK: Int,
    private val bloom: LongArray,
    private val hashes: LongArray,
    private val excHashes: LongArray
) {

    companion object {
        private const val MAGIC = 0x564C4231 // "VLB1"
        private const val MAX_BLOOM_WORDS = 1 shl 22 // 32MB 上限，防御损坏资产
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
        private const val FNV_PRIME = 0x100000001B3L

        @Volatile private var instance: BlockList? = null

        fun get(context: Context): BlockList = instance ?: synchronized(this) {
            instance ?: load(context.applicationContext.assets).also { instance = it }
        }

        fun loaded(): BlockList? = instance

        private fun load(assets: AssetManager): BlockList {
            val t0 = android.os.SystemClock.uptimeMillis()
            return try {
                DataInputStream(assets.open("blocklist.bin").buffered(64 * 1024)).use { input ->
                    if (input.readInt() != MAGIC) throw IllegalStateException("bad magic")
                    val count = input.readInt()
                    val excCount = input.readInt()
                    val bloomBits = input.readInt()
                    val bloomK = input.readInt()
                    val bloomWords = (bloomBits + 63) ushr 6
                    require(count >= 0 && excCount >= 0 && bloomBits > 0) { "corrupt header" }
                    require(bloomWords in 1..MAX_BLOOM_WORDS) { "corrupt bloom size" }

                    val bloom = LongArray(bloomWords).also { arr ->
                        val chunk = ByteArray(minOf(bloomWords * 8, 64 * 1024))
                        var written = 0
                        val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                        while (written < bloomWords * 8) {
                            val want = minOf(chunk.size, bloomWords * 8 - written)
                            input.readFully(chunk, 0, want)
                            var i = 0
                            var wi = written / 8
                            while (i + 8 <= want) {
                                if (wi < bloomWords) arr[wi] = bb.getLong()
                                i += 8; wi++
                            }
                            written += want
                        }
                    }
                    val hashes = readSorted(input, count)
                    val excHashes = readSorted(input, excCount)
                    val bl = BlockList(count, excCount, bloomBits, bloomK, bloom, hashes, excHashes)
                    LatencyLogger.duration("filter.load", t0)
                    bl
                }
            } catch (t: Throwable) {
                // 资产损坏时降级为「空规则集」：宁可少拦，不可崩页面
                android.util.Log.w("Velox", "blocklist load failed, falling back to empty", t)
                BlockList(0, 0, 64, 4, LongArray(1), LongArray(0), LongArray(0))
            }
        }

        private fun readSorted(input: DataInputStream, n: Int): LongArray {
            if (n <= 0) return LongArray(0)
            require(n <= 4_000_000) { "corrupt entry count" }
            val arr = LongArray(n)
            val buf = ByteArray(8 * 8192)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            var idx = 0
            while (idx < n) {
                val take = minOf(8192, n - idx)
                input.readFully(buf, 0, take * 8)
                var i = 0
                while (i < take) {
                    arr[idx + i] = bb.getLong(i * 8)
                    i++
                }
                idx += take
            }
            return arr
        }

        /** 测试与基准用：直接由域名列表构造（构建同样的 Bloom + 有序哈希）。 */
        @JvmStatic
        fun fromDomains(domains: Collection<String>, exceptions: Collection<String> = emptyList()): BlockList {
            fun hash(s: String): Long {
                var h = FNV_OFFSET
                for (ch in s) {
                    h = h xor (ch.code.toLong() and 0xFF)
                    h *= FNV_PRIME
                }
                return h
            }
            val blocked = domains.map { hash(it.lowercase()) }.sorted().toLongArray()
            val exc = exceptions.map { hash(it.lowercase()) }.sorted().toLongArray()
            val bits = maxOf(64, blocked.size * 16)
            val words = LongArray((bits + 63) / 64)
            val k = 4
            val bloomInputs = HashSet<Long>()
            for (d in domains) {
                val labels = d.lowercase().split(".")
                for (i in 0 until labels.size - 1) {
                    val suffix = labels.subList(i, labels.size).joinToString(".")
                    bloomInputs.add(hash(suffix))
                }
            }
            for (h in bloomInputs) {
                for (i in 0 until k) {
                    val idx = (((h ushr (i * 16)) xor (h * (i + 1))) and Long.MAX_VALUE) % bits
                    words[(idx ushr 6).toInt()] = words[(idx ushr 6).toInt()] or (1L shl (idx and 63L).toInt())
                }
            }
            return BlockList(blocked.size, exc.size, bits, k, words, blocked, exc)
        }

        @JvmStatic
        fun fnv1a(bytes: ByteArray, from: Int, to: Int): Long {
            var h = FNV_OFFSET
            var i = from
            while (i < to) {
                h = h xor (bytes[i].toLong() and 0xFF)
                h *= FNV_PRIME
                i++
            }
            return h
        }
    }

    val size: Int get() = count

    private val hostBuf = ThreadLocal.withInitial { CharArray(260) }

    fun isEmpty(): Boolean = count == 0

    /**
     * @return true 表示应当拦截。
     * 传入的 url 必须是已规范化的小写 ASCII（含 punycode），调用方负责。
     */
    fun shouldBlock(url: String): Boolean {
        if (count == 0) return false
        val buf = hostBuf.get()!!
        val len = extractHost(url, buf)
        if (len < 3) return false

        // 从最具体的域开始，逐级向上（a.b.com -> b.com -> com）
        var start = 0
        while (start < len) {
            val h = hashBuffer(buf, start, len)
            if (excCount > 0 && binarySearch(excHashes, h)) return false
            if (contains(h)) return true
            var dot = start
            while (dot < len && buf[dot] != '.') dot++
            start = dot + 1
        }
        return false
    }

    /** 把 URL 的 host 写入 buf，返回长度；无 host 返回 0。零分配。 */
    private fun extractHost(url: String, buf: CharArray): Int {
        val n = url.length
        var i = 0
        val schemeEnd = url.indexOf("://")
        if (schemeEnd in 1..8) i = schemeEnd + 3
        // authority 段：从 i 到第一个 / ? #
        var authorityEnd = i
        while (authorityEnd < n && url[authorityEnd] != '/' &&
            url[authorityEnd] != '?' && url[authorityEnd] != '#') {
            authorityEnd++
        }
        // 同一 authority 内，host 从最后一个 '@' 之后开始
        var hostBegin = i
        var k = i
        while (k < authorityEnd) {
            if (url[k] == '@') hostBegin = k + 1
            k++
        }
        var len = 0
        var j = hostBegin
        while (j < authorityEnd) {
            val c = url[j]
            if (c == ':') break
            if (len >= buf.size) return 0
            buf[len++] = c
            j++
        }
        return len
    }

    /** 主机名是否在黑名单（不查例外）。供 UI 统计与测试使用。 */
    fun contains(hash: Long): Boolean {
        if (!bloomMayContain(hash)) return false
        return binarySearch(hashes, hash)
    }

    private fun bloomMayContain(h: Long): Boolean {
        val bits = bloomBits
        val mask = bloom.size * 64 - 1
        var i = 0
        while (i < bloomK) {
            val idx = (((h ushr (i * 16)) xor (h * (i + 1))) and Long.MAX_VALUE) % bits
            val word = (idx ushr 6).toInt()
            val bit = 1L shl (idx and 63L).toInt()
            if (word >= bloom.size) return true
            if (bloom[word] and bit == 0L) return false
            i++
        }
        return true
    }

    private fun hashBuffer(buf: CharArray, from: Int, to: Int): Long {
        var h = FNV_OFFSET
        var i = from
        while (i < to) {
            val c = buf[i]
            // 域名只可能是 ASCII；大写折叠成小写
            val lower = if (c in 'A'..'Z') (c.code + 32).toChar() else c
            h = h xor (lower.code.toLong() and 0xFF)
            h *= FNV_PRIME
            i++
        }
        return h
    }

    private fun binarySearch(arr: LongArray, key: Long): Boolean {
        var lo = 0
        var hi = arr.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = arr[mid]
            when {
                v < key -> lo = mid + 1
                v > key -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }
}
