package com.enya.torrent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TorrentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                TorrentService.CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        TorrentEngine.init(this)
    }
}
