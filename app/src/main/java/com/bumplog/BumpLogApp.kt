package com.bumplog

import android.app.Application

/**
 * Settings are ready from the moment any process starts. Without this, a background start - Android
 * restarting logging after a kill, or the weekly backup worker - crashed on Prefs before any screen opened.
 */
class BumpLogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
