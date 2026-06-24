package com.stalkertv.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.stalkertv.app.databinding.ItemChannelBinding

/**
 * Channel list for the Live TV preview screen.
 * [onFocus] fires when a row gains focus (D-pad scroll on TV) → auto-preview.
 * [onActivate] fires on click/OK (touch tap, or D-pad center) → preview-then-fullscreen.
 */
class ChannelGridAdapter(
    private var items: List<Portal.Channel>,
    private val onActivate: (Portal.Channel) -> Unit,
    private val onFocus: (Portal.Channel) -> Unit = {}
) : RecyclerView.Adapter<ChannelGridAdapter.VH>() {

    fun submit(list: List<Portal.Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.b.name.text = if (ch.number.isNotEmpty()) "${ch.number}.  ${ch.name}" else ch.name
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
