package com.stalkertv.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivityDiagnosticsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Diagnostics: a read-only health screen for troubleshooting — app/device, network, portal session,
 * loaded catalog counts, plus a "Test portal connection" button and the local "reported issues" log.
 */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var b: ActivityDiagnosticsBinding
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.testBtn.setOnClickListener { testConnection() }
        b.clearReportsBtn.setOnClickListener { Reports.clear(this); refresh() }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        b.report.text = buildReport()
        val reps = Reports.all(this)
        b.reports.text = if (reps.isEmpty()) "None reported."
        else reps.take(20).joinToString("\n") { "• ${it.title}   (${fmtDate(it.ts)})" } +
            (if (reps.size > 20) "\n…and ${reps.size - 20} more" else "")
    }

    private fun line(label: String, value: String) = "$label:  $value\n"

    private fun buildReport(): String {
        val sb = StringBuilder()
        // App + device
        val ver = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName} (build $code)"
        } catch (_: Exception) { "?" }
        sb.append(line("App", "${getString(R.string.app_name)}  $ver"))
        sb.append(line("Device", "${Build.MANUFACTURER} ${Build.MODEL}"))
        sb.append(line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        sb.append(line("Network", networkStatus()))
        sb.append("\n")
        // Portal / account
        val acct = Configs.active(this)
        if (acct == null) {
            sb.append(line("Provider", "none configured"))
        } else {
            sb.append(line("Provider", acct.name))
            sb.append(line("Portal URL", acct.portal))
            sb.append(line("MAC", acct.mac))
            sb.append(line("Serial", if (acct.sn.isBlank()) "(not set)" else acct.sn))
        }
        sb.append(line("Session", if (Portal.isConnected()) "connected ✓" else "not connected"))
        if (Portal.lastError.isNotBlank()) sb.append(line("Last portal note", Portal.lastError))
        sb.append("\n")
        // Catalog
        sb.append(line("Live channels", ChannelsActivity.allChannelsCatalog().size.toString()))
        sb.append(line("Live categories", ChannelsActivity.catGenres().size.toString()))
        sb.append(line("Movie/series categories", ChannelsActivity.catVodCats().size.toString()))
        // EPG
        sb.append(line("External XMLTV", if (Configs.epgXmltvUrl(this).isBlank()) "off (portal EPG)" else "on"))
        return sb.toString().trimEnd()
    }

    private fun networkStatus(): String = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 23) {
            val n = cm.activeNetwork
            val caps = if (n != null) cm.getNetworkCapabilities(n) else null
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Online (Wi-Fi)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Online (Cellular)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Online (Ethernet)"
                else -> "Online"
            }
        } else {
            @Suppress("DEPRECATION") val ni = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (ni != null && ni.isConnected) "Online (${ni.typeName})" else "Offline"
        }
    } catch (_: Exception) { "Unknown" }

    private fun testConnection() {
        b.testResult.text = "Testing…"
        b.testBtn.isEnabled = false
        io.execute {
            val err = try { Portal.connect() } catch (e: Exception) { "Error: ${e.message}" }
            runOnUiThread {
                b.testBtn.isEnabled = true
                b.testResult.text = if (err == null) "✓  Portal reachable & authenticated" else "✗  $err"
                refresh()
            }
        }
    }

    private fun fmtDate(ts: Long) = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ts))

    override fun onDestroy() { super.onDestroy(); io.shutdownNow() }
}
