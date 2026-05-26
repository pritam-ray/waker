package com.waker.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts WakerService after device reboot if any ping events were enabled.
 * The service's default onStartCommand path will reload all enabled events.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val hasEnabled = EventRepository.getAll(context).any { it.enabled }
        if (!hasEnabled) return
        context.startForegroundService(Intent(context, WakerService::class.java))
    }
}
