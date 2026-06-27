package com.stalkertv.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.stalkertv.app.databinding.ItemCardBinding
import com.stalkertv.app.databinding.ItemCatChipBinding
import com.stalkertv.app.databinding.ItemChannelBinding
import com.stalkertv.app.databinding.ItemRailBinding
import com.stalkertv.app.databinding.ItemVodPosterBinding

/** Generic list row (optional thumbnail + label), or a horizontal "rail" of poster cards (home). */
class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<ChannelsActivity.Row>()
    var onFavToggled: (() -> Unit)? = null // lets the screen refresh (e.g. drop a row) after toggling

    fun submit(list: List<ChannelsActivity.Row>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)
    class RailVH(val b: ItemRailBinding) : RecyclerView.ViewHolder(b.root)
    class ChipVH(val b: ItemCatChipBinding) : RecyclerView.ViewHolder(b.root)
    class PosterVH(val b: ItemVodPosterBinding) : RecyclerView.ViewHolder(b.root)

    /** For GridLayoutManager span sizing. */
    fun isChip(position: Int) = items.getOrNull(position)?.chip == true
    fun isPoster(position: Int) = items.getOrNull(position)?.poster == true

    override fun getItemViewType(position: Int) = when {
        items[position].chip -> T_CHIP
        items[position].poster -> T_POSTER
        items[position].rail != null -> T_RAIL
        else -> T_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_RAIL -> RailVH(ItemRailBinding.inflate(inf, parent, false))
            T_CHIP -> ChipVH(ItemCatChipBinding.inflate(inf, parent, false))
            T_POSTER -> PosterVH(ItemVodPosterBinding.inflate(inf, parent, false))
            else -> VH(ItemChannelBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = items[position]
        when (holder) {
            is RailVH -> bindRail(holder, row)
            is ChipVH -> bindChip(holder, row)
            is PosterVH -> bindPoster(holder, row)
            else -> bindRow(holder as VH, row)
        }
    }

    private fun bindPoster(holder: PosterVH, row: ChannelsActivity.Row) {
        val url = row.iconUrl
        if (url.isNullOrEmpty()) holder.b.posterImg.setImageResource(R.drawable.thumb_placeholder)
        else holder.b.posterImg.load(url) {
            crossfade(true); placeholder(R.drawable.thumb_placeholder); error(R.drawable.thumb_placeholder)
        }
        holder.b.posterRoot.setOnClickListener { row.action() }
        val fav = row.fav
        if (fav == null) {
            holder.b.posterStar.visibility = View.GONE
            holder.b.posterRoot.setOnLongClickListener(null)
            holder.b.posterRoot.isLongClickable = false
        } else {
            holder.b.posterStar.visibility = View.VISIBLE
            val isF = fav.isFav()
            holder.b.posterStar.text = if (isF) "★" else "☆"
            holder.b.posterStar.setTextColor(if (isF) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
            val toggle = {
                val now = fav.toggle()
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                android.widget.Toast.makeText(holder.b.root.context,
                    if (now) "★  Added to Favourites" else "Removed from Favourites",
                    android.widget.Toast.LENGTH_SHORT).show()
                if (now) fav.onAdded?.invoke()
                onFavToggled?.invoke()
            }
            holder.b.posterStar.setOnClickListener { toggle() }
            holder.b.posterRoot.setOnLongClickListener { toggle(); true }
        }
    }

    private fun bindChip(holder: ChipVH, row: ChannelsActivity.Row) {
        val gold = row.label.contains("Favourites")
        holder.b.chipRoot.setBackgroundResource(if (gold) R.drawable.chip_bg_gold else R.drawable.chip_bg)
        holder.b.chipName.text = row.label
        holder.b.chipName.setTextColor(if (gold) 0xFF1A1A1A.toInt() else 0xFFE6EDF3.toInt())
        holder.b.chipChevron.setTextColor(if (gold) 0xFF1A1A1A.toInt() else 0xFF7A8A9A.toInt())
        holder.b.chipRoot.setOnClickListener { row.action() }
    }

    private fun bindRail(holder: RailVH, row: ChannelsActivity.Row) {
        holder.b.railTitle.text = row.label
        holder.b.railTitle.visibility = if (row.label.isBlank()) View.GONE else View.VISIBLE
        if (holder.b.railList.layoutManager == null) {
            holder.b.railList.layoutManager =
                LinearLayoutManager(holder.b.root.context, LinearLayoutManager.HORIZONTAL, false)
            holder.b.railList.isFocusable = false
        }
        holder.b.railList.adapter = CardAdapter(row.rail ?: emptyList())
    }

    private fun bindRow(holder: VH, row: ChannelsActivity.Row) {
        if (row.isHeader) {
            holder.b.name.text = row.label
            holder.b.name.setTextColor(0xFF19C37D.toInt())
            holder.b.thumb.visibility = View.GONE
            holder.b.star.visibility = View.GONE
            holder.b.clock.visibility = View.GONE
            holder.b.root.setOnKeyListener(null)
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
                if (now) fav.onAdded?.invoke() // newly added → ask which group
                onFavToggled?.invoke()
            }
            holder.b.star.setOnClickListener { toggle() }           // tap the star (touch)
            holder.b.root.setOnLongClickListener { toggle(); true }  // long-press OK / long-tap = favourite
        }
        // Catch-up clock (channels only): tap it, or press LEFT on the row to jump to it.
        val cu = row.catchup
        if (cu == null) {
            holder.b.clock.visibility = View.GONE
            holder.b.root.setOnKeyListener(null)
        } else {
            holder.b.clock.visibility = View.VISIBLE
            holder.b.clock.setOnClickListener { cu() }
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
        }
    }

    override fun getItemCount() = items.size

    companion object { const val T_ROW = 0; const val T_RAIL = 1; const val T_CHIP = 2; const val T_POSTER = 3 }

    /** Horizontal poster cards within a rail. */
    class CardAdapter(private val cards: List<ChannelsActivity.Card>) :
        RecyclerView.Adapter<CardAdapter.CVH>() {
        class CVH(val b: ItemCardBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CVH(ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = cards.size
        override fun onBindViewHolder(holder: CVH, position: Int) {
            val card = cards[position]
            val dp = holder.b.root.resources.displayMetrics.density
            val lp = holder.b.cardArt.layoutParams
            if (card.landscape) { lp.width = (220 * dp).toInt(); lp.height = (124 * dp).toInt() }
            else { lp.width = (130 * dp).toInt(); lp.height = (185 * dp).toInt() }
            holder.b.cardArt.layoutParams = lp
            holder.b.cardTitle.width = lp.width
            holder.b.cardTitle.text = card.title
            if (card.poster.isNullOrBlank()) holder.b.cardPoster.setImageResource(R.drawable.thumb_placeholder)
            else holder.b.cardPoster.load(card.poster) {
                crossfade(true); placeholder(R.drawable.thumb_placeholder); error(R.drawable.thumb_placeholder)
            }
            if (card.progress in 1..99) {
                holder.b.cardProgress.visibility = View.VISIBLE
                holder.b.cardProgress.progress = card.progress
            } else holder.b.cardProgress.visibility = View.GONE
            holder.b.cardRoot.setOnClickListener { card.onClick() }
            holder.b.cardRoot.setOnLongClickListener { card.onLongClick?.invoke(); card.onLongClick != null }
        }
    }
}
