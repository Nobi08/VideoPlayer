package com.nobi.player
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {
  private lateinit var p: ExoPlayer
  private lateinit var ov: FrameLayout
  private lateinit var ctrl: FrameLayout
  private lateinit var lockBtn: ImageView
  private val h = Handler(Looper.getMainLooper())
  private var mode = 0
  private var hold = 0
  private var prevSpeed = 1f
  private var vol = 0f
  private var locked = false
  private var dragging = false
  private val hideRun = Runnable { showCtrl(false) }
  private val rew = object : Runnable { override fun run() { seekBy(-1000); h.postDelayed(this, 150) } }
  private val subPick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
    val c = p.currentMediaItem
    if (u != null && c != null) {
      val i = p.currentMediaItemIndex; val pos = p.currentPosition
      val sub = MediaItem.SubtitleConfiguration.Builder(u)
        .setMimeType(if (u.toString().endsWith("vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
      p.replaceMediaItem(i, c.buildUpon().setSubtitleConfigurations(listOf(sub)).build())
      p.seekTo(i, pos)
    }
  }

  private lateinit var rotBtn: ImageView
  private fun updateRot() { rotBtn.setImageResource(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) R.drawable.ic_rot_land else R.drawable.ic_rot_port) }
  override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); if (::rotBtn.isInitialized) updateRot() }
  private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
  private fun fmt(ms: Long): String {
    val s = ms / 1000; val hh = s / 3600; val mm = (s % 3600) / 60; val ss = s % 60
    return if (hh > 0) "%d:%02d:%02d".format(hh, mm, ss) else "%02d:%02d".format(mm, ss)
  }
  private fun seekBy(ms: Long) {
    val t = p.currentPosition + ms
    p.seekTo(if (p.duration > 0) t.coerceIn(0L, p.duration) else t.coerceAtLeast(0L))
  }
  private fun showCtrl(s: Boolean) {
    ctrl.visibility = if (s && !locked) View.VISIBLE else View.GONE
    lockBtn.visibility = if (s) View.VISIBLE else View.GONE
    h.removeCallbacks(hideRun)
    if (s) h.postDelayed(hideRun, 4000)
  }

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val names = intent.getStringArrayListExtra("names") ?: arrayListOf()
    val v = PlayerView(this); v.useController = false
    val root = FrameLayout(this); root.setBackgroundColor(0xFF000000.toInt()); root.addView(v)
    ov = FrameLayout(this); root.addView(ov)
    ctrl = FrameLayout(this); ov.addView(ctrl)
    setContentView(root)
    p = ExoPlayer.Builder(this).build()
    v.player = p
    p.setMediaItems(intent.getStringArrayListExtra("uris")!!.map { MediaItem.fromUri(it) }, intent.getIntExtra("i", 0), 0L)
    p.prepare(); p.play()
    WindowCompat.getInsetsController(window, v).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    val W = 0xFFFFFFFF.toInt()
    fun ic(r: Int, f: () -> Unit) = ImageView(this).apply {
      setImageResource(r); setPadding(dp(12), dp(12), dp(12), dp(12))
      layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { f() }
    }
    fun box(t: String, fill: Boolean, f: () -> Unit) = TextView(this).apply {
      text = t; textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
      setTextColor(if (fill) 0xFF000000.toInt() else W)
      setPadding(dp(8), dp(4), dp(8), dp(4))
      background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); if (fill) setColor(W) else setStroke(dp(2), W) }
      layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) }
      setOnClickListener { f() }
    }
    fun pill(t: String, f: () -> Unit) = TextView(this).apply {
      text = t; textSize = 13f; setTextColor(W); gravity = Gravity.CENTER
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x88333333.toInt()) }
      layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, dp(6), 0, dp(6)) }
      setOnClickListener { f() }
    }

    // top bar
    val ttl = TextView(this).apply {
      setTextColor(W); textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
      layoutParams = LinearLayout.LayoutParams(0, -2, 1f); text = names.getOrNull(p.currentMediaItemIndex) ?: ""
    }
    val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(0x55000000) }
    top.addView(ic(R.drawable.ic_back) { finish() }); top.addView(ttl)
    top.addView(box("CC", false) { TrackSelectionDialogBuilder(this, "Subtitles", p, C.TRACK_TYPE_TEXT).build().show() })
    top.addView(ic(R.drawable.ic_audio) { TrackSelectionDialogBuilder(this, "Audio track", p, C.TRACK_TYPE_AUDIO).build().show() })
    val more = ic(R.drawable.ic_more) {}
    more.setOnClickListener {
      val m = PopupMenu(this, more)
      m.menu.add("Load subtitle file")
      m.menu.add(if (p.repeatMode == Player.REPEAT_MODE_ONE) "Repeat: off" else "Repeat: one")
      m.setOnMenuItemClickListener { mi ->
        if (mi.title.toString().startsWith("Load")) subPick.launch(arrayOf("*/*"))
        else p.repeatMode = if (p.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        true
      }
      m.show()
    }
    top.addView(more)
    ctrl.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

    // side columns
    var muted = false
    val mute = ic(R.drawable.ic_vol) {}
    mute.setOnClickListener { muted = !muted; p.volume = if (muted) 0f else 1f; mute.alpha = if (muted) 0.4f else 1f }
    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    left.addView(pill("-30") { seekBy(-30000) }); left.addView(mute)
    ctrl.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(16); bottomMargin = dp(140) })
    rotBtn = ImageView(this).apply {
      setPadding(dp(10), dp(10), dp(10), dp(10)); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
      setOnClickListener {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
          ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
    }
    val rot = rotBtn
    updateRot()
    val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    right.addView(pill("+30") { seekBy(30000) }); right.addView(rot)
    ctrl.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16); bottomMargin = dp(140) })

    // lock
    lockBtn = ic(R.drawable.ic_unlock) {}
    lockBtn.setOnClickListener {
      locked = !locked
      lockBtn.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_unlock)
      showCtrl(true)
    }
    ov.addView(lockBtn, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(16) })

    // seek row
    val cur = TextView(this).apply { setTextColor(W); textSize = 13f; text = "00:00" }
    val dur = TextView(this).apply { setTextColor(W); textSize = 13f; text = "00:00" }
    val sb = SeekBar(this).apply {
      layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
      progressTintList = ColorStateList.valueOf(W); thumbTintList = ColorStateList.valueOf(W)
      progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
    }
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(s: SeekBar, pr: Int, user: Boolean) { if (user) { p.seekTo(pr.toLong()); cur.text = fmt(pr.toLong()) } }
      override fun onStartTrackingTouch(s: SeekBar) { dragging = true; h.removeCallbacks(hideRun) }
      override fun onStopTrackingTouch(s: SeekBar) { dragging = false; showCtrl(true) }
    })
    val seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
    seekRow.addView(cur); seekRow.addView(sb); seekRow.addView(dur)
    ctrl.addView(seekRow, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(68) })

    // bottom row
    val play = ImageView(this).apply {
      setImageResource(R.drawable.ic_pause); setPadding(dp(14), dp(14), dp(14), dp(14))
      background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), W) }
      layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
      setOnClickListener { if (p.isPlaying) p.pause() else p.play() }
    }
    val sp = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    val speed = TextView(this).apply { text = "Speed"; textSize = 15f; setTextColor(W); setPadding(dp(8), dp(8), dp(8), dp(8)) }
    speed.setOnClickListener {
      AlertDialog.Builder(this).setItems(sp.map { "${it}x" }.toTypedArray()) { _, i ->
        p.setPlaybackSpeed(sp[i]); speed.text = if (sp[i] == 1f) "Speed" else "${sp[i]}x"
      }.show()
    }
    val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    val modeNames = arrayOf("Fit", "Zoom", "Stretch")
    var mi = 0
    val hud = TextView(this).apply {
      setTextColor(W); textSize = 18f; setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE
      background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xAA000000.toInt()) }
    }
    val hudHide = Runnable { hud.visibility = View.GONE }
    fun flash(t: String) { hud.text = t; hud.visibility = View.VISIBLE; h.removeCallbacks(hudHide); h.postDelayed(hudHide, 700) }
    val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
    bottom.addView(play)
    bottom.addView(ic(R.drawable.ic_prev) { p.seekToPreviousMediaItem() })
    bottom.addView(ic(R.drawable.ic_next) { p.seekToNextMediaItem() })
    bottom.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
    bottom.addView(speed)
    bottom.addView(box("↔", true) { mi = (mi + 1) % 3; v.resizeMode = modes[mi]; flash(modeNames[mi]) })
    bottom.addView(ic(R.drawable.ic_pip) {
      if (Build.VERSION.SDK_INT >= 26) enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
    })
    ctrl.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(80) })

    p.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(pl: Boolean) { play.setImageResource(if (pl) R.drawable.ic_pause else R.drawable.ic_play) }
      override fun onMediaItemTransition(m: MediaItem?, r: Int) { ttl.text = names.getOrNull(p.currentMediaItemIndex) ?: "" }
    })
    val tick = object : Runnable {
      override fun run() {
        if (!dragging) {
          val d = p.duration.coerceAtLeast(0L)
          sb.max = d.toInt(); sb.progress = p.currentPosition.toInt()
          cur.text = fmt(p.currentPosition); dur.text = fmt(d)
        }
        h.postDelayed(this, 500)
      }
    }
    h.post(tick)

    // gestures
    val am = getSystemService(AUDIO_SERVICE) as AudioManager
    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
    vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
    val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent): Boolean = true
      override fun onSingleTapConfirmed(e: MotionEvent): Boolean { showCtrl(lockBtn.visibility != View.VISIBLE); return true }
      override fun onDoubleTap(e: MotionEvent): Boolean {
        val x = e.x / v.width
        if (x < 0.4f) { seekBy(-10000); flash("⏪ 10s") }
        else if (x > 0.6f) { seekBy(10000); flash("10s ⏩") }
        else if (p.isPlaying) p.pause() else p.play()
        return true
      }
      override fun onLongPress(e: MotionEvent) {
        val x = e.x / v.width
        if (x > 0.7f) { hold = 1; prevSpeed = p.playbackParameters.speed; p.setPlaybackSpeed(2f); flash("2x ⏩") }
        else if (x < 0.3f) { hold = 2; h.post(rew); flash("⏪") }
      }
      override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
        if (e1 == null || hold != 0) return true
        if (mode == 0) mode = if (abs(dx) > abs(dy)) 1 else 2
        if (mode == 1) {
          seekBy((-dx * 60).toLong()); flash(fmt(p.currentPosition))
        } else if (e1.x < v.width / 2) {
          val lp = window.attributes
          val c = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
          lp.screenBrightness = (c + dy / v.height).coerceIn(0.02f, 1f)
          window.attributes = lp
          flash("☀ ${(lp.screenBrightness * 100).roundToInt()}%")
        } else {
          vol = (vol + dy / v.height * mx).coerceIn(0f, mx)
          am.setStreamVolume(AudioManager.STREAM_MUSIC, vol.roundToInt(), 0)
          flash("🔊 ${(vol / mx * 100).roundToInt()}%")
        }
        return true
      }
    })
    v.setOnTouchListener { _, e ->
      if (locked) {
        if (e.actionMasked == MotionEvent.ACTION_UP) showCtrl(lockBtn.visibility != View.VISIBLE)
      } else {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { mode = 0; hold = 0 }
        gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
          if (hold == 1) p.setPlaybackSpeed(prevSpeed)
          if (hold == 2) h.removeCallbacks(rew)
          hold = 0
        }
      }
      true
    }
    showCtrl(true)
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    ov.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
  }
  override fun onStop() { super.onStop(); p.pause() }
  override fun onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null); p.release() }
}
