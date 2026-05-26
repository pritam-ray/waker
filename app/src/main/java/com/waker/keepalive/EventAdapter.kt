package com.waker.keepalive

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView

class EventAdapter(
    private var events: List<PingEvent>,
    private val onToggle: (id: String, enabled: Boolean) -> Unit,
    private val onEdit:   (event: PingEvent) -> Unit,
    private val onDelete: (id: String) -> Unit
) : RecyclerView.Adapter<EventAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:     TextView    = view.findViewById(R.id.tvEventName)
        val tvUrl:      TextView    = view.findViewById(R.id.tvEventUrl)
        val tvInterval: TextView    = view.findViewById(R.id.tvEventInterval)
        val toggle:     SwitchCompat = view.findViewById(R.id.switchEnabled)
        val btnEdit:    ImageButton  = view.findViewById(R.id.btnEdit)
        val btnDelete:  ImageButton  = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]

        holder.tvName.text     = event.name
        holder.tvUrl.text      = event.url
        holder.tvInterval.text = "Every ${event.minMinutes}–${event.maxMinutes} min"

        // Clear listener before setting checked state to avoid spurious callbacks
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = event.enabled
        holder.toggle.setOnCheckedChangeListener { _, checked -> onToggle(event.id, checked) }

        holder.btnEdit.setOnClickListener   { onEdit(event) }
        holder.btnDelete.setOnClickListener { onDelete(event.id) }
    }

    override fun getItemCount(): Int = events.size

    fun updateData(newEvents: List<PingEvent>) {
        events = newEvents
        notifyDataSetChanged()
    }
}
