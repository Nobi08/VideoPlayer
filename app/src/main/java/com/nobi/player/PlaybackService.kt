package com.nobi.player
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Keeps the player alive (audio + notification controls) when the app is in the background. */
class PlaybackService : MediaSessionService() {
  override fun onCreate() {
    super.onCreate()
    val pl = player
    if (pl == null) { stopSelf(); return }
    val open = Intent(this, PlayerActivity::class.java)
      .putExtra("reattach", true)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    session = MediaSession.Builder(this, pl).setSessionActivity(pi).build()
  }
  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
  override fun onTaskRemoved(rootIntent: Intent?) { shutdown(this); stopSelf() }
  override fun onDestroy() { session?.release(); session = null; super.onDestroy() }

  companion object {
    var player: ExoPlayer? = null
    var names: List<String> = emptyList()
    var session: MediaSession? = null

    /** Stop the service but keep the player (used when background playback is switched off). */
    fun detach(ctx: Context) {
      session?.release(); session = null
      ctx.stopService(Intent(ctx, PlaybackService::class.java))
    }
    /** Stop the service and release the player. */
    fun shutdown(ctx: Context) {
      detach(ctx)
      try { player?.release() } catch (e: Exception) {}
      player = null; names = emptyList()
    }
  }
}
