package com.tv.mailvod

import android.app.Application
import com.tv.mailvod.store.LibraryStore
import com.tv.mailvod.config.ConfigLoader

/**
 * 应用入口。负责初始化配置与本地存储单例。
 */
class App : Application() {
    lateinit var configLoader: ConfigLoader
        private set
    lateinit var library: LibraryStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        configLoader = ConfigLoader(this).also { it.ensureLoaded() }
        library = LibraryStore(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
