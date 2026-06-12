package com.palmclaw

import android.app.Application

class PalmClawApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }
}
