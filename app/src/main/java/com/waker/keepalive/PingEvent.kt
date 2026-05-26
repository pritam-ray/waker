package com.waker.keepalive

data class PingEvent(
    val id: String,          // UUID — stable identity across edits
    val name: String,        // human-readable label
    val url: String,         // URL to GET
    val minMinutes: Int,     // minimum interval between pings (minutes)
    val maxMinutes: Int,     // maximum interval between pings (minutes)
    val enabled: Boolean     // whether this event is currently active
)
