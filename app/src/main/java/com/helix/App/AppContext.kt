package com.helix

import android.annotation.SuppressLint
import android.content.Context

// contact blaku64th on discord if you have any issues ^^
@SuppressLint("StaticFieldLeak")
object AppContext {
    lateinit var app: Context

    fun init(context: Context) {
        if (!::app.isInitialized) {
            app = context.applicationContext
        }
    }
}