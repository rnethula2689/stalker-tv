package com.stalkertv.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Trampoline for Settings ▸ "Restart app". Runs in its OWN process (":restart", see the manifest):
 * the main process can die underneath it while this activity — being foreground — is still allowed
 * to start activities. (The previous AlarmManager approach was blocked by background-activity-launch
 * restrictions on Fire OS: the app exited but never came back.)
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kill the main process (its pid rides in on the intent), give it a beat to fully die so the
        // relaunch is a true cold start, then bring the app back and remove this helper process too.
        val mainPid = intent.getIntExtra("pid", -1)
        if (mainPid > 0) try { android.os.Process.killProcess(mainPid) } catch (_: Exception) {}
        try { Thread.sleep(350) } catch (_: Exception) {}
        startActivity(
            Intent(this, ChannelsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
        Runtime.getRuntime().exit(0)
    }
}
