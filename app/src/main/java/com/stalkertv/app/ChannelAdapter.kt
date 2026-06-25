package com.stalkertv.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.stalkertv.app.databinding.ItemChannelBinding

/** Generic list row (optional thumbnail + label) used for every browse level. */
class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

    private val items = ArrayList<ChannelsActivity.Row>()
    var onFavToggled: (() -> Unit)? = null // lets the screen refresh (e.g. drop a row) after toggling

    fun submit(list: List<ChannelsActivity.Row>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        if (row.isHeader) {
            holder.b.name.text = row.label
            holder.b.name.setTextColor(0xFF19C37D.toInt())
            holder.b.thumb.visibility = View.GONE
            holder.b.star.visibility = View.GONE
            holder.b.root.isFocusable = false
            holder.b.root.isClickable = false
            holder.b.root.setOnClickListener(null)
            holder.b.root.setOnLongClickListener(null)
            holder.b.root.isLongClickable = false
            return
        }
        holder.b.root.isFocusable = true
        holder.b.root.isClickable = true
        holder.b.name.setTextColor(0xFFE6EDF3.toInt())
        holder.b.name.text = row.label
        val url = row.iconUrl
        if (url.isNullOrEmpty()) {
            holder.b.thumb.visibility = View.GONE
            holder.b.thumb.setImageDrawable(null)
        } else {
            holder.b.thumb.visibility = View.VISIBLE
            holder.b.thumb.load(url) {
                crossfade(true)
                placeholder(R.drawable.thumb_placeholder)
                error(R.drawable.thumb_placeholder)
            }
        }
        holder.b.root.setOnClickListener { row.action() }
        val fav = row.fav
        if (fav == null) {
            holder.b.star.visibility = View.GONE
            holder.b.root.setOnLongClickListener(null)
            holder.b.root.isLongClickable = false
        } else {
            holder.b.star.visibility = View.VISIBLE
            val isF = fav.isFav()
            holder.b.star.text = if (isF) "★" else "☆"
            holder.b.star.setTextColor(if (isF) 0xFFFFD54F.toInt() else 0xFF5A6675.toInt())
            val toggle = {
                val now = fav.toggle()
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                android.widget.Toast.makeText(
                    holder.b.root.context,
                    if (now) "★  Added to Favourites" else "Removed from Favourites",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                onFavToggled?.invoke()
            }
            holder.b.star.setOnClickListener { toggle() }           // tap the star (touch)
            holder.b.root.setOnLongClickListener { toggle(); true }  // long-press OK / long-tap = favourite
        }
    }

    override fun getItemCount() = items.size
}
