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
    private var chosenAvatar = ""
    private var pendingCameraPath: String? = null
    private val liveBoxes = ArrayList<CheckBox>()
    private val vodBoxes = ArrayList<CheckBox>()
    private var fastScrolled = false

    /** Hold Up/Down ~1.5s to jump to the top (name + Select-all) / bottom (Save) of the long editor,
     *  so you don't have to D-pad through every category checkbox to reach those controls. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val kc = event.keyCode
        if (kc == android.view.KeyEvent.KEYCODE_DPAD_UP || kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == android.view.KeyEvent.ACTION_UP) fastScrolled = false
            else if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0 && !fastScrolled &&
                (event.eventTime - event.downTime) >= 1500L
            ) {
                val sv = b.root as? android.widget.ScrollView
                if (sv != null) {
                    fastScrolled = true
                    // Move focus to the target FIRST, then scroll — otherwise the ScrollView keeps the still-
                    // focused view (e.g. Save) on screen and snaps straight back to it.
                    if (kc == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        b.profileName.requestFocus(); sv.post { sv.smoothScrollTo(0, 0) }
                    } else {
                        b.saveBtn.requestFocus(); sv.post { sv.fullScroll(View.FOCUS_DOWN) }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private val pickGallery = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val a = Avatars.importFrom(this, uri)
            if (a != null) { chosenAvatar = a; refreshAvatar() } else toast("Couldn't load that image.")
        }
    }
    private val takePhoto = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        val p = pendingCameraPath
        if (ok && p != null) { chosenAvatar = Avatars.shrinkFile(this, p) ?: "file:$p"; refreshAvatar() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(b.root)

        editing = ContentProfiles.get(this, intent.getStringExtra("profileId"))
        editing?.let {
            b.editTitle.text = "Edit profile"
            b.profileName.setText(it.name)
            chosenColor = it.color
            chosenAvatar = it.avatar
            b.deleteBtn.visibility = View.VISIBLE
        }

        buildColorRow()
        buildEmojiRow()
        refreshAvatar()
        b.profileName.addTextChangedListener(simpleWatcher { refreshAvatar() })
        b.galleryBtn.setOnClickListener {
            try { pickGallery.launch("image/*") } catch (_: Exception) { toast("No gallery app available.") }
        }
        b.cameraBtn.setOnClickListener { launchCamera() }
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
            tv.setOnClickListener { chosenColor = c; refreshColorStrokes(); refreshAvatar() }
            colorSwatches.add(tv)
            b.colorRow.addView(tv)
        }
    }

    // ---- Avatar (picture) ----
    private fun buildEmojiRow() {
        b.emojiRow.removeAllViews()
        addEmojiTile("Aa", "")               // default: colour + name initial
        for (e in Avatars.EMOJI) addEmojiTile(e, "emoji:$e")
    }

    private fun addEmojiTile(display: String, value: String) {
        val dp = resources.displayMetrics.density
        val tv = android.widget.TextView(this)
        val size = (52 * dp).toInt()
        val lp = android.widget.LinearLayout.LayoutParams(size, size); lp.marginEnd = (8 * dp).toInt()
        tv.layoutParams = lp
        tv.gravity = android.view.Gravity.CENTER
        tv.textSize = 22f
        tv.text = display
        tv.setTextColor(0xFFE6EDF3.toInt())
        tv.isFocusable = true; tv.isClickable = true
        val bg = android.graphics.drawable.GradientDrawable()
        bg.shape = android.graphics.drawable.GradientDrawable.OVAL; bg.setColor(0x22FFFFFF)
        tv.background = bg
        tv.setOnFocusChangeListener { v, f -> val s = if (f) 1.2f else 1f; v.animate().scaleX(s).scaleY(s).setDuration(120).start() }
        tv.setOnClickListener { chosenAvatar = value; refreshAvatar() }
        b.emojiRow.addView(tv)
    }

    private fun refreshAvatar() {
        val initial = b.profileName.text?.toString()?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Avatars.render(b.avatarPreview, chosenAvatar, chosenColor, initial, false)
        b.avatarPreview.textSize = if (chosenAvatar.startsWith("emoji:")) 30f else 22f
    }

    private fun launchCamera() {
        try {
            val dir = java.io.File(getExternalFilesDir(null), "avatars").apply { mkdirs() }
            val f = java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
            pendingCameraPath = f.absolutePath
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            takePhoto.launch(uri)
        } catch (e: Exception) { toast("Camera not available: ${e.message}") }
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
            it.name = name; it.color = chosenColor; it.avatar = chosenAvatar
            it.allLive = allLive; it.liveCats = if (allLive) LinkedHashSet() else liveChecked
            it.allVod = allVod; it.vodCats = if (allVod) LinkedHashSet() else vodChecked
        } ?: ContentProfiles.Profile(
            ContentProfiles.newId(), name, chosenColor,
            allLive, if (allLive) LinkedHashSet() else liveChecked,
            allVod, if (allVod) LinkedHashSet() else vodChecked,
            chosenAvatar
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
