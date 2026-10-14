package io.github.pgodlews.heymuse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Keeps Muse listening when the screen is elsewhere: a foreground service of the microphone type,
 * which is what lets an app hear on Android 10, with a partial wake lock so the Portal's screen saver
 * does not stop it.
 */
class MuseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = notification("Starting…")
        // The microphone type arrived with Android 11, which is also when it began to matter; on the
        // Portal's Android 9 and 10 any foreground service keeps the microphone open.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heymuse:listening")
            ?.apply { setReferenceCounted(false); acquire() }
        Assistant.start(applicationContext)
        Assistant.state.map { describe(it) }.distinctUntilChanged()
            .onEach { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(it)) }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Assistant.stop()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun describe(s: UiState): String = when {
        s.mic == Mic.State.NO_PERMISSION -> "Needs the microphone: open Hey Muse to allow it"
        s.mic == Mic.State.SILENCED -> "The microphone is off"
        s.status.online -> if (Assistant.settings.wakeWord) "Listening for \"Hey Muse\"" else "Ready: tap to talk"
        s.status.pairing -> "Pairing with the Muse app"
        s.status.paired -> "Connecting to Muse"
        else -> "Not paired with Muse yet"
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Hey Muse", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps Hey Muse listening in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Hey Muse")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "listening"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "io.github.pgodlews.heymuse.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MuseService::class.java))
        }
    }
}
