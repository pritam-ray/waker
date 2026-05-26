package com.waker.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Foreground service that manages N independent ping loops — one per PingEvent.
 * Each loop runs on a Handler and fires HTTP GETs on a single-threaded executor.
 *
 * Intent actions:
 *   ACTION_START_EVENT  — begin (or restart) pinging a specific event
 *   ACTION_STOP_EVENT   — stop pinging a specific event; service self-stops if empty
 *   (no action)         — on initial / sticky restart: load all enabled events
 */
class WakerService : Service() {

    companion object {
        const val ACTION_START_EVENT = "com.waker.START_EVENT"
        const val ACTION_STOP_EVENT  = "com.waker.STOP_EVENT"
        const val EXTRA_EVENT_ID     = "event_id"

        private const val TAG             = "WakerService"
        private const val CHANNEL_ID      = "waker_keep_alive"
        private const val NOTIFICATION_ID = 1
    }

    private val mainHandler    = Handler(Looper.getMainLooper())
    // CachedThreadPool: one thread per concurrent ping (kept minimal by Handler scheduling)
    private val networkExecutor = Executors.newCachedThreadPool()

    // Live ping loops, keyed by event id
    private val activeRunnables = mutableMapOf<String, Runnable>()
    // Last HTTP status per event id (shown in notification)
    private val lastStatus      = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must be called immediately on every onStartCommand
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START_EVENT -> {
                val id = intent.getStringExtra(EXTRA_EVENT_ID) ?: return START_STICKY
                val event = EventRepository.getById(this, id) ?: return START_STICKY
                scheduleEvent(event)
            }
            ACTION_STOP_EVENT -> {
                val id = intent.getStringExtra(EXTRA_EVENT_ID) ?: return START_STICKY
                stopEvent(id)
                if (activeRunnables.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                // Initial or sticky restart — load every enabled event
                val enabled = EventRepository.getAll(this).filter { it.enabled }
                if (enabled.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                enabled.forEach { scheduleEvent(it) }
            }
        }

        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeRunnables.keys.toList().forEach { stopEvent(it) }
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun scheduleEvent(event: PingEvent) {
        stopEvent(event.id)   // cancel any existing runnable for this id

        val runnable = object : Runnable {
            override fun run() {
                networkExecutor.execute { pingUrl(event.id, event.name, event.url) }

                val delayMs = Random.nextLong(
                    event.minMinutes * 60_000L,
                    event.maxMinutes * 60_000L + 1L
                )
                Log.d(TAG, "[${event.name}] next ping in ${delayMs / 60_000}m ${(delayMs % 60_000) / 1_000}s")
                mainHandler.postDelayed(this, delayMs)
            }
        }
        activeRunnables[event.id] = runnable
        mainHandler.post(runnable)
    }

    private fun stopEvent(id: String) {
        activeRunnables.remove(id)?.let { mainHandler.removeCallbacks(it) }
        lastStatus.remove(id)
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun pingUrl(id: String, name: String, urlString: String) {
        try {
            val url = URL(urlString)
            require(url.protocol == "http" || url.protocol == "https") {
                "Unsupported protocol: ${url.protocol}"
            }
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout    = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Waker/1.0)")
                setRequestProperty("Accept",     "application/json, */*")
            }
            val code = conn.responseCode
            conn.disconnect()
            lastStatus[id] = "HTTP $code ✓"
            Log.i(TAG, "[$name] ping OK — HTTP $code")
        } catch (e: Exception) {
            lastStatus[id] = "error"
            Log.w(TAG, "[$name] ping failed: ${e.message}")
        }
        updateNotification()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val count = activeRunnables.size
        val title = if (count == 0) "Waker — Idle"
                    else "Waker — $count event${if (count > 1) "s" else ""} active"

        val text = when {
            lastStatus.isEmpty() -> "Starting…"
            else -> lastStatus.entries.take(3).joinToString("  ·  ") { (id, s) ->
                "${EventRepository.getById(this, id)?.name ?: id}: $s"
            }
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Keep Alive", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Periodic health pings"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
