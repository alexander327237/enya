package com.enya.txtvoice.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.enya.txtvoice.MainActivity
import com.enya.txtvoice.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that keeps playback alive with the screen off and shows a media-style notification. */
class PlayerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var inForeground = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) Player.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        observeJob = scope.launch {
            Player.state
                .map { NotificationModel(it.book?.title, it.status, it.current?.text, it.index, it.segments.size) }
                .distinctUntilChanged()
                .collect { model ->
                    when (model.status) {
                        Player.Status.IDLE -> {
                            ServiceCompat.stopForeground(this@PlayerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            inForeground = false
                            stopSelf()
                        }
                        Player.Status.PLAYING, Player.Status.LOADING -> goForeground(build(model, playing = true))
                        Player.Status.PAUSED, Player.Status.ERROR -> {
                            // Paused: keep a dismissible notification but let the system reclaim the process.
                            if (inForeground) {
                                ServiceCompat.stopForeground(this@PlayerService, ServiceCompat.STOP_FOREGROUND_DETACH)
                                inForeground = false
                            }
                            notificationManager.notify(NOTIFICATION_ID, build(model, playing = false))
                        }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> Player.play()
            ACTION_PAUSE -> Player.pause()
            ACTION_TOGGLE -> Player.togglePlayPause()
            ACTION_NEXT -> Player.next()
            ACTION_PREV -> Player.previous()
            ACTION_STOP -> {
                Player.stop()
                return START_NOT_STICKY
            }
        }
        // Android requires startForeground() promptly after startForegroundService().
        if (!inForeground) {
            val s = Player.state.value
            goForeground(build(NotificationModel(s.book?.title, s.status, s.current?.text, s.index, s.segments.size), playing = true))
        }
        return START_NOT_STICKY
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        inForeground = true
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(channel)
    }

    private fun action(icon: Int, title: String, action: String): NotificationCompat.Action {
        val intent = Intent(this, PlayerService::class.java).setAction(action)
        val pi = PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(icon, title, pi)
    }

    private fun build(model: NotificationModel, playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_BOOK, Player.state.value.book?.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snippet = model.snippet?.take(120) ?: ""
        val subtitle = when (model.status) {
            Player.Status.LOADING -> getString(R.string.loading)
            Player.Status.PAUSED -> getString(R.string.notification_paused)
            else -> snippet
        }
        val toggle = if (playing) action(R.drawable.ic_pause, getString(R.string.pause), ACTION_PAUSE)
        else action(R.drawable.ic_play, getString(R.string.play), ACTION_PLAY)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(model.title ?: getString(R.string.app_name))
            .setContentText(subtitle)
            .setSubText(if (model.total > 0) "${model.index + 1} / ${model.total}" else null)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(action(R.drawable.ic_skip_previous, getString(R.string.previous), ACTION_PREV))
            .addAction(toggle)
            .addAction(action(R.drawable.ic_skip_next, getString(R.string.next), ACTION_NEXT))
            .addAction(action(R.drawable.ic_stop, getString(R.string.stop), ACTION_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class NotificationModel(
        val title: String?,
        val status: Player.Status,
        val snippet: String?,
        val index: Int,
        val total: Int
    )

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.enya.txtvoice.START"
        const val ACTION_PLAY = "com.enya.txtvoice.PLAY"
        const val ACTION_PAUSE = "com.enya.txtvoice.PAUSE"
        const val ACTION_TOGGLE = "com.enya.txtvoice.TOGGLE"
        const val ACTION_NEXT = "com.enya.txtvoice.NEXT"
        const val ACTION_PREV = "com.enya.txtvoice.PREV"
        const val ACTION_STOP = "com.enya.txtvoice.STOP"
    }
}
