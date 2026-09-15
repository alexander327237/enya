package com.enya.torrent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the libtorrent session alive in the foreground, holds wake/wifi locks so the download
 * keeps going with the screen off, rebinds sockets on network changes and refreshes the
 * status list once a second.
 */
class TorrentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()
        // Promote to foreground first: Android kills a started service that takes too long.
        if (!startForegroundCompat(buildNotification("Запуск…"))) {
            stopSelf()
            return
        }
        CrashLog.markServiceRunning(this)
        acquireLocks()
        watchNetwork()
        scope.launch {
            TorrentEngine.start()
            while (isActive) {
                try {
                    TorrentEngine.refresh()
                    updateNotification()
                } catch (t: Throwable) {
                    Log.w(TAG, "refresh failed", t)
                }
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: Throwable) {
            }
        }
        releaseLocks()
        CrashLog.markServiceStopped(this)
        // Stopping the session blocks for a while; never do that on the main thread.
        Thread { TorrentEngine.stop() }.start()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (t: Throwable) {
        // Android 12+ forbids starting a foreground service from the background, e.g. when the
        // system restarts a sticky service. Better to stop quietly than to crash.
        Log.w(TAG, "startForeground failed", t)
        false
    }

    private fun acquireLocks() {
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnyaTorrent:download")
                .also { it.acquire() }
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EnyaTorrent:wifi")
                .also { it.acquire() }
        } catch (t: Throwable) {
            Log.w(TAG, "locks failed", t)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release locks failed", t)
        }
        wakeLock = null
        wifiLock = null
    }

    private fun watchNetwork() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (lastNetwork != null && lastNetwork != network) TorrentEngine.onNetworkChanged()
                    lastNetwork = network
                }
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
            networkCallback = callback
        } catch (t: Throwable) {
            Log.w(TAG, "network callback failed", t)
        }
    }

    private fun updateNotification() {
        val items = TorrentEngine.torrents.value
        val active = items.count { !it.finished && !it.paused }
        val text = buildString {
            append(items.size).append(" торрент(ов), ").append(active).append(" активных")
            append(" · ↓ ").append(formatRate(TorrentEngine.totalDownloadRate))
            append(" ↑ ").append(formatRate(TorrentEngine.totalUploadRate))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = Intent(this, TorrentService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getForegroundService(this, 1, stopIntent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "TorrentService"
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.enya.torrent.STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "start failed", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, TorrentService::class.java).setAction(ACTION_STOP))
            } catch (t: Throwable) {
                Log.w(TAG, "stop failed", t)
            }
        }
    }
}
