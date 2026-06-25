package com.stalkertv.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.stalkertv.app.databinding.ItemLiveChannelBinding

/**
 * Channel list for the Live TV preview screen.
 * [onFocus] fires when a row gains focus (D-pad scroll on TV) → auto-preview.
 * [onActivate] fires on click/OK (touch tap, or D-pad center) → preview-then-fullscreen.
 */
class ChannelGridAdapter(
    private var items: List<Portal.Channel>,
    private val onActivate: (Portal.Channel) -> Unit,
    private val onFocus: (Portal.Channel) -> Unit = {},
    private val onToggleFav: (Portal.Channel) -> Unit = {},
    private val onCatchup: (Portal.Channel) -> Unit = {}
) : RecyclerView.Adapter<ChannelGridAdapter.VH>() {

    fun submit(list: List<Portal.Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemLiveChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLiveChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.b.name.text = if (ch.number.isNotEmpty()) "${ch.number}.  ${ch.name}" else ch.name
        val fav = Configs.isFavorite(holder.b.root.context, ch.id)
        holder.b.star.text = if (fav) "★" else "☆"
        holder.b.star.setTextColor(if (fav) 0xFFFFD54F.toInt() else 0xFF5A6675.toInt())
        val toggle = {
            Configs.toggleFavorite(holder.b.root.context, ch.id)
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos) // refresh just this star
            onToggleFav(ch)
        }
        holder.b.star.setOnClickListener { toggle() }              // tap the star (touch)
        holder.b.root.setOnLongClickListener { toggle(); true }     // long-press OK / long-tap = favourite
        // Catch-up clock only on channels that actually have an archive (tv_archive_duration > 0).
        val hasCatchup = ch.archiveDays > 0
        if (hasCatchup) {
            holder.b.clock.visibility = View.VISIBLE
            // tap the clock (touch), or press LEFT on the channel to jump to the clock (remote).
            holder.b.clock.setOnClickListener { onCatchup(ch) }
            holder.b.root.setOnKeyListener { _, keyCode, ev ->
                if (ev.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    holder.b.clock.requestFocus(); true
                } else false
            }
            holder.b.clock.setOnKeyListener { _, keyCode, ev ->
                if (ev.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    holder.b.root.requestFocus(); true
                } else false
            }
        } else {
            holder.b.clock.visibility = View.GONE
            holder.b.clock.setOnClickListener(null)
            holder.b.root.setOnKeyListener(null)
        }
        if (ch.logoUrl.isEmpty()) {
            holder.b.thumb.visibility = View.GONE
            holder.b.thumb.setImageDrawable(null)
        } else {
            holder.b.thumb.visibility = View.VISIBLE
            holder.b.thumb.load(ch.logoUrl) {
                crossfade(true); placeholder(R.drawable.thumb_placeholder); error(R.drawable.thumb_placeholder)
            }
        }
        holder.b.root.setOnClickListener { onActivate(ch) }
        holder.b.root.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocus(ch) }
    }

    override fun getItemCount() = items.size
}
