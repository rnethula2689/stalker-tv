package com.stalkertv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivityProfileEditBinding
import java.util.concurrent.Executors

/** Create / edit a content profile: name, colour, and which Live + Movie categories it shows. */
class ProfileEditActivity : AppCompatActivity() {
    private lateinit var b: ActivityProfileEditBinding
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var editing: ContentProfiles.Profile? = null
    private var chosenColor = ContentProfiles.COLORS[0]
    private val liveBoxes = ArrayList<CheckBox>()
    private val vodBoxes = ArrayList<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(b.root)

        editing = ContentProfiles.get(this, intent.getStringExtra("profileId"))
        editing?.let {
            b.editTitle.text = "Edit profile"
            b.profileName.setText(it.name)
            chosenColor = it.color
            b.deleteBtn.visibility = View.VISIBLE
        }

        buildColorRow()
        b.liveAllBtn.setOnClickListener { val v = visible(liveBoxes); setAll(v, !(v.isNotEmpty() && v.all { it.isChecked })); updateAllLabels() }
        b.vodAllBtn.setOnClickListener { val v = visible(vodBoxes); setAll(v, !(v.isNotEmpty() && v.all { it.isChecked })); updateAllLabels() }
        b.saveBtn.setOnClickListener { save() }
        b.deleteBtn.setOnClickListener { confirmDelete() }
        b.liveSearch.addTextChangedListener(simpleWatcher { filterList(liveBoxes, it) })
        b.vodSearch.addTextChangedListener(simpleWatcher { filterList(vodBoxes, it) })

        loadCategories()
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
        override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
    }

    private val colorSwatches = ArrayList<android.widget.TextView>()

    private fun buildColorRow() {
        b.colorRow.removeAllViews()
        colorSwatches.clear()
        val dp = resources.displayMetrics.density
        for (c in ContentProfiles.COLORS) {
            val tv = android.widget.TextView(this)
            val size = (44 * dp).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.marginEnd = (12 * dp).toInt()
            tv.layoutParams = lp
            tv.background = circle(c, c == chosenColor)
            tv.isFocusable = true
            tv.isClickable = true
            // Scale up on focus so the remote user can clearly see which swatch is selected.
            tv.setOnFocusChangeListener { v, f ->
                val s = if (f) 1.3f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
            // Update strokes in place (don't rebuild the row, which would drop focus).
            tv.setOnClickListener { chosenColor = c; refreshColorStrokes() }
            colorSwatches.add(tv)
            b.colorRow.addView(tv)
        }
    }

    private fun refreshColorStrokes() {
        for ((i, c) in ContentProfiles.COLORS.withIndex())
            colorSwatches.getOrNull(i)?.background = circle(c, c == chosenColor)
    }

    private fun circle(color: Int, selected: Boolean): android.graphics.drawable.GradientDrawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(color)
        if (selected) d.setStroke((3 * resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
        return d
    }

    private fun loadCategories() {
        var genres = ChannelsActivity.catGenres()
        var vodCats = ChannelsActivity.catVodCats()
        if (genres.isNotEmpty() && vodCats.isNotEmpty()) {
            populate(genres, vodCats); return
        }
        b.loadingNote.visibility = View.VISIBLE
        io.execute {
            if (genres.isEmpty()) genres = Portal.liveGenres()
            if (vodCats.isEmpty()) vodCats = Portal.vodCategories()
            ChannelsActivity.cacheCatalog(genres, vodCats)
            ui.post { b.loadingNote.visibility = View.GONE; populate(genres, vodCats) }
        }
    }

    private fun populate(genres: List<Portal.Genre>, vodCats: List<Portal.VodCat>) {
        val p = editing
        for (g in genres) {
            val checked = p == null || p.allLive || p.liveCats.contains(g.id)
            liveBoxes.add(addBox(b.liveList, g.title + (if (g.censored) "  🔒" else ""), g.id, checked))
        }
        for (c in vodCats) {
            val checked = p == null || p.allVod || p.vodCats.contains(c.id)
            vodBoxes.add(addBox(b.vodList, c.title, c.id, checked))
        }
        updateAllLabels()
    }

    private fun addBox(parent: android.widget.LinearLayout, label: String, id: String, checked: Boolean): CheckBox {
        val cb = CheckBox(this)
        cb.text = label
        cb.tag = id
        cb.isChecked = checked
        cb.setTextColor(0xFFE6EDF3.toInt())
        cb.textSize = 15f
        cb.isFocusable = true
        val pad = (6 * resources.displayMetrics.density).toInt()
        cb.setPadding(cb.paddingLeft, pad, pad, pad)
        cb.setOnCheckedChangeListener { _, _ -> updateAllLabels() }
        parent.addView(cb)
        return cb
    }

    private fun visible(boxes: List<CheckBox>) = boxes.filter { it.visibility == View.VISIBLE }
    private fun setAll(boxes: List<CheckBox>, value: Boolean) { for (cb in boxes) cb.isChecked = value }

    /** Filter a list's checkboxes by the search query (visibility only — checked state is preserved). */
    private fun filterList(boxes: List<CheckBox>, q: String) {
        val query = q.trim().lowercase()
        for (cb in boxes) cb.visibility =
            if (query.isEmpty() || cb.text.toString().lowercase().contains(query)) View.VISIBLE else View.GONE
        updateAllLabels()
    }

    /** "Select all" ↔ "Deselect all" depending on whether every *visible* box is already ticked. */
    private fun updateAllLabels() {
        val liveV = visible(liveBoxes)
        val vodV = visible(vodBoxes)
        b.liveAllBtn.text = if (liveV.isNotEmpty() && liveV.all { it.isChecked }) "Deselect all" else "Select all"
        b.vodAllBtn.text = if (vodV.isNotEmpty() && vodV.all { it.isChecked }) "Deselect all" else "Select all"
    }

    private fun save() {
        val name = b.profileName.text?.toString()?.trim().orEmpty().ifBlank { "Profile" }
        val liveChecked = liveBoxes.filter { it.isChecked }.map { it.tag as String }.toMutableSet()
        val vodChecked = vodBoxes.filter { it.isChecked }.map { it.tag as String }.toMutableSet()
        if (liveChecked.isEmpty() && vodChecked.isEmpty()) {
            android.widget.Toast.makeText(this, "Pick at least one category.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val allLive = liveBoxes.isNotEmpty() && liveChecked.size == liveBoxes.size
        val allVod = vodBoxes.isNotEmpty() && vodChecked.size == vodBoxes.size
        val p = editing?.also {
            it.name = name; it.color = chosenColor
            it.allLive = allLive; it.liveCats = if (allLive) LinkedHashSet() else liveChecked
            it.allVod = allVod; it.vodCats = if (allVod) LinkedHashSet() else vodChecked
        } ?: ContentProfiles.Profile(
            ContentProfiles.newId(), name, chosenColor,
            allLive, if (allLive) LinkedHashSet() else liveChecked,
            allVod, if (allVod) LinkedHashSet() else vodChecked
        )
        ContentProfiles.save(this, p)
        // First profile created becomes active right away.
        if (ContentProfiles.activeId(this) == null) ContentProfiles.setActive(this, p.id)
        finish()
    }

    private fun confirmDelete() {
        val p = editing ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete “${p.name}”?")
            .setPositiveButton("Delete") { _, _ -> ContentProfiles.delete(this, p.id); finish() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
