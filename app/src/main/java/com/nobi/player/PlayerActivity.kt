package com.nobi.player
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.media3.common.AudioAttributes
import android.content.ContentValues
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.util.TypedValue
import android.widget.Toast
import androidx.media3.ui.CaptionStyleCompat
import java.nio.ByteBuffer
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
import androidx.media3.common.VideoSize
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
  private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
  private var lastKey: String? = null
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
  private fun curKey() = p.currentMediaItem?.localConfiguration?.uri?.toString()
  private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
  private fun applySub(v: PlayerView) {
    val sz = prefs.getInt("subsz", 20); val col = prefs.getInt("subcol", Color.WHITE)
    v.subtitleView?.apply {
      setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false)
      setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sz.toFloat())
      setStyle(CaptionStyleCompat(col, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
    }
  }
  private fun saveClip(src: Uri, s: Long, e: Long) {
    if (Build.VERSION.SDK_INT < 29) { toast("Clip needs Android 10+"); return }
    toast("Saving clip...")
    Thread {
      var out: Uri? = null
      try {
        val ex = MediaExtractor(); ex.setDataSource(this, src, null)
        val cv = ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, "clip_${System.currentTimeMillis()}.mp4")
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Clips")
        }
        out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
        val pfd = contentResolver.openFileDescriptor(out!!, "w")!!
        val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val map = HashMap<Int, Int>()
        for (i in 0 until ex.trackCount) {
          try { map[i] = mux.addTrack(ex.getTrackFormat(i)); ex.selectTrack(i) } catch (x: Exception) {}
        }
        if (map.isEmpty()) throw Exception("no supported tracks")
        mux.start()
        ex.seekTo(s * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var base = -1L
        while (true) {
          val n = ex.readSampleData(buf, 0)
          if (n < 0) break
          val t = ex.sampleTime
          if (t > e * 1000) break
          if (base < 0) base = t
          info.set(0, n, (t - base).coerceAtLeast(0L), ex.sampleFlags)
          mux.writeSampleData(map[ex.sampleTrackIndex]!!, buf, info)
          ex.advance()
        }
        mux.stop(); mux.release(); pfd.close(); ex.release()
        runOnUiThread { toast("Clip saved in Movies/Clips") }
      } catch (x: Exception) {
        out?.let { try { contentResolver.delete(it, null, null) } catch (y: Exception) {} }
        runOnUiThread { toast("Clip failed: ${x.message}") }
      }
    }.start()
  }
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
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val names = intent.getStringArrayListExtra("names") ?: arrayListOf(intent.data?.lastPathSegment?.substringAfterLast('/') ?: "")
    val v = PlayerView(this); v.useController = false
    val root = FrameLayout(this); root.setBackgroundColor(0xFF000000.toInt()); root.addView(v)
    ov = FrameLayout(this); root.addView(ov)
    ctrl = FrameLayout(this); ov.addView(ctrl)
    setContentView(root)
    p = ExoPlayer.Builder(this).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true).setHandleAudioBecomingNoisy(true).build()
    v.player = p
    applySub(v)
    val uris = intent.getStringArrayListExtra("uris") ?: arrayListOf(intent.dataString ?: "")
    val si = intent.getIntExtra("i", 0)
    val saved = prefs.getLong(uris[si], 0L)
    p.setMediaItems(uris.map { MediaItem.fromUri(it) }, si, if (saved > 5000) saved else 0L)
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
      m.menu.add("Load subtitle file"); m.menu.add("Subtitle size"); m.menu.add("Subtitle color")
      m.menu.add(if (p.repeatMode == Player.REPEAT_MODE_ONE) "Repeat: off" else "Repeat: one")
      m.setOnMenuItemClickListener { it2 ->
        when (it2.title.toString()) {
          "Load subtitle file" -> subPick.launch(arrayOf("*/*"))
          "Subtitle size" -> {
            val n = arrayOf("Small", "Medium", "Large", "Huge"); val z = intArrayOf(16, 20, 26, 34)
            AlertDialog.Builder(this).setItems(n) { _, i -> prefs.edit().putInt("subsz", z[i]).apply(); applySub(v) }.show()
          }
          "Subtitle color" -> {
            val n = arrayOf("White", "Yellow", "Cyan", "Green"); val c = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN)
            AlertDialog.Builder(this).setItems(n) { _, i -> prefs.edit().putInt("subcol", c[i]).apply(); applySub(v) }.show()
          }
          else -> p.repeatMode = if (p.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        }
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
    rotBtn = ImageView(this).apply {
      setPadding(dp(10), dp(10), dp(10), dp(10))
      setOnClickListener {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
          ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
    }
    ctrl.addView(rotBtn, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
    updateRot()

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
      layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
      setOnClickListener { if (p.isPlaying) p.pause() else p.play() }
    }
    val sp = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    val speed = TextView(this).apply { text = "Speed"; textSize = 15f; setTextColor(W); setPadding(dp(8), dp(8), dp(8), dp(8)) }
    speed.setOnClickListener {
      AlertDialog.Builder(this).setItems(sp.map { "${it}x" }.toTypedArray()) { _, i ->
        p.setPlaybackSpeed(sp[i]); prefs.edit().putFloat("speed", sp[i]).apply(); speed.text = if (sp[i] == 1f) "Speed" else "${sp[i]}x"
      }.show()
    }
    prefs.getFloat("speed", 1f).let { if (it != 1f) { p.setPlaybackSpeed(it); speed.text = "${it}x" } }
    val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    val modeNames = arrayOf("Fit", "Zoom", "Stretch")
    var mi = prefs.getInt("aspect", 0).coerceIn(0, 2); v.resizeMode = modes[mi]
    val hud = TextView(this).apply {
      setTextColor(W); textSize = 18f; setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE
      background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xAA000000.toInt()) }
    }
    val hudHide = Runnable { hud.visibility = View.GONE }
    fun flash(t: String) { hud.text = t; hud.visibility = View.VISIBLE; h.removeCallbacks(hudHide); h.postDelayed(hudHide, 700) }
    var clipStart = -1L
    val cut = ImageView(this).apply { setImageResource(R.drawable.ic_cut); setPadding(dp(10), dp(10), dp(10), dp(10)) }
    cut.setOnClickListener {
      val u = p.currentMediaItem?.localConfiguration?.uri
      if (u == null) return@setOnClickListener
      if (clipStart < 0) {
        clipStart = p.currentPosition; cut.setColorFilter(0xFFFF9800.toInt()); flash("Clip start " + fmt(clipStart) + " - tap scissors again for end")
      } else {
        val e = p.currentPosition; val s0 = clipStart; clipStart = -1L; cut.clearColorFilter()
        if (e - s0 < 500) flash("Clip too short") else saveClip(u, s0, e)
      }
    }
    ctrl.addView(cut, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16); bottomMargin = dp(112) })
    val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
    fun gapView() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
    fun small(x: ImageView) = x.also { it.layoutParams = LinearLayout.LayoutParams(dp(40), dp(48)); it.setPadding(dp(8), dp(12), dp(8), dp(12)) }
    bottom.addView(mute); bottom.addView(gapView())
    bottom.addView(small(ic(R.drawable.ic_prev) { p.seekToPreviousMediaItem() }))
    bottom.addView(play)
    bottom.addView(small(ic(R.drawable.ic_next) { p.seekToNextMediaItem() }))
    bottom.addView(gapView())
    bottom.addView(speed)
    bottom.addView(box("↔", true) { mi = (mi + 1) % 3; v.resizeMode = modes[mi]; prefs.edit().putInt("aspect", mi).apply(); flash(modeNames[mi]) })
    bottom.addView(ic(R.drawable.ic_pip) {
      if (Build.VERSION.SDK_INT >= 26) enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
    })
    ctrl.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(80) })

    p.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(pl: Boolean) { play.setImageResource(if (pl) R.drawable.ic_pause else R.drawable.ic_play) }
      override fun onMediaItemTransition(m: MediaItem?, r: Int) {
        ttl.text = names.getOrNull(p.currentMediaItemIndex) ?: ""
        val k = m?.localConfiguration?.uri?.toString()
        if (r == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) lastKey?.let { prefs.edit().remove(it).apply() }
        lastKey = k
        if (k != null && r != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && r != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
          val sp0 = prefs.getLong(k, 0L); if (sp0 > 5000) p.seekTo(sp0)
        }
      }
      override fun onPlaybackStateChanged(st: Int) { if (st == Player.STATE_ENDED) curKey()?.let { prefs.edit().remove(it).apply() } }
    })
    val tick = object : Runnable {
      override fun run() {
        if (!dragging) {
          val d = p.duration.coerceAtLeast(0L)
          sb.max = d.toInt(); sb.progress = p.currentPosition.toInt()
          cur.text = fmt(p.currentPosition); dur.text = fmt(d)
        }
        if (p.isPlaying) curKey()?.let { prefs.edit().putLong(it, p.currentPosition).apply() }; h.postDelayed(this, 500)
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
  override fun onStop() { super.onStop(); p.pause(); if (p.playbackState != Player.STATE_ENDED) curKey()?.let { prefs.edit().putLong(it, p.currentPosition).apply() } }
  override fun onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null); p.release() }
}
