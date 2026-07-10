package com.stalkertv.app

/** The complete Vibe TV user guide — one plain-text source shown in HelpActivity and exported to PDF. */
object UserGuide {
    const val TITLE = "Vibe TV — User Guide"

    val TEXT = """
Vibe TV — USER GUIDE
====================
Your complete step-by-step guide to every feature. Use this any time from
Settings > User guide. Tap "Save as PDF" to keep an offline copy.


1. GETTING STARTED — ADD YOUR PROVIDER
--------------------------------------
Vibe TV plays channels and movies from your own IPTV provider (a Stalker /
Ministra portal). You enter the details once.
  1. Open Settings (gear icon, top-right of Home — or the MENU button on a
     TV remote).
  2. Choose "IPTV configuration".
  3. Tap "Add provider" and enter:
       - Portal URL  (from your provider, e.g. http://example.com/c/)
       - MAC address (the one registered with your provider)
       - Serial Number (if your provider uses one)
  4. Tap Submit. Channels and movies load automatically.
  5. To change or add another provider later, return to the same screen.
Tip: if nothing loads, see section 20 (Troubleshooting).


2. GETTING AROUND (REMOTE & TOUCH)
----------------------------------
On a TV remote:
  - D-pad arrows move the highlight; OK/Center selects.
  - BACK goes up one level or closes a screen.
  - The MENU button opens Settings from Home.
  - While watching live, UP/DOWN change channel.
On a touch tablet: just tap. Long-press a poster for more options.
You can re-assign remote buttons — see section 19 (Remote control).


3. THE HOME SCREEN
------------------
Home shows rows ("rails") built from what you watch:
  - Continue Watching — resume movies/episodes where you left off.
  - Favourites — channels and titles you starred.
  - Watch Later — your saved list.
  - For You — movie picks based on what you play.
  - Recently Added — the newest movies from your provider.
Along the bottom is the navigation bar:
  Live TV  -  VOD  -  Favourites  -  Watch Later  -  Recordings  -  Downloads
Top-right icons: profile, search, refresh, and Settings (gear).
You can hide the "For You" and "Recently Added" rows — see section 18.


4. LIVE TV
----------
  1. From Home, select "Live TV".
  2. Pick a category (e.g. News, Sports) or "All Channels".
  3. Select a channel to start watching; select the video to go full screen.
  4. Full screen:
       - UP / DOWN  = previous / next channel.
       - Tap (or OK) = show/hide the on-screen controls.
       - A slim "Now Playing" bar shows the current programme, a progress
         bar, and what's next. It hides itself after a few seconds.
  5. The top bar has aspect ratio, volume, brightness, and a menu (the three
     dots) for Retry, Report, Cast, Sleep timer, and Settings.


5. TV GUIDE (EPG)
-----------------
  1. Live TV > "TV Guide - what's on now".
  2. Each channel shows its Now / Next programme.
  3. Select the schedule (calendar) on a row to see the full day.
  4. In the full-day list you can:
       - Watch a programme that is on now.
       - Set a reminder for an upcoming programme (you'll get a notification
         about a minute before it starts).
       - Watch a past programme if your provider offers catch-up (see 6).
Optional: use your own guide source — Settings > TV Guide (EPG), section 18.


6. CATCH-UP & TIMESHIFT (REWIND LIVE TV)
----------------------------------------
If your provider supports archive/catch-up:
  - In the TV Guide's full-day list, select a past programme to play it.
  - While watching live, press LEFT (or use the on-screen rewind) to pause/
    rewind the live stream (timeshift). Use the slider to scrub; "Go Live"
    returns to the live edge.


7. RECORDING
------------
While watching a live channel, open the player menu (three dots) and use the
record option (where supported). Saved recordings appear under Home > Recordings.


8. MULTI-VIEW (WATCH SEVERAL AT ONCE)
-------------------------------------
From the live player menu, open Multi-view to watch multiple channels in a
grid. Select a pane to make it the active audio/full screen.


9. VOD (MOVIES, SERIES & DOCUMENTARIES)
------------------------
  1. From Home, select "VOD".
  2. Browse categories, or use Search (top-right) to find a title.
  3. Select a title to open its DETAILS screen — backdrop, poster, genre,
     ratings (IMDb plus Rotten Tomatoes 🍅 where available), runtime, full
     overview, a Trailers rail and a Cast rail, with Play plus icon buttons
     for Favourite (★, turns gold when on), Watch later (＋) and Download (📥).
  4. For a SERIES, the details screen shows a Season selector and an episode
     list — pick an episode to play it.
  The screen adapts to portrait or landscape automatically.


10. FAVOURITES
--------------
  - Star a channel or title to add it to Favourites (long-press a poster, or
    use the heart/star where shown).
  - Find them on Home > Favourites, or the Favourites tab.


11. WATCH LATER
---------------
  - Add movies/episodes to Watch Later from their options menu.
  - Open the Watch Later tab to search, sort (Newest / A-Z / Z-A), or export
    your list.


12. CONTINUE WATCHING
---------------------
Anything you start is saved automatically. Resume it from the Continue
Watching row on Home. Long-press an item to remove it.


13. DOWNLOADS (WATCH OFFLINE)
-----------------------------
  - From a movie/episode's options, choose Download.
  - Track progress and play finished downloads from Home > Downloads.
  - Downloaded items play without internet.


14. RECORDINGS
--------------
Your live recordings are listed under Home > Recordings — select to play,
or remove to free up space.


15. CASTING TO ANOTHER SCREEN
-----------------------------
From the player menu choose "Cast to TV" to send the stream to a DLNA or
Chromecast device on your network. (Casting is not available on Fire OS
devices, which have no Google services.)


16. SUBTITLES
-------------
  - In the movie player menu, choose Subtitles to open the subtitle search:
    the movie's title is pre-filled (edit it if needed), pick a LANGUAGE from
    the dropdown (remembered for next time), and press Search. Each result
    shows its DOWNLOAD COUNT — higher usually means better quality/sync.
    Tap a result to apply it.
  - Once you pick a subtitle it is REMEMBERED for that movie: resuming it later
    (or switching player) re-loads the same subtitle automatically, with no new
    search or download. Clear them anytime via Settings > Subtitles > Clear
    saved subtitles.
  - In the VLC player, the 💬 button on the top bar picks between the movie's
    built-in subtitle tracks, Off, or an online search. Movies with built-in
    English subtitles show them automatically — same as the Default player.
  - If a downloaded subtitle appears slightly early or late, use 💬 > Adjust
    timing to shift it in half-second steps.
  - To use OpenSubtitles, add your free API key in Settings > Subtitles.


17. PLAYER CONTROLS
-------------------
  - Aspect ratio: cycle Fit / Fill / 16:9 etc. from the top bar.
  - Volume & brightness: the side panels (tablet); on TV use the remote's
    volume keys.
  - Sleep timer: player menu > Sleep timer (see 18).


18. SETTINGS — EVERY SECTION
----------------------------
Open Settings from Home (gear icon, or MENU on a remote).
  - IPTV configuration: add/edit your provider(s).
  - Profiles: create separate content profiles (e.g. "Telugu", "Kids") and
    choose which channels/movie categories each one shows. Switch any time.
    Give each profile a PICTURE: pick a fun icon (animals, toys, etc.), or use
    "Camera"/"Gallery" to take or choose a photo ("Aa" keeps colour + initial).
  - Personalization: hide the "Recently Added" and/or "For You" rows on Home.
  - Remote control (key mapping): re-assign remote buttons to actions — see 19.
  - Parental PIN: set a PIN that locks restricted channels.
  - Playback settings: buffering size and hardware decoding. If you see
    stutter, try a higher buffer; if video fails, turn hardware decoding off.
  - Sleep timer: auto-stop after a chosen time; the menu shows time remaining,
    and it can close the app when it fires. While a timer is running, a ⏲ chip
    with the live m:ss countdown appears in the top bar on the browse screens —
    select it to see the countdown and change/extend or turn the timer off.
  - Fast-scroll: in a long list, HOLD the Up button to jump straight to the top
    (and the A-Z bar) instead of scrolling row by row. In the User Guide, Up/Down
    page-scroll.
  - Storage: clear Cache, Favourites, Watch Later, Continue Watching,
    Downloads, or Recordings — selectively or all at once.
  - Subtitles: your OpenSubtitles API key.
  - TV Guide (EPG): optionally point to an external XMLTV guide URL (.xml or
    .xml.gz). Leave blank to use the provider's own guide.
  - App updates: check for and install the latest version.
  - Sync & Backup: back up your Favourites / Watch Later / Continue Watching
    to a file, restore from one, or delete the backup file.
  - Troubleshooting: a diagnostics screen (see 20).
  - About.
  - Restart app: fully closes and relaunches the app (a quick fix for
    glitches without reaching for the Fire TV settings).
  - Restore factory defaults: wipes EVERYTHING — portal settings, profiles,
    favourites, downloads and caches — so the app starts like a brand-new
    install. Asks for confirmation first; this cannot be undone.
  - Exit.


19. REMOTE CONTROL — MAP YOUR KEYS
----------------------------------
Make any remote work the way you like (like STB Emulator).
  1. Settings > Remote control (key mapping).
  2. Select an action (Channel up, Channel down, Play/Pause, Rewind,
     Fast-forward, Change aspect ratio, Open menu, Show info).
  3. Press the remote button you want for it. Done.
  4. "Reset all to default" restores standard behaviour.
Note: Back and Home can never be re-mapped, so you can always exit. Mappings
apply in the live player.


20. TROUBLESHOOTING
-------------------
  - Channels/movies won't load: check internet; Settings > Troubleshooting >
    "Test portal connection". Re-check your Portal URL / MAC / Serial.
  - "Couldn't open ... provider returned an unexpected response": the provider
    hiccupped — try again, or another channel.
  - A channel buffers or fails: it auto-retries; you can also pick Retry from
    the player menu, or raise the buffer in Playback settings.
  - A movie STUTTERS at the start or after seeking (most common with 4K/UHD
    titles or on a slower/public Wi-Fi): set Settings > Playback settings >
    Buffer to HIGH. This pre-loads much more before playing and usually removes
    the stutter. Also keep Hardware decoding ON, and prefer your home Wi-Fi over
    a public hotspot for 4K.
  - A movie's audio is too QUIET (some titles are mastered at a low level): open
    the player menu and pick "Audio boost" to cycle Off > +4 > +8 > +12 dB. It
    lifts the volume above the normal 100% and is remembered for next time.
  - A movie won't play smoothly in the default player: open the player menu and
    pick "Switch player (VLC)" to play it through the VLC engine instead (it
    handles some codecs/containers better). The VLC player has all the same menu
    options, and your position, subtitles, playback speed, Continue Watching and
    autoplay-next all carry over; pick "Switch player (Default)" to switch back.
    Note: for HDR / 4K UHD titles keep the Default player — it renders those
    colours correctly, whereas VLC can look a little washed-out on HDR content.
    Use VLC only when a title won't play properly in the Default player.
  - No guide: your provider's EPG may be down — set an external XMLTV source
    in Settings > TV Guide (EPG).
  - The app closed unexpectedly: Settings > Troubleshooting shows the last
    crash details (handy to send to support). "Clear" dismisses it.
  - Remote buttons not doing what you expect: remap them (section 19).


21. TIPS
--------
  - Use Profiles to keep different content sets tidy and switch fast.
  - Set reminders for shows you don't want to miss.
  - Back up your Favourites before reinstalling.
  - Keep the app updated via Settings > App updates.

— End of guide —
""".trimIndent()
}
