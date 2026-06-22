package com.stalkertv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.stalkertv.app.databinding.ActivityChannelsBinding
import java.util.concurrent.Executors

class ChannelsActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var b: ActivityChannelsBinding
    private val adapter = ChannelAdapter { ch -> openChannel(ch) }
    private var all: List<Portal.Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityChannelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        b.status.text = "Loading channels…"
        io.execute {
            val ch = Portal.liveChannels()
            runOnUiThread {
                all = ch
                if (ch.isEmpty()) {
                    b.status.text = "No channels returned. (Portal auth or listing issue.)"
                } else {
                    b.status.visibility = View.GONE
                    adapter.submit(ch)
                    b.list.requestFocus()
                }
            }
        }
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase()
        adapter.submit(if (query.isEmpty()) all else all.filter { it.name.lowercase().contains(query) })
    }

    private fun openChannel(ch: Portal.Channel) {
        b.status.visibility = View.VISIBLE
        b.status.text = "Opening ${ch.name}…"
        io.execute {
            val url = Portal.createLink(ch.cmd)
            runOnUiThread {
                if (url.isNullOrEmpty()) {
                    b.status.text = "Couldn't open ${ch.name}."
                } else {
                    b.status.visibility = View.GONE
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", ch.name)
                    )
                }
            }
        }
    }
}
