package com.waker.keepalive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for PingEvents, persisted in SharedPreferences as a JSON array.
 * Uses Android's built-in org.json — no extra dependencies.
 */
object EventRepository {

    private const val PREF_NAME = "waker_events"
    private const val KEY_EVENTS = "events_v2"

    fun getAll(context: Context): List<PingEvent> {
        val json = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PingEvent(
                id         = o.getString("id"),
                name       = o.getString("name"),
                url        = o.getString("url"),
                minMinutes = o.getInt("minMinutes"),
                maxMinutes = o.getInt("maxMinutes"),
                enabled    = o.getBoolean("enabled")
            )
        }
    }

    fun getById(context: Context, id: String): PingEvent? =
        getAll(context).firstOrNull { it.id == id }

    /** Insert or update (matched by id). */
    fun save(context: Context, event: PingEvent) {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == event.id }
        if (idx >= 0) list[idx] = event else list.add(event)
        persist(context, list)
    }

    fun delete(context: Context, id: String) {
        persist(context, getAll(context).filter { it.id != id })
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        persist(context, getAll(context).map {
            if (it.id == id) it.copy(enabled = enabled) else it
        })
    }

    private fun persist(context: Context, list: List<PingEvent>) {
        val array = JSONArray()
        list.forEach { e ->
            array.put(JSONObject().apply {
                put("id",         e.id)
                put("name",       e.name)
                put("url",        e.url)
                put("minMinutes", e.minMinutes)
                put("maxMinutes", e.maxMinutes)
                put("enabled",    e.enabled)
            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_EVENTS, array.toString()).apply()
    }
}
