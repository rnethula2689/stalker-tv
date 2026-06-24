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
        } else {
            holder.b.star.visibility = View.VISIBLE
            val isF = fav.isFav()
            holder.b.star.text = if (isF) "★" else "☆"
            holder.b.star.setTextColor(if (isF) 0xFFFFD54F.toInt() else 0xFF5A6675.toInt())
            holder.b.star.setOnClickListener {
                fav.toggle()
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            }
        }
    }

    override fun getItemCount() = items.size
}
