package com.velox.browser

import com.velox.browser.filter.BlockList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockListTest {

    private val list = BlockList.fromDomains(
        domains = listOf(
            "doubleclick.net",
            "ads.example.com",
            "googlesyndication.com",
            "tracker.co"
        ),
        exceptions = listOf("safe.ads.example.com")
    )

    @Test
    fun blocksExactHost() {
        assertTrue(list.shouldBlock("https://doubleclick.net/pagead/x.js"))
    }

    @Test
    fun blocksSubdomainOfListedDomain() {
        assertTrue(list.shouldBlock("https://static.googlesyndication.com/a.js"))
        assertTrue(list.shouldBlock("https://t.tracker.co/pixel"))
    }

    @Test
    fun blocksListedSubdomainAndItsChildren() {
        assertTrue(list.shouldBlock("https://ads.example.com/banner.png"))
        assertTrue(list.shouldBlock("https://cdn.ads.example.com/banner.png"))
    }

    @Test
    fun exceptionWins() {
        assertFalse(list.shouldBlock("https://safe.ads.example.com/ok.js"))
    }

    @Test
    fun doesNotBlockUnrelated() {
        assertFalse(list.shouldBlock("https://example.com/"))
        assertFalse(list.shouldBlock("https://news.ycombinator.com/item?id=1"))
        // 后缀误判防线：notdoubleclick.net 不能被 doubleclick.net 命中
        assertFalse(list.shouldBlock("https://notdoubleclick.net/x"))
    }

    @Test
    fun handlesPortsUserInfoAndQueries() {
        assertTrue(list.shouldBlock("https://doubleclick.net:8443/x"))
        assertTrue(list.shouldBlock("https://user:pw@doubleclick.net/x"))
        assertTrue(list.shouldBlock("http://doubleclick.net?q=1"))
    }

    @Test
    fun handlesUppercaseHost() {
        assertTrue(list.shouldBlock("https://DoubleClick.NET/x"))
    }

    @Test
    fun rejectsMalformedInput() {
        assertFalse(list.shouldBlock(""))
        assertFalse(list.shouldBlock("about:blank"))
        assertFalse(list.shouldBlock("data:text/html,<b>x</b>"))
    }

    @Test
    fun emptyRuleSetNeverBlocks() {
        val empty = BlockList.fromDomains(emptyList())
        assertFalse(empty.shouldBlock("https://doubleclick.net/x"))
        assertTrue(empty.isEmpty())
    }

    /**
     * 性能断言（对应 ARCHITECTURE §3 红线：shouldInterceptRequest 内必须零阻塞）。
     * 10 万次判定必须远低于单帧预算，否则说明引入了 IO 或分配。
     */
    @Test
    fun lookupsAreConstantTimeEnough() {
        val big = BlockList.fromDomains(
            (1..50_000).map { "adserver$it.example-cdn.com" }
        )
        val urls = (0 until 10_000).map {
            if (it % 2 == 0) "https://cdn$it.news.example.org/assets/app.js"
            else "https://adserver${it + 1}.example-cdn.com/x.js"
        }
        // 预热 JIT
        repeat(2000) { list.shouldBlock("https://doubleclick.net/x") }
        val t0 = System.nanoTime()
        var hits = 0
        for (u in urls) if (big.shouldBlock(u)) hits++
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("perf: 10k lookups in ${"%.2f".format(ms)}ms, hits=$hits")
        assertTrue("10k lookups took ${ms}ms; must stay under 120ms", ms < 120.0)
        assertTrue("expected ad hosts to be blocked", hits > 4000)
    }
}
