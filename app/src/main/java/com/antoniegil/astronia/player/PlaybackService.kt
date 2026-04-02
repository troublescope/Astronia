package com.antoniegil.astronia.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.antoniegil.astronia.MainActivity
import com.antoniegil.astronia.R
import com.antoniegil.astronia.util.M3U8Channel

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "astronia_playback"
        var currentPlayer: Player? = null
        var currentTitle: String = ""
        var currentChannel: M3U8Channel? = null
        var isBuffering: Boolean = false
        var isPlayerPageActive: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createFallbackNotification())

        if (!isPlayerPageActive) {
            stopSelfAndCleanup()
            return START_NOT_STICKY
        }

       if (mediaSession == null) {
            currentPlayer?.let { player -> initMediaSession(player) }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun initMediaSession(player: Player) {
        val pendingIntent = createMainActivityPendingIntent()

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {})
            .build()

        player.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateNotification()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = playbackState == Player.STATE_BUFFERING
                        updateNotification()

                        if (playbackState == Player.STATE_ENDED && !player.isPlaying) {
                            stopSelfAndCleanup()
                        }
                    }
                }
        )

        if (player.isPlaying || isBuffering) {
            updateNotification()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val session = mediaSession ?: return createFallbackNotification()

        val currentEpgTitle = currentChannel?.epgTitle ?: ""
        val pendingIntent = createMainActivityPendingIntent()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentEpgTitle)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createFallbackNotification(): Notification {
        val pendingIntent = createMainActivityPendingIntent()
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        if (isPlayerPageActive) {
            startForeground(NOTIFICATION_ID, createNotification())
        } else {
            stopSelfAndCleanup()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun stopSelfAndCleanup() {
        mediaSession?.let {
            it.release()
            mediaSession = null
        }
        currentPlayer = null

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(NOTIFICATION_ID)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfAndCleanup()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelfAndCleanup()
    }
}
