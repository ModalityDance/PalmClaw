package com.palmclaw

import android.app.Application
import com.palmclaw.runtime.alwayson.AlwaysOnRuntimeAccess
import com.palmclaw.runtime.alwayson.AlwaysOnTrigger

class PalmClawApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        appContainer
        AlwaysOnRuntimeAccess.requestReconcile(AlwaysOnTrigger.INITIALIZE)
    }
}
