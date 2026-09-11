package com.helix

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

// contact blaku64th on discord if you have any issues ^^
object GoSettings {

    private const val androidsettings = "com.android.settings"
    private const val flag = Intent.FLAG_ACTIVITY_NEW_TASK

    fun goToSettings(ctx: Utils.ActionContext) {
        val context = AppContext.app

        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(androidsettings)
            if (launchIntent != null) {
                launchIntent.addFlags(flag)
                context.startActivity(launchIntent)
                ctx.log("Launched Settings package")
            } else {
                openSettingsDetails(context, ctx)
            }
        } catch (e: Exception) {
            openSettingsDetails(context, ctx)
        }
    }

    private fun openSettingsDetails(context: android.content.Context, ctx: Utils.ActionContext) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$androidsettings".toUri()
        ).apply {
            setPackage(androidsettings)
            addFlags(flag)
        }
        context.startActivity(intent)
        ctx.log("Opened Settings")
        ctx.toast("Press OPEN to go to settings")
    }
}