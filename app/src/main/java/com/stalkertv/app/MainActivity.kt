package com.stalkertv.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        b.portal.setText(prefs.getString("portal", ""))
        b.mac.setText(prefs.getString("mac", ""))
        b.serial.setText(prefs.getString("sn", ""))

        b.connect.setOnClickListener {
            val url = b.portal.text.toString().trim()
            val mac = b.mac.text.toString().trim()
            val sn = b.serial.text.toString().trim()
            if (url.isEmpty() || mac.isEmpty()) {
                b.status.text = "Enter both the portal URL and the MAC address."
                return@setOnClickListener
            }
            prefs.edit().putString("portal", url).putString("mac", mac).putString("sn", sn).apply()
            Portal.portalUrl = url
            Portal.mac = mac
            Portal.sn = sn
            b.status.text = "Connecting…"
            b.connect.isEnabled = false
            io.execute {
                val err = Portal.connect()
                runOnUiThread {
                    b.connect.isEnabled = true
                    if (err == null) {
                        b.status.text = "Connected ✓"
                        startActivity(Intent(this, ChannelsActivity::class.java))
                    } else {
                        b.status.text = err
                    }
                }
            }
        }

        b.portal.requestFocus()
    }
}
