package com.tv.mailvod

import android.app.Application
import com.tv.mailvod.net.TlsCompat
import com.tv.mailvod.store.LibraryStore
import com.tv.mailvod.store.ProgressStore
import com.tv.mailvod.config.ConfigLoader

/**
 * 应用入口。负责初始化配置与本地存储单例。
 */
class App : Application() {
    lateinit var configLoader: ConfigLoader
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var progress: ProgressStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 老安卓(<=7.1.1)补 ISRG 根证书, 修复 Let's Encrypt 源(量子等)的 Source error
        TlsCompat.install(this)
        configLoader = ConfigLoader(this).also { it.ensureLoaded() }
        library = LibraryStore(this)
        progress = ProgressStore(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
