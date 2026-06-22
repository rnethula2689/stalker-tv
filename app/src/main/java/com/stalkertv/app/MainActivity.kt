package com.stalkertv.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stalkertv.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.status.text = "Stalker TV v0.1\nBuild + install OK ✓\nNext step: portal login + channel list."
        b.testBtn.setOnClickListener {
            b.status.text = "Remote D-pad + OK button work ✓\nReady for the next milestone."
        }
        b.testBtn.requestFocus()
    }
}
