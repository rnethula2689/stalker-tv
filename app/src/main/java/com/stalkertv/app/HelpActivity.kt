package com.stalkertv.app

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.stalkertv.app.databinding.ActivityHelpBinding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** In-app user guide (remote-navigable) with one-tap export to a PDF that can be shared/downloaded. */
class HelpActivity : AppCompatActivity() {
    private lateinit var b: ActivityHelpBinding
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.title.text = UserGuide.TITLE
        b.guide.text = UserGuide.TEXT
        b.guide.movementMethod = ScrollingMovementMethod() // d-pad / touch scroll
        b.guide.isFocusable = true
        b.savePdfBtn.setOnClickListener { exportPdf(share = false) }
        b.sharePdfBtn.setOnClickListener { exportPdf(share = true) }
        b.savePdfBtn.requestFocus()
    }

    /** D-pad up/down PAGE-scrolls the guide (line-by-line was painfully slow on a remote); at the very
     *  top/bottom the key falls through so focus can still leave the guide to the buttons. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && b.guide.hasFocus()) {
            val page = (b.guide.height * 0.85f).toInt().coerceAtLeast(120)
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP ->
                    if (b.guide.canScrollVertically(-1)) { b.guide.scrollBy(0, -page); return true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (b.guide.canScrollVertically(1)) { b.guide.scrollBy(0, page); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND ->
                    { b.guide.scrollTo(0, 0); return true }   // jump to top
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()

    private fun exportPdf(share: Boolean) {
        b.savePdfBtn.isEnabled = false; b.sharePdfBtn.isEnabled = false
        io.execute {
            val f = try { buildPdf() } catch (e: Exception) {
                runOnUiThread { toast("Couldn't create PDF: ${e.message}"); b.savePdfBtn.isEnabled = true; b.sharePdfBtn.isEnabled = true }
                return@execute
            }
            runOnUiThread {
                b.savePdfBtn.isEnabled = true; b.sharePdfBtn.isEnabled = true
                toast("Saved PDF:\n${f.absolutePath}")
                if (share) try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                    val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (i.resolveActivity(packageManager) != null) startActivity(Intent.createChooser(i, "Share user guide"))
                    else toast("No app to share with — the PDF is saved on the device.")
                } catch (_: Exception) { /* file is already saved */ }
            }
        }
    }

    /** Render the guide text into a paginated A4 PDF. */
    private fun buildPdf(): File {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 10f; color = 0xFF111111.toInt() }
        val pageW = 595; val pageH = 842; val margin = 36f; val lineH = 14f
        val maxW = pageW - margin * 2
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas; var y = margin + lineH
        fun newPage() {
            doc.finishPage(page); pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
            canvas = page.canvas; y = margin + lineH
        }
        for (raw in UserGuide.TEXT.lines()) {
            for (ln in wrap(raw, paint, maxW)) {
                if (y > pageH - margin) newPage()
                canvas.drawText(ln, margin, y, paint); y += lineH
            }
        }
        doc.finishPage(page)
        val dir = File(getExternalFilesDir(null), "guide").apply { mkdirs() }
        val out = File(dir, "VibeTV-User-Guide.pdf")
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** Word-wrap a line to fit maxW, preferring to break at spaces. */
    private fun wrap(line: String, paint: Paint, maxW: Float): List<String> {
        if (line.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        var start = 0
        while (start < line.length) {
            var count = paint.breakText(line, start, line.length, true, maxW, null)
            if (count <= 0) count = 1
            var end = start + count
            if (end < line.length) {
                val lastSpace = line.lastIndexOf(' ', end - 1)
                if (lastSpace > start) end = lastSpace + 1
            }
            out.add(line.substring(start, end).trimEnd())
            start = end
        }
        return out
    }

    override fun onDestroy() { super.onDestroy(); io.shutdownNow() }
}
