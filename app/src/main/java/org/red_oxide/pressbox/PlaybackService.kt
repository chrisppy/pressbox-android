// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package org.red_oxide.pressbox

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays podcast episodes in the background with ExoPlayer, behind a media session: the
 * notification, the lock screen, headphones and Bluetooth controls all reach it.
 *
 * It saves the position on the server itself (every [SAVE_EVERY_MS] while playing, and on pause),
 * and marks an episode read when it plays to the end, so that keeps working with the app's screens
 * gone. Each media item's id is the pressbox item id.
 */
class PlaybackService : MediaSessionService() {
  companion object {
    const val SAVE_EVERY_MS = 15_000L
    const val SKIP_BACK_MS = 10_000L
    const val SKIP_FORWARD_MS = 30_000L
  }

  private var session: MediaSession? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var saving: Job? = null

  @OptIn(UnstableApi::class)
  override fun onCreate() {
    super.onCreate()
    val player =
        ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SKIP_BACK_MS)
            .setSeekForwardIncrementMs(SKIP_FORWARD_MS)
            .build()
    player.addListener(
        object : Player.Listener {
          override fun onIsPlayingChanged(isPlaying: Boolean) {
            saving?.cancel()
            if (isPlaying) {
              saving = scope.launch {
                while (isActive) {
                  delay(SAVE_EVERY_MS)
                  save(player)
                }
              }
            } else if (player.playbackState != Player.STATE_ENDED) {
              scope.launch { save(player) }
            }
          }

          override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
              val id = player.currentMediaItem.itemId ?: return
              scope.launch { finished(this@PlaybackService, id) }
            }
          }
        }
    )
    session = MediaSession.Builder(this, player).build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

  /** Swiped away from recent apps: keep playing only if it is playing. */
  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = session?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    session?.run {
      player.release()
      release()
    }
    session = null
    scope.cancel()
    super.onDestroy()
  }

  private suspend fun save(player: Player) {
    val id = player.currentMediaItem.itemId ?: return
    val seconds = (player.currentPosition / 1000).toInt()
    quietly { api(this).setPosition(id, seconds) }
  }
}

/** The pressbox item a media item plays. */
val MediaItem?.itemId: Long?
  get() = this?.mediaId?.toLongOrNull()

/** The API as the app is signed in, for the service. */
fun api(context: Context): ApiClient {
  val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
  return ApiClient(prefs.getString("server", "") ?: "", TokenStore.token(context))
}

/** An episode played to the end: read, and its position forgotten. */
suspend fun finished(context: Context, id: Long) {
  quietly {
    val api = api(context)
    api.setPosition(id, 0)
    api.setRead(id, true)
  }
}

/** Runs a background request whose failure has no one to tell. */
private suspend fun quietly(body: suspend () -> Unit) {
  try {
    body()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {}
}
