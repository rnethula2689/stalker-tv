package com.stalkertv.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.stalkertv.app.databinding.ItemChannelBinding

class ChannelAdapter(private val onClick: (Portal.Channel) -> Unit) :
    RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = ArrayList<Portal.Channel>()

    fun submit(list: List<Portal.Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.b.name.text = if (ch.number.isNotEmpty()) "${ch.number}.  ${ch.name}" else ch.name
        holder.b.root.setOnClickListener { onClick(ch) }
    }

    override fun getItemCount() = items.size
}
