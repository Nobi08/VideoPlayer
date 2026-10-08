package com.nobi.player

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
        }

        val title = TextView(this).apply {
            text = "⚙️ Settings"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(title)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(20))
        }

        fun section(name: String) {
            list.addView(TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(dp(12), dp(20), dp(12), dp(8))
            })
        }

        fun option(name: String) {
            list.addView(TextView(this).apply {
                text = name
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        }

        section("Playback")
        option("Auto play next")
        option("Resume playback")
        option("Playback speed")
        option("Seek duration")
        option("Background playback")
        option("Picture-in-picture")

        section("Player")
        option("Auto hide controls")
        option("Fullscreen by default")
        option("Screen orientation")
        option("Gesture controls")
        option("Double-tap to seek")
        option("Keep screen on")

        section("Audio")
        option("Default audio track")
        option("Volume boost")
        option("Audio track")
        option("Equalizer")
        option("Remember volume")

        section("Subtitles")
        option("Enable subtitles")
        option("Subtitle size")
        option("Subtitle color")
        option("Subtitle background")
        option("Subtitle delay")
        option("Default subtitle language")

        section("Video")
        option("Aspect ratio")
        option("Video scaling")
        option("Hardware acceleration")
        option("Brightness")
        option("Screen rotation")

        section("Library")
        option("Video folders")
        option("Sort order")
        option("Recently played")
        option("Favorites")
        option("Playback history")

        section("Downloads")
        option("Download location")
        option("Default quality")
        option("Wi-Fi only")
        option("Download notifications")

        section("Appearance")
        option("Theme: Light / Dark / System")
        option("Accent color")
        option("Player controls style")

        section("Privacy")
        option("App lock")
        option("Hidden videos")
        option("Clear history")
        option("Clear search history")

        section("Advanced")
        option("Decoder")
        option("Network settings")
        option("HLS / M3U8")
        option("Chromecast")
        option("External player")

        section("About")
        option("Version")
        option("Privacy policy")
        option("Open-source licenses")
        option("Report a problem")

        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
    }
}
