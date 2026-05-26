package com.waker.keepalive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_NOTIFICATION_PERMISSION = 101
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: EventAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty      = findViewById(R.id.tvEmpty)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = EventAdapter(
            events   = emptyList(),
            onToggle = ::handleToggle,
            onEdit   = ::showEventDialog,
            onDelete = ::handleDelete
        )
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            showEventDialog(null)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private fun handleToggle(id: String, enabled: Boolean) {
        EventRepository.setEnabled(this, id, enabled)
        if (enabled) {
            checkPermissionsAndStartEvent(id)
        } else {
            dispatchToService(WakerService.ACTION_STOP_EVENT, id)
        }
    }

    private fun handleDelete(id: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete event?")
            .setMessage("This will stop and permanently remove the ping event.")
            .setPositiveButton("Delete") { _, _ ->
                dispatchToService(WakerService.ACTION_STOP_EVENT, id)
                EventRepository.delete(this, id)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEventDialog(existing: PingEvent?) {
        val view   = LayoutInflater.from(this).inflate(R.layout.dialog_event, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etUrl  = view.findViewById<EditText>(R.id.etUrl)
        val etMin  = view.findViewById<EditText>(R.id.etMinMinutes)
        val etMax  = view.findViewById<EditText>(R.id.etMaxMinutes)

        if (existing != null) {
            etName.setText(existing.name)
            etUrl.setText(existing.url)
            etMin.setText(existing.minMinutes.toString())
            etMax.setText(existing.maxMinutes.toString())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New Ping Event" else "Edit Ping Event")
            .setView(view)
            .setPositiveButton("Save", null)   // null — we override below to prevent auto-dismiss on error
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                val url  = etUrl.text.toString().trim()
                val minM = etMin.text.toString().toIntOrNull()
                val maxM = etMax.text.toString().toIntOrNull()

                when {
                    name.isEmpty() -> {
                        etName.error = "Required"
                        return@setOnClickListener
                    }
                    url.isEmpty() -> {
                        etUrl.error = "Required"
                        return@setOnClickListener
                    }
                    !url.startsWith("http://") && !url.startsWith("https://") -> {
                        etUrl.error = "Must start with http:// or https://"
                        return@setOnClickListener
                    }
                    minM == null || minM < 1 -> {
                        etMin.error = "Must be ≥ 1"
                        return@setOnClickListener
                    }
                    maxM == null || maxM < minM -> {
                        etMax.error = "Must be ≥ min interval"
                        return@setOnClickListener
                    }
                }

                val event = PingEvent(
                    id         = existing?.id ?: UUID.randomUUID().toString(),
                    name       = name,
                    url        = url,
                    minMinutes = minM!!,
                    maxMinutes = maxM!!,
                    enabled    = existing?.enabled ?: false
                )
                EventRepository.save(this, event)

                // If enabled, restart the event loop so new settings take effect immediately
                if (event.enabled) {
                    dispatchToService(WakerService.ACTION_START_EVENT, event.id)
                }

                dialog.dismiss()
                refreshList()
            }
        }
        dialog.show()
    }

    // ── Service helpers ───────────────────────────────────────────────────────

    private fun checkPermissionsAndStartEvent(id: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION_PERMISSION
            )
            // Event is already marked enabled in prefs; the callback will start all enabled events
            return
        }
        dispatchToService(WakerService.ACTION_START_EVENT, id)
        requestBatteryExclusion()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION_PERMISSION) {
            // Start all currently-enabled events (they were saved before requesting permission)
            EventRepository.getAll(this).filter { it.enabled }.forEach {
                dispatchToService(WakerService.ACTION_START_EVENT, it.id)
            }
            requestBatteryExclusion()
        }
    }

    /** Send an action intent to the foreground service (starts it if not running). */
    private fun dispatchToService(action: String, eventId: String) {
        startForegroundService(
            Intent(this, WakerService::class.java).apply {
                this.action = action
                putExtra(WakerService.EXTRA_EVENT_ID, eventId)
            }
        )
    }

    private fun requestBatteryExclusion() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        // Show rationale before launching the system dialog.
        // Google Play requires in-app disclosure for REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
        AlertDialog.Builder(this)
            .setTitle("Battery optimisation")
            .setMessage(
                "Waker needs to be excluded from battery optimisation so it can send " +
                "scheduled pings reliably, even when the screen is off.\n\n" +
                "On the next screen, tap \"Allow\" to grant this."
            )
            .setPositiveButton("Continue") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) { /* some ROMs don't support this intent */ }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun refreshList() {
        val events = EventRepository.getAll(this)
        adapter.updateData(events)
        tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
    }
}
