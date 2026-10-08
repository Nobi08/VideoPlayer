package com.nobi.player
import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.core.content.ContextCompat
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {
  private lateinit var p: ExoPlayer
  private lateinit var ov: FrameLayout
  private lateinit var ctrl: FrameLayout
  private lateinit var lockBtn: ImageView
  private lateinit var rotBtn: ImageView
  private val h = Handler(Looper.getMainLooper())
  private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
  private var lastKey: String? = null
  private var mode = 0
  private var hold = 0
  private var prevSpeed = 1f
  private var vol = 0f
  private var locked = false
  private var dragging = false
  private var abA = -1L
  private var abB = -1L
  private lateinit var pv: PlayerView
  private var plListener: Player.Listener? = null
  private var pipRx: BroadcastReceiver? = null
  private fun bgOn() = prefs.getBoolean("bg", true)
  private fun autoNext() = prefs.getBoolean("autonext", true)
  private fun autoPip() = prefs.getBoolean("autopip", true)
  private fun seekSec() = prefs.getInt("seeksec", 10)
  private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
  private val hideRun = Runnable { showCtrl(false) }
  private val rew = object : Runnable { override fun run() { seekBy(-1000); h.postDelayed(this, 150) } }
  private val abRun = object : Runnable { override fun run() { if (abB > 0 && p.currentPosition >= abB) p.seekTo(abA); h.postDelayed(this, 100) } }
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

  private fun savePos() {
    val k = curKey() ?: return
    val d = p.duration; val pos = p.currentPosition
    if (d > 0 && pos > d - 5000) prefs.edit().remove(k).apply() else prefs.edit().putLong(k, pos).apply()
  }
  private fun startBg() {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    PlaybackService.player = p
    startService(Intent(this, PlaybackService::class.java))
  }
  private fun stopBg() { if (PlaybackService.player === p) PlaybackService.detach(this) }
  private fun pipActions(): List<RemoteAction> {
    if (Build.VERSION.SDK_INT < 26) return emptyList()
    fun act(code: Int, res: Int, t: String, a: String) = RemoteAction(
      Icon.createWithResource(this, res), t, t,
      PendingIntent.getBroadcast(this, code, Intent(a).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE))
    return listOf(
      act(1, R.drawable.ic_rew, "Back", "nobi.PIP_REW"),
      if (p.isPlaying) act(2, R.drawable.ic_pause, "Pause", "nobi.PIP_PLAY") else act(2, R.drawable.ic_play, "Play", "nobi.PIP_PLAY"),
      act(3, R.drawable.ic_fwd, "Forward", "nobi.PIP_FWD"))
  }
  private fun pipParams(): PictureInPictureParams? {
    if (Build.VERSION.SDK_INT < 26 || !::p.isInitialized) return null
    val b = PictureInPictureParams.Builder().setActions(pipActions())
    val vs = p.videoSize
    if (vs.width > 0 && vs.height > 0) {
      val r = (vs.width * vs.pixelWidthHeightRatio) / vs.height
      b.setAspectRatio(Rational((r.coerceIn(0.43f, 2.38f) * 1000).toInt(), 1000))
    }
    if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoPip() && p.isPlaying)
    return b.build()
  }
  private fun updatePip() { if (Build.VERSION.SDK_INT >= 26) pipParams()?.let { try { setPictureInPictureParams(it) } catch (e: Exception) {} } }
  private fun enterPip() { if (Build.VERSION.SDK_INT >= 26) pipParams()?.let { try { enterPictureInPictureMode(it) } catch (e: Exception) {} } }
  private fun curKey() = p.currentMediaItem?.localConfiguration?.uri?.toString()
  private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
  private fun sx(f: Float) = (if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()) + "x"
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
  private fun updateRot() {
    rotBtn.setImageResource(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) R.drawable.ic_rot_land else R.drawable.ic_rot_port)
  }
  override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); if (::rotBtn.isInitialized) updateRot() }
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

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val re = intent.getBooleanExtra("reattach", false)
    if (re && PlaybackService.player == null) { finish(); return }
    val uris: ArrayList<String> = if (re) arrayListOf() else intent.getStringArrayListExtra("uris") ?: arrayListOf(intent.dataString ?: "")
    val names: List<String> = if (re) PlaybackService.names else intent.getStringArrayListExtra("names") ?: arrayListOf(intent.data?.lastPathSegment?.substringAfterLast('/') ?: "")
    val v = PlayerView(this); v.useController = false; pv = v
    val root = FrameLayout(this); root.setBackgroundColor(0xFF000000.toInt()); root.addView(v)
    ov = FrameLayout(this); root.addView(ov)
    ctrl = FrameLayout(this); ov.addView(ctrl)
    setContentView(root)
    if (re) {
      p = PlaybackService.player!!
    } else {
      PlaybackService.shutdown(this)
      p = ExoPlayer.Builder(this)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
      val si = intent.getIntExtra("i", 0).coerceIn(0, uris.size - 1)
      val saved = prefs.getLong(uris[si], 0L)
      p.setMediaItems(uris.mapIndexed { i, u ->
        MediaItem.Builder().setUri(u).setMediaMetadata(MediaMetadata.Builder().setTitle(names.getOrNull(i) ?: "").build()).build()
      }, si, if (saved > 5000) saved else 0L)
      p.prepare(); p.play()
      if (saved > 5000) Toast.makeText(this, "Resumed from ${fmt(saved)}", Toast.LENGTH_SHORT).show()
    }
    p.pauseAtEndOfMediaItems = !autoNext()
    v.player = p
    applySub(v)
    if (bgOn()) { PlaybackService.names = names; startBg() }
    WindowCompat.getInsetsController(window, v).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    val W = 0xFFFFFFFF.toInt()
    val ORANGE = 0xFFFF9800.toInt()
    val GREEN = 0xFF4CAF50.toInt()
    fun circ(sz: Int, pad: Int, r: Int, f: () -> Unit) = ImageView(this).apply {
      val png = resources.getResourceEntryName(r).startsWith("b_")
      val pp = if (png) sz * 4 / 100 else sz * 30 / 100
      setImageResource(r); setPadding(dp(pp), dp(pp), dp(pp), dp(pp))
      if (!png) background = InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE60B0B0C.toInt()) }, dp(sz * 14 / 100))
      layoutParams = LinearLayout.LayoutParams(dp(sz), dp(sz))
      setOnClickListener { f() }
    }
    fun mark(v: ImageView, sz: Int, on: Boolean, col: Int) {
      v.background = if (on) InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), col) }, dp(sz * 8 / 100)) else null
    }

    val hud = TextView(this).apply {
      setTextColor(W); textSize = 18f; setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE
      background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xAA000000.toInt()) }
    }
    val hudHide = Runnable { hud.visibility = View.GONE }
    fun flash(t: String) { hud.text = t; hud.visibility = View.VISIBLE; h.removeCallbacks(hudHide); h.postDelayed(hudHide, 900) }
    val gBright = Gauge(this, R.drawable.ic_sun)
    val gVol = Gauge(this, R.drawable.ic_vol)
    val gHide = Runnable { gBright.visibility = View.GONE; gVol.visibility = View.GONE }
    fun showGauge(g: Gauge, level: Float) { g.set(level); h.removeCallbacks(gHide); h.postDelayed(gHide, 900) }

    val ttl = TextView(this).apply {
      setTextColor(W); textSize = 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
      layoutParams = LinearLayout.LayoutParams(0, -2, 1f); text = names.getOrNull(p.currentMediaItemIndex) ?: ""
      setPadding(dp(6), 0, dp(6), 0)
    }
    val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
    top.addView(circ(42, 8, R.drawable.ic_back) { finish() })
    top.addView(ttl)
    top.addView(circ(42, 8, R.drawable.b_sub) { TrackSelectionDialogBuilder(this, "Subtitles", p, C.TRACK_TYPE_TEXT).build().show() })
    top.addView(circ(42, 8, R.drawable.b_audio) { TrackSelectionDialogBuilder(this, "Audio track", p, C.TRACK_TYPE_AUDIO).build().show() })
    val repBtn = circ(42, 8, R.drawable.b_repeat) {}
    repBtn.setOnClickListener {
      val on = p.repeatMode != Player.REPEAT_MODE_ONE
      p.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
      mark(repBtn, 42, on, ORANGE)
      flash(if (on) "Repeat: on" else "Repeat: off")
    }
    top.addView(repBtn)
    val gear = circ(42, 8, R.drawable.b_gear) {}
    gear.setOnClickListener {
      val m = PopupMenu(this, gear)
      fun oo(b: Boolean) = if (b) "On" else "Off"
      m.menu.add(0, 1, 0, "Background playback: ${oo(bgOn())}")
      m.menu.add(0, 2, 0, "Auto play next: ${oo(autoNext())}")
      m.menu.add(0, 3, 0, "Auto PiP on home: ${oo(autoPip())}")
      m.menu.add(0, 4, 0, "Seek duration: ${seekSec()}s")
      m.menu.add(0, 5, 0, "Load subtitle file"); m.menu.add(0, 6, 0, "Subtitle size"); m.menu.add(0, 7, 0, "Subtitle color")
      m.setOnMenuItemClickListener { it2 ->
        when (it2.itemId) {
          1 -> { val on = !bgOn(); prefs.edit().putBoolean("bg", on).apply()
            if (on) { PlaybackService.names = names; startBg() } else stopBg(); flash("Background: ${oo(on)}") }
          2 -> { val on = !autoNext(); prefs.edit().putBoolean("autonext", on).apply(); p.pauseAtEndOfMediaItems = !on; flash("Auto next: ${oo(on)}") }
          3 -> { val on = !autoPip(); prefs.edit().putBoolean("autopip", on).apply(); updatePip(); flash("Auto PiP: ${oo(on)}") }
          4 -> {
            val o = intArrayOf(5, 10, 15, 30, 60)
            AlertDialog.Builder(this).setTitle("Seek duration").setSingleChoiceItems(o.map { "$it seconds" }.toTypedArray(), o.indexOf(seekSec())) { d, i ->
              prefs.edit().putInt("seeksec", o[i]).apply(); flash("Seek ${o[i]}s"); d.dismiss()
            }.show()
          }
          5 -> subPick.launch(arrayOf("*/*"))
          6 -> {
            val n = arrayOf("Small", "Medium", "Large", "Huge"); val z = intArrayOf(16, 20, 26, 34)
            AlertDialog.Builder(this).setItems(n) { _, i -> prefs.edit().putInt("subsz", z[i]).apply(); applySub(v) }.show()
          }
          else -> {
            val n = arrayOf("White", "Yellow", "Cyan", "Green"); val c = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN)
            AlertDialog.Builder(this).setItems(n) { _, i -> prefs.edit().putInt("subcol", c[i]).apply(); applySub(v) }.show()
          }
        }
        true
      }
      m.show()
    }
    top.addView(gear)
    ctrl.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

    rotBtn = circ(48, 10, R.drawable.ic_rot_port) {
      requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
    ctrl.addView(rotBtn, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
    updateRot()

    lockBtn = circ(48, 10, R.drawable.ic_unlock) {
      locked = !locked
      lockBtn.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_unlock)
      showCtrl(true)
    }
    ov.addView(lockBtn, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(16) })

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

    var muted = false
    val mute = circ(42, 8, R.drawable.b_vol) {}
    mute.setOnClickListener {
      muted = !muted; p.volume = if (muted) 0f else 1f
      mute.setImageResource(if (muted) R.drawable.b_mute else R.drawable.b_vol)
    }
    val play = ImageView(this).apply {
      setImageResource(R.drawable.b_pause); setPadding(dp(2), dp(2), dp(2), dp(2))
      layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
      setOnClickListener { if (p.isPlaying) p.pause() else p.play() }
    }
    val sp = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
    val speed = circ(42, 8, R.drawable.b_speed) {}
    speed.setOnClickListener {
      val cs = sp.indexOfFirst { it == p.playbackParameters.speed }
      AlertDialog.Builder(this).setTitle("Playback speed").setSingleChoiceItems(sp.map { sx(it) }.toTypedArray(), cs) { d, i ->
        p.setPlaybackSpeed(sp[i]); prefs.edit().putFloat("speed", sp[i]).apply(); flash(sx(sp[i])); d.dismiss()
      }.show()
    }
    prefs.getFloat("speed", 1f).let { if (it != 1f) { p.setPlaybackSpeed(it) } }
    val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    val modeNames = arrayOf("Fit", "Zoom", "Stretch")
    var mi = prefs.getInt("aspect", 0).coerceIn(0, 2); v.resizeMode = modes[mi]
    val aspect = circ(42, 8, R.drawable.b_full) {
      mi = (mi + 1) % 3; v.resizeMode = modes[mi]; prefs.edit().putInt("aspect", mi).apply(); flash(modeNames[mi])
    }
    val pip = circ(42, 8, R.drawable.b_pip) { enterPip() }
    fun gapView() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
    val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
    bottom.addView(mute); bottom.addView(gapView())
    bottom.addView(circ(36, 7, R.drawable.b_prev) { p.seekToPreviousMediaItem() })
    bottom.addView(play)
    bottom.addView(circ(36, 7, R.drawable.b_next) { p.seekToNextMediaItem() })
    bottom.addView(gapView())
    bottom.addView(speed); bottom.addView(aspect); bottom.addView(pip)
    ctrl.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

    val skipRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
    skipRow.addView(circ(42, 8, R.drawable.b_rew) { seekBy(-seekSec() * 1000L); flash("-${seekSec()}s") })
    skipRow.addView(View(this), LinearLayout.LayoutParams(dp(80), 1))
    skipRow.addView(circ(42, 8, R.drawable.b_fwd) { seekBy(seekSec() * 1000L); flash("+${seekSec()}s") })
    ctrl.addView(skipRow, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

    root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(80) })
    root.addView(gBright, FrameLayout.LayoutParams(dp(60), -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(76) })
    root.addView(gVol, FrameLayout.LayoutParams(dp(60), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(76) })

    plListener = object : Player.Listener {
      override fun onIsPlayingChanged(pl: Boolean) { play.setImageResource(if (pl) R.drawable.b_pause else R.drawable.b_play); updatePip() }
      override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) { updatePip() }
      override fun onMediaItemTransition(m: MediaItem?, r: Int) {
        ttl.text = names.getOrNull(p.currentMediaItemIndex) ?: ""
        if (r == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) lastKey?.let { prefs.edit().remove(it).apply() }
        lastKey = m?.localConfiguration?.uri?.toString()
        if (r == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || r == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
          lastKey?.let { val s0 = prefs.getLong(it, 0L); if (s0 > 5000) p.seekTo(s0) }
        }
        abA = -1L; abB = -1L; h.removeCallbacks(abRun)
      }
      override fun onPlaybackStateChanged(st: Int) { if (st == Player.STATE_ENDED) curKey()?.let { prefs.edit().remove(it).apply() } }
    }
    p.addListener(plListener!!)
    play.setImageResource(if (p.isPlaying) R.drawable.b_pause else R.drawable.b_play)
    val tick = object : Runnable {
      override fun run() {
        if (!dragging) {
          val d = p.duration.coerceAtLeast(0L)
          sb.max = d.toInt(); sb.progress = p.currentPosition.toInt()
          cur.text = fmt(p.currentPosition); dur.text = fmt(d)
        }
        if (p.isPlaying) savePos()
        h.postDelayed(this, 500)
      }
    }
    h.post(tick)

    val am = getSystemService(AUDIO_SERVICE) as AudioManager
    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
    vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
    val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent): Boolean = true
      override fun onSingleTapConfirmed(e: MotionEvent): Boolean { showCtrl(lockBtn.visibility != View.VISIBLE); return true }
      override fun onDoubleTap(e: MotionEvent): Boolean {
        val x = e.x / v.width
        if (x < 0.4f) { seekBy(-seekSec() * 1000L); flash("-${seekSec()}s") }
        else if (x > 0.6f) { seekBy(seekSec() * 1000L); flash("+${seekSec()}s") }
        else if (p.isPlaying) p.pause() else p.play()
        return true
      }
      override fun onLongPress(e: MotionEvent) {
        val x = e.x / v.width
        if (x > 0.7f) { hold = 1; prevSpeed = p.playbackParameters.speed; p.setPlaybackSpeed(2f); flash("2x") }
        else if (x < 0.3f) { hold = 2; h.post(rew); flash("Rewind") }
      }
      override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
        if (e1 == null || hold != 0) return true
        if (mode == 0) mode = if (abs(dx) > abs(dy)) 1 else 2
        if (mode == 1) {
          seekBy((-dx * 60).toLong()); flash(fmt(p.currentPosition))
        } else if (e1.x < v.width / 2) {
          val lp = window.attributes
          val c = if (lp.screenBrightness < 0) Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f else lp.screenBrightness
          lp.screenBrightness = (c + dy / v.height).coerceIn(0.02f, 1f)
          window.attributes = lp
          showGauge(gBright, lp.screenBrightness)
        } else {
          vol = (vol + dy / v.height * mx).coerceIn(0f, mx)
          am.setStreamVolume(AudioManager.STREAM_MUSIC, vol.roundToInt(), 0)
          showGauge(gVol, vol / mx)
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

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (::p.isInitialized && Build.VERSION.SDK_INT in 26..30 && autoPip() && p.isPlaying) enterPip()
  }
  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    ov.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    if (isInPictureInPictureMode) {
      val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
          when (i?.action) {
            "nobi.PIP_REW" -> seekBy(-seekSec() * 1000L)
            "nobi.PIP_FWD" -> seekBy(seekSec() * 1000L)
            "nobi.PIP_PLAY" -> if (p.isPlaying) p.pause() else p.play()
          }
          updatePip()
        }
      }
      pipRx = rx
      val f = IntentFilter().apply { addAction("nobi.PIP_REW"); addAction("nobi.PIP_PLAY"); addAction("nobi.PIP_FWD") }
      ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
      updatePip()
    } else {
      pipRx?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }; pipRx = null
    }
  }
  override fun onStop() {
    super.onStop()
    if (!::p.isInitialized) return
    if (p.playbackState != Player.STATE_ENDED) savePos()
    if (isInPictureInPictureMode) { p.pause(); finish(); return }   // PiP window was closed
    if (!bgOn()) p.pause()
  }
  override fun onDestroy() {
    super.onDestroy()
    h.removeCallbacksAndMessages(null)
    pipRx?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }; pipRx = null
    if (!::p.isInitialized) return
    plListener?.let { p.removeListener(it) }
    if (::pv.isInitialized) pv.player = null
    val keep = !isFinishing && bgOn() && p.playWhenReady && PlaybackService.player === p
    if (keep) return                       // service keeps playing in background
    if (PlaybackService.player === p) PlaybackService.shutdown(this) else try { p.release() } catch (e: Exception) {}
  }
}
