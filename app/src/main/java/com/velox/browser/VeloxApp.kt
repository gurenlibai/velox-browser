package com.velox.browser

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import com.velox.browser.browser.KernelWarmup
import com.velox.browser.browser.SnapshotStore
import com.velox.browser.browser.TabStore
import com.velox.browser.filter.BlockList
import com.velox.browser.metrics.LatencyLogger
import com.velox.browser.observe.CrashGuard
import com.velox.browser.prefs.Settings
import java.util.concurrent.Executors

class VeloxApp : Application() {

    companion object {
        @Volatile lateinit var instance: VeloxApp
            private set
        val appStartUptime: Long by lazy { SystemClock.uptimeMillis() }
    }

    lateinit var settings: Settings
        private set
    lateinit var snapshots: SnapshotStore
        private set
    lateinit var tabs: TabStore
        private set

    override fun onCreate() {
        instance = this
        super.onCreate()
        val t0 = SystemClock.uptimeMillis()
        LatencyLogger.mark("app.onCreate.begin")

        settings = Settings(this)
        // 无磁盘 IO 的轻量对象先行构造，其余一律延后到首帧之后
        snapshots = SnapshotStore(this)
        tabs = TabStore(this, settings)
        CrashGuard.install(this)

        // 规则集与引擎预热都推到主线程空闲期，绝不阻塞首帧
        Looper.myQueue().addIdleHandler {
            LatencyLogger.mark("app.idle.begin")
            KernelWarmup.startAsync(this)
            RuleSetLoader.EXECUTOR.execute { BlockList.get(this) }
            false
        }

        LatencyLogger.mark("app.onCreate.end")
        LatencyLogger.duration("app.onCreate", t0)
    }

    object RuleSetLoader {
        val EXECUTOR = Executors.newSingleThreadExecutor { r ->
            Thread(r, "velox-rules").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}
