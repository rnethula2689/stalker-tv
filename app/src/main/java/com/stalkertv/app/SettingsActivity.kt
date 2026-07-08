package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivitySettingsBinding
import java.util.concurrent.Executors

/** One place for everything: a hub of sections, each opening its own screen or dialog. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val epgIo = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rowProviders.setOnClickListener { startActivity(Intent(this, ProvidersActivity::class.java)) }
        b.rowProfiles.setOnClickListener { showProfilesDialog() }
        b.rowPersonalization.setOnClickListener { showPersonalizationDialog() }
        b.rowRemote.setOnClickListener { showRemoteDialog() }
        b.rowPin.setOnClickListener { showParentalPinDialog() }
        b.rowPlayback.setOnClickListener { PlaybackSettings.show(this) }
        b.rowSleep.setOnClickListener { SleepTimer.showDialog(this, closeApp = true) }
        b.rowStorage.setOnClickListener { showStorageDialog() }
        b.rowSubs.setOnClickListener { showSubtitlesDialog() }
        b.rowEpg.setOnClickListener { showEpgDialog() }
        b.rowUpdates.setOnClickListener { startActivity(Intent(this, AppUpdatesActivity::class.java)) }
        b.rowBackup.setOnClickListener { showBackupDialog() }
        b.rowDiag.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        b.rowHelp.setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }
        b.rowAbout.setOnClickListener { About.show(this) }
        b.rowExit.setOnClickListener { finishAffinity() }

        b.rowProviders.requestFocus()
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun padded(v: View): FrameLayout {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(v) }
    }

    // ---- Profiles ----
    private fun showProfilesDialog() {
        val profiles = ContentProfiles.list(this)
        val activeId = ContentProfiles.activeId(this)
        val labels = profiles.map { (if (it.id == activeId) "✓  " else "     ") + it.name }.toMutableList()
        labels.add("➕  New profile")
        AlertDialog.Builder(this)
            .setTitle("Profiles")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w >= profiles.size) startActivity(Intent(this, ProfileEditActivity::class.java))
                else profileActions(profiles[w])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun profileActions(p: ContentProfiles.Profile) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("✓  Use this profile", "✏  Edit", "🗑  Delete")) { _, w ->
                when (w) {
                    0 -> { ContentProfiles.setActive(this, p.id); Configs.dirty = true; toast("Switched to “${p.name}”.") }
                    1 -> startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("profileId", p.id))
                    2 -> { ContentProfiles.delete(this, p.id); Configs.dirty = true; toast("Deleted “${p.name}”.") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Personalization (Home rails) ----
    private fun showPersonalizationDialog() {
        val items = arrayOf("Hide “Recently Added” on Home", "Hide “For You” on Home")
        val checked = booleanArrayOf(Configs.hideRecentlyAdded(this), Configs.hideForYou(this))
        AlertDialog.Builder(this)
            .setTitle("Personalization")
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                Configs.setHideRecentlyAdded(this, checked[0])
                Configs.setHideForYou(this, checked[1])
                toast("Home updated. Changes show next time you open Home.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Remote control (key mapping) ----
    private fun showRemoteDialog() {
        val actions = RemoteMap.ACTIONS.entries.toList()
        val labels = actions.map { "${it.value}\n     [ ${RemoteMap.keyName(RemoteMap.keyFor(this, it.key))} ]" }.toMutableList()
        labels.add("↺  Reset all to default")
        labels.add("ℹ️  Which buttons can't be mapped?")
        AlertDialog.Builder(this)
            .setTitle("Remote control — map keys")
            .setItems(labels.toTypedArray()) { _, w ->
                when {
                    w == actions.size -> { RemoteMap.clearAll(this); toast("All remote keys reset to default.") }
                    w > actions.size -> AlertDialog.Builder(this)
                        .setTitle("Buttons Fire TV keeps for itself")
                        .setMessage("Volume, Mute, Home, the ⊡ (mirror) button and the app-shortcut buttons " +
                            "(Netflix, Prime Video, Disney+, Hulu) are handled by Fire TV / your TV itself and are " +
                            "never sent to apps — so they can't be remapped here.\n\nMappable keys: D-pad, OK, Menu, " +
                            "media Play/Pause & Rewind/Fast-forward, and Channel +/- on remotes that have them.")
                        .setPositiveButton("OK", null).show()
                    else -> captureKey(actions[w].key, actions[w].value)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** A button-less dialog so it captures EVERY key (including D-pad/OK); Back cancels. */
    private fun captureKey(action: String, label: String) {
        val msg = TextView(this).apply {
            text = "Press the remote key you want for:\n\n“$label”\n\n(Press Back to cancel.)"
            textSize = 16f; setTextColor(0xFFE6EDF3.toInt())
        }
        val dlg = AlertDialog.Builder(this).setTitle("Map a key").setView(padded(msg)).create()
        dlg.setOnKeyListener { d, keyCode, ev ->
            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_BACK -> d.dismiss()
                android.view.KeyEvent.KEYCODE_HOME -> { /* ignore — can't map Home */ }
                else -> {
                    RemoteMap.setKey(this, action, keyCode)
                    toast("“$label”  →  ${RemoteMap.keyName(keyCode)}")
                    d.dismiss()
                    showRemoteDialog() // reopen so the user sees the update / maps the next one
                }
            }
            true
        }
        dlg.show()
    }

    // ---- Parental PIN ----
    private fun pinInput(hint: String) = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        this.hint = hint
    }

    private fun showParentalPinDialog() {
        if (!Configs.hasParentalPin(this)) { promptNewPin("Set a parental PIN"); return }
        val cur = pinInput("Current PIN")
        AlertDialog.Builder(this)
            .setTitle("Parental PIN")
            .setMessage("Locks adult / restricted channels.")
            .setView(padded(cur))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Change PIN") { _, _ ->
                if (Configs.verifyParentalPin(this, cur.text.toString().trim())) promptNewPin("New parental PIN")
                else { val s = Configs.parentalPinLockSecs(this); toast(if (s > 0) "Too many attempts — wait ${s}s." else "Incorrect PIN.") }
            }
            .show()
    }

    private fun promptNewPin(title: String) {
        val next = pinInput("New PIN (min 3 digits)")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(padded(next))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = next.text.toString().trim()
                if (n.length >= 3) { Configs.setParentalPin(this, n); toast("Parental PIN saved ✓") }
                else toast("PIN must be at least 3 digits.")
            }
            .show()
    }

    // ---- Storage ----
    private fun showStorageDialog() {
        val items = arrayOf("Cache", "Favourites", "Watch Later", "Continue Watching", "Downloads", "Recordings")
        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this)
            .setTitle("Storage — clear data")
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Clear selected") { _, _ ->
                if (checked.any { it }) confirmClear(items, checked) else toast("Nothing selected.")
            }
            .setNeutralButton("Clear ALL") { _, _ -> confirmClear(items, BooleanArray(items.size) { true }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear(items: Array<String>, sel: BooleanArray) {
        val names = items.filterIndexed { i, _ -> sel[i] }.joinToString(", ")
        AlertDialog.Builder(this)
            .setTitle("Clear $names?")
            .setMessage("This permanently removes the selected data on this device.")
            .setPositiveButton("Clear") { _, _ -> doClear(sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doClear(sel: BooleanArray) {
        if (sel[0]) try { cacheDir.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}
        if (sel[1]) { Favorites.clearAll(this); Configs.clearFavorites(this) }
        if (sel[2]) WatchLater.clearAll(this)
        if (sel[3]) Resume.clearAll(this)
        if (sel[4]) Downloads.deleteAll(this)
        if (sel[5]) Recordings.deleteAll(this)
        toast("Cleared.")
    }

    // ---- Subtitles ----
    private fun showSubtitlesDialog() {
        val input = EditText(this).apply {
            setText(Configs.ossKey(this@SettingsActivity))
            hint = "OpenSubtitles API key"
            setSingleLine()
        }
        val (savedN, savedBytes) = SubStore.stats(this)
        val clearLabel = if (savedN > 0) "🗑  Clear saved subtitles ($savedN · ${savedBytes / 1024} KB)" else "🗑  Clear saved subtitles"
        AlertDialog.Builder(this)
            .setTitle("Subtitles (OpenSubtitles API key)")
            .setView(padded(input))
            .setNeutralButton(clearLabel) { _, _ -> confirmClearSubs() }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                Configs.setOssKey(this, key); Subtitles.apiKey = key
                toast(if (key.isEmpty()) "Subtitle key cleared." else "Subtitle key saved ✓")
            }
            .show()
    }

    /** Wipe the per-movie saved subtitles (they auto-download again on next play). */
    private fun confirmClearSubs() {
        val (n, bytes) = SubStore.stats(this)
        if (n == 0) { toast("No saved subtitles to clear."); return }
        AlertDialog.Builder(this)
            .setTitle("Clear saved subtitles?")
            .setMessage("Remove $n saved subtitle${if (n == 1) "" else "s"} (${bytes / 1024} KB)? Movies will search & download again next time you turn subtitles on.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> toast("Cleared ${SubStore.clearAll(this)} saved subtitle(s).") }
            .show()
    }

    // ---- EPG (external XMLTV) ----
    private fun showEpgDialog() {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val urlField = EditText(this).apply {
            setText(Configs.epgXmltvUrl(this@SettingsActivity))
            hint = "https://example.com/epg.xml(.gz)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val status = TextView(this).apply { setTextColor(0xFF9fb0c0.toInt()); textSize = 14f }
        val note = TextView(this).apply {
            text = "Blank = use the portal's own guide. If set, the TV Guide prefers this source (matched by channel name). Supports .xml and .xml.gz."
            setTextColor(0xFF8b97a5.toInt()); textSize = 12f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(urlField); addView(note); addView(status)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("TV Guide (external XMLTV)")
            .setView(box)
            .setPositiveButton("Save & test", null) // overridden below so the dialog stays open
            .setNeutralButton("Clear") { _, _ ->
                Configs.setEpgXmltvUrl(this, ""); urlField.setText(""); toast("Cleared — using the portal's guide.")
            }
            .setNegativeButton("Close", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = urlField.text.toString().trim()
                Configs.setEpgXmltvUrl(this, url)
                if (url.isEmpty()) { status.text = "Cleared — using the portal's guide."; return@setOnClickListener }
                status.text = "Downloading & testing…"
                epgIo.execute {
                    val ok = XmltvEpg.ensureLoaded(this, force = true)
                    runOnUiThread { status.text = XmltvEpg.lastStatus + (if (ok) "" else "\nThe portal guide will be used as a fallback.") }
                }
            }
        }
        dlg.show()
    }

    // ---- Sync & Backup ----
    private fun showBackupDialog() {
        // Note: an AlertDialog shows EITHER a message OR a list — setting a message hides setItems().
        AlertDialog.Builder(this)
            .setTitle("Sync & Backup")
            .setItems(arrayOf("⬆  Back up my data", "⬇  Restore last backup (this device)", "📂  Restore from a file…", "🗑  Delete backup file")) { _, w ->
                when (w) {
                    0 -> doBackup()
                    1 -> doRestoreLocal()
                    2 -> try { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
                        catch (e: Exception) { toast("Couldn't open the file picker: ${e.message}") }
                    3 -> toast(if (Backup.deleteFile(this)) "Backup file deleted." else "No backup file to delete.")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Restore directly from the backup this device made (no file picker — the app reads its own file).
     *  The picker often shows "empty" because the backup lives in the app's private folder. */
    private fun doRestoreLocal() {
        val f = Backup.backupFile(this)
        if (!f.exists()) { toast("No backup on this device yet. Use “Back up my data” first, or “Restore from a file”."); return }
        try {
            val r = Backup.importJson(this, f.readText())
            toast("Restored ✓  +${r.favorites} favourites, +${r.watchLater} watch-later, +${r.resume} continue-watching")
        } catch (e: Exception) { toast("Restore failed: ${e.message}") }
    }

    private fun doBackup() {
        val f = try { Backup.writeToFile(this) } catch (e: Exception) { toast("Backup failed: ${e.message}"); return }
        toast("Saved ✓  ${f.absolutePath}")
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (send.resolveActivity(packageManager) != null) startActivity(Intent.createChooser(send, "Share backup"))
        } catch (_: Exception) { /* no share target (TV) — file is already saved */ }
    }

    /** SAF picker for restoring a backup file → merge into the current account/profile. */
    private val openBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val r = Backup.importJson(this, text)
            toast("Restored ✓  +${r.favorites} favourites, +${r.watchLater} watch-later, +${r.resume} continue-watching")
        } catch (e: Exception) { toast("Restore failed: ${e.message}") }
    }
}
