package com.helix

import android.content.Context
import androidx.core.content.edit

object Prefs {
    const val prefname = "helix_prefs"
    const val pairhost = "pair_host"
    const val pairport = "pair_port"
    const val paircode = "pair_code"
    const val connecthost = "connect_host"
    const val connectport = "connect_port"
    const val success = "connect_succeeded"
    const val ispaired = "has_paired"
    const val wireless_debug_on_start = "wireless_debug_on_start"
    const val disable_wifi_on_boot = "disable_wifi_on_boot"
    const val charge_limit_enabled = "charge_limit_enabled"
    const val charge_limit_percent = "charge_limit_percent"
    const val killswitch_soft_clear = "killswitch_soft_clear"

    fun prefs(context: Context) = context.getSharedPreferences(prefname, Context.MODE_PRIVATE)!!

    fun savePair(context: Context, host: String, port: String, code: String) {
        prefs(context).edit {
            putString(pairhost, host).putString(pairport, port).putString(paircode, code)
        }
    }
    fun loadPairHost(context: Context): String = prefs(context).getString(pairhost, "127.0.0.1") ?: "127.0.0.1"
    fun loadPairPort(context: Context): String = prefs(context).getString(pairport, "") ?: ""
    fun loadPairCode(context: Context): String = prefs(context).getString(paircode, "") ?: ""
    fun saveConnect(context: Context, host: String, port: String) {
        prefs(context).edit { putString(connecthost, host).putString(connectport, port) }
    }
    fun loadConnectHost(context: Context): String = prefs(context).getString(connecthost, "127.0.0.1") ?: "127.0.0.1"
    fun loadConnectPort(context: Context): String = prefs(context).getString(connectport, "") ?: ""
    fun saveConnectSucceeded(context: Context, succeeded: Boolean) {
        prefs(context).edit { putBoolean(success, succeeded) }
    }
    fun wasLastConnectSuccessful(context: Context): Boolean = prefs(context).getBoolean(success, false)
    fun saveHasPaired(context: Context, paired: Boolean) {
        prefs(context).edit { putBoolean(ispaired, paired) }
    }
    fun hasPairedBefore(context: Context): Boolean = prefs(context).getBoolean(ispaired, false)
    fun wirelessDebugOnStart(context: Context): Boolean = prefs(context).getBoolean(wireless_debug_on_start, true)
    fun setWirelessDebugOnStart(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(wireless_debug_on_start, enabled) }
    }
    fun disableWifiOnBoot(context: Context): Boolean = prefs(context).getBoolean(disable_wifi_on_boot, false)
    fun setDisableWifiOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(disable_wifi_on_boot, enabled) }
    }
    fun chargeLimitEnabled(context: Context): Boolean = prefs(context).getBoolean(charge_limit_enabled, false)
    fun setChargeLimitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(charge_limit_enabled, enabled) }
    }
    fun chargeLimitPercent(context: Context): Int = prefs(context).getInt(charge_limit_percent, 80).coerceIn(50, 100)
    fun setChargeLimitPercent(context: Context, percent: Int) {
        prefs(context).edit { putInt(charge_limit_percent, percent.coerceIn(50, 100)) }
    }
    fun killswitchSoftClear(context: Context): Boolean = prefs(context).getBoolean(killswitch_soft_clear, false)
    fun setKillswitchSoftClear(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(killswitch_soft_clear, enabled) }
    }
}