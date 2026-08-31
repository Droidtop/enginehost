package dev.enginehost

import android.app.Application

class EnginehostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile lateinit var instance: EnginehostApplication
            private set
    }
}
