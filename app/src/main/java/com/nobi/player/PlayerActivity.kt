package com.nobi.player
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
  private lateinit var p: ExoPlayer
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val v = PlayerView(this); setContentView(v)
    p = ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()
    v.player = p
    p.setMediaItems(intent.getStringArrayListExtra("uris")!!.map { MediaItem.fromUri(it) }, intent.getIntExtra("i", 0), 0L)
    p.prepare(); p.play()
    WindowCompat.getInsetsController(window, v).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }
  override fun onStop() { super.onStop(); p.pause() }
  override fun onDestroy() { super.onDestroy(); p.release() }
}
