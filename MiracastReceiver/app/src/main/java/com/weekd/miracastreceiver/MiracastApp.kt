package com.weekd.miracastreceiver

import android.app.Application
import com.weekd.miracastreceiver.util.AppLanguage
import timber.log.Timber

/**
 * Miracast Receiver Application
 */
class MiracastApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化 Timber 日志库。
        // release 也保留 info 及以上级别：Miracast 这条链路依赖系统组件（wpa_supplicant /
        // MediaCodec）协作，出问题时只有应用自己的日志能说明协商走到哪一步；
        // 全屏蔽等于把这台设备变成黑盒（实测 release 版排查 M6/M7 完全无从下手）。
        Timber.plant(
            if (BuildConfig.DEBUG) Timber.DebugTree()
            else InfoTree()
        )

        // 要在任何 Activity / 服务创建前恢复界面语言
        AppLanguage.restore(this)

        Timber.i("MiracastApp initialized")
    }
}

/**
 * release 版用的日志树：只输出 info 及以上，丢掉 verbose/debug。
 * 投屏协商的关键节点（session / SETUP / PLAY / RTP）都用 Timber.i，
 * 因此现场排查信息完整，又不会被高频 debug 日志刷屏。
 */
private class InfoTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < android.util.Log.INFO) return
        val name = tag ?: "MiracastReceiver"
        if (t == null) android.util.Log.println(priority, name, message)
        else android.util.Log.println(priority, name, "$message\n${android.util.Log.getStackTraceString(t)}")
    }
}
