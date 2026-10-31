package com.velox.browser.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import java.util.concurrent.Executors

data class Visit(val url: String, val title: String, val at: Long, val hits: Int)

/**
 * 本地历史与「常用站点」。
 * - 只存在设备上，无同步、无上传；
 * - 写入在单线程池串行执行，主线程永不触碰 SQLite（避免 StrictMode 违规与首帧抖动）；
 * - 主页的「快速访问」直接从 hits 排序取，不需要额外埋点。
 */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, DB, null, 1) {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "velox-history") }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE visits(
                 url TEXT PRIMARY KEY,
                 title TEXT NOT NULL DEFAULT '',
                 at INTEGER NOT NULL,
                 hits INTEGER NOT NULL DEFAULT 1)"""
        )
        db.execSQL("CREATE INDEX idx_at ON visits(at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS visits")
        onCreate(db)
    }

    fun record(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val now = System.currentTimeMillis()
        io.execute {
            try {
                writableDatabase.execSQL(
                    "INSERT INTO visits(url,title,at,hits) VALUES(?,?,?,1) " +
                        "ON CONFLICT(url) DO UPDATE SET hits=hits+1, at=?, title=CASE WHEN ?<>'' THEN ? ELSE title END",
                    arrayOf(url, title, now, now, title, title)
                )
            } catch (t: Throwable) {
                android.util.Log.w("Velox", "history record failed", t)
            }
        }
    }

    fun topSites(limit: Int, onReady: (List<Visit>) -> Unit) {
        io.execute {
            val out = ArrayList<Visit>(limit)
            try {
                readableDatabase.rawQuery(
                    "SELECT url,title,at,hits FROM visits ORDER BY hits DESC, at DESC LIMIT ?",
                    arrayOf(limit.toString())
                ).use { c ->
                    while (c.moveToNext()) out.add(Visit(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3)))
                }
            } catch (t: Throwable) {
                // 忽略
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(out) }
        }
    }

    fun recent(limit: Int, onReady: (List<Visit>) -> Unit) {
        io.execute {
            val out = ArrayList<Visit>(limit)
            try {
                readableDatabase.rawQuery(
                    "SELECT url,title,at,hits FROM visits ORDER BY at DESC LIMIT ?",
                    arrayOf(limit.toString())
                ).use { c ->
                    while (c.moveToNext()) out.add(Visit(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3)))
                }
            } catch (t: Throwable) {
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(out) }
        }
    }

    fun clear(onDone: () -> Unit = {}) {
        io.execute {
            try { writableDatabase.delete("visits", null, null) } catch (t: Throwable) {}
            android.os.Handler(android.os.Looper.getMainLooper()).post(onDone)
        }
    }

    companion object {
        private const val DB = "velox-history.db"
    }
}
