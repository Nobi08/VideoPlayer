package com.nobi.player
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {
  private lateinit var p: ExoPlayer
  private val h = Handler(Looper.getMainLooper())
  private var mode = 0
  private var busy = false
  private var hold = 0
  private var prevSpeed = 1f
  private var vol = 0f
  private val rew = object : Runnable {
    override fun run() { p.seekTo((p.currentPosition - 1000).coerceAtLeast(0)); h.postDelayed(this, 150) }
  }

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val v = PlayerView(this)
    val root = FrameLayout(this); root.addView(v); setContentView(root)
    p = ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()
    v.player = p
    p.setMediaItems(intent.getStringArrayListExtra("uris")!!.map { MediaItem.fromUri(it) }, intent.getIntExtra("i", 0), 0L)
    p.prepare(); p.play()
    WindowCompat.getInsetsController(window, v).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    val row = LinearLayout(this); row.gravity = Gravity.CENTER
    fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
    row.addView(btn("⏪ 30") { p.seekTo((p.currentPosition - 30000).coerceAtLeast(0)) })
    row.addView(btn("30 ⏩") { p.seekTo(p.currentPosition + 30000) })
    row.addView(btn("🔄") {
      requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    })
    root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    v.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { row.visibility = it })

    val am = getSystemService(AUDIO_SERVICE) as AudioManager
    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
    vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
    val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent): Boolean = true
      override fun onLongPress(e: MotionEvent) {
        val x = e.x / v.width
        if (x > 0.7f) { hold = 1; prevSpeed = p.playbackParameters.speed; p.setPlaybackParameters(PlaybackParameters(2f)); busy = true }
        else if (x < 0.3f) { hold = 2; h.post(rew); busy = true }
      }
      override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
        if (e1 == null || hold != 0) return true
        if (mode == 0) mode = if (abs(dx) > abs(dy)) 1 else 2
        busy = true
        if (mode == 1) {
          p.seekTo((p.currentPosition - dx * 60).toLong().coerceIn(0L, p.duration.coerceAtLeast(0L)))
        } else if (e1.x < v.width / 2) {
          val lp = window.attributes
          val c = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
          lp.screenBrightness = (c + dy / v.height).coerceIn(0.02f, 1f)
          window.attributes = lp
        } else {
          vol = (vol + dy / v.height * mx).coerceIn(0f, mx)
          am.setStreamVolume(AudioManager.STREAM_MUSIC, vol.roundToInt(), 0)
        }
        return true
      }
    })
    v.setOnTouchListener { _, e ->
      if (e.actionMasked == MotionEvent.ACTION_DOWN) { mode = 0; busy = false; hold = 0 }
      gd.onTouchEvent(e)
      if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
        if (hold == 1) p.setPlaybackParameters(PlaybackParameters(prevSpeed))
        if (hold == 2) h.removeCallbacks(rew)
        hold = 0
        val r = busy; busy = false; r
      } else busy
    }
  }
  override fun onStop() { super.onStop(); p.pause() }
  override fun onDestroy() { super.onDestroy(); h.removeCallbacks(rew); p.release() }
}
