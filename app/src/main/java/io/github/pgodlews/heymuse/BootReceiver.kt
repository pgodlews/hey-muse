package io.github.pgodlews.heymuse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts listening when the Portal boots, or the app is updated, if the owner wants it to. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!Settings(context).startOnBoot) return
        Log.i("HeyMuse", "starting on $action")
        MuseService.start(context)
    }
}
