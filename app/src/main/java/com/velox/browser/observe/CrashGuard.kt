package com.velox.browser.observe

import android.app.Application
import android.util.Log

/**
 * 兜底：记录未捕获异常到本地日志（供用户导出），不改变进程原有行为。
 * 注意：必须先保存原 handler 再替换，避免自递归。
 */
object CrashGuard {

    @JvmStatic
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("Velox", "uncaught on ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }
}
