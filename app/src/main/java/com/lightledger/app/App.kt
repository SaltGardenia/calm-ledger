package com.lightledger.app

import android.app.Application
import com.lightledger.app.data.AppDatabase
import com.lightledger.app.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(AppDatabase.build(this))
        ServiceLocator.init(repository)
        applicationScope.launch {
            repository.ensureSeeded()
            repository.ensureDefaultSyncRules()
        }
    }
}

/** 轻量服务定位器：单模块应用无需引入 Hilt，保持二次开发门槛低 */
object ServiceLocator {
    lateinit var repository: Repository
        private set

    fun init(repo: Repository) {
        repository = repo
    }
}
