package com.helix

import android.content.Context
import androidx.core.content.edit

// contact blaku64th on discord if you have any issues ^^
object Prefs {
    const val prefname = "helix_prefs"

    const val pairhost = "pair_host"
    const val pairport = "pair_port"
    const val paircode = "pair_code"
    const val connecthost = "connect_host"
    const val connectport = "connect_port"
    const val success = "connect_succeeded"
    const val ispaired = "has_paired"

    fun prefs(context: Context) = context.getSharedPreferences(prefname, Context.MODE_PRIVATE)!!

    fun savePair(context: Context, host: String, port: String, code: String) {
        prefs(context).edit {
            putString(pairhost, host)
                .putString(pairport, port)
                .putString(paircode, code)
        }
    }

    fun loadPairHost(context: Context): String = prefs(context).getString(pairhost, "127.0.0.1") ?: "127.0.0.1"
    fun loadPairPort(context: Context): String = prefs(context).getString(pairport, "") ?: ""
    fun loadPairCode(context: Context): String = prefs(context).getString(paircode, "") ?: ""

    fun saveConnect(context: Context, host: String, port: String) {
        prefs(context).edit {
            putString(connecthost, host)
                .putString(connectport, port)
        }
    }

    fun loadConnectHost(context: Context): String =
        prefs(context).getString(connecthost, "127.0.0.1") ?: "127.0.0.1"

    fun loadConnectPort(context: Context): String = prefs(context).getString(connectport, "") ?: ""

    fun saveConnectSucceeded(context: Context, succeeded: Boolean) {
        prefs(context).edit { putBoolean(success, succeeded) }
    }

    fun wasLastConnectSuccessful(context: Context): Boolean =
        prefs(context).getBoolean(success, false)

    fun saveHasPaired(context: Context, paired: Boolean) {
        prefs(context).edit { putBoolean(ispaired, paired) }
    }

    fun hasPairedBefore(context: Context): Boolean =
        prefs(context).getBoolean(ispaired, false)
}