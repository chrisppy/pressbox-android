// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package org.red_oxide.pressbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import kotlinx.coroutines.withContext

/**
 * Plays podcast episodes in the background with ExoPlayer, behind a media session: the
 * notification, the lock screen, headphones and Bluetooth controls all reach it.
 *
 * It saves the position on the server itself (every [SAVE_EVERY_MS] while playing, and on pause),
 * and marks an episode read when it plays to the end, so that keeps working with the app's screens
 * gone. Each media item's id is the pressbox item id.
 *
 * Up Next: the first episode in the server's queue waits in the player's playlist after the one
 * playing, so playback runs on into it without stopping (a service can't always start playing again
 * from the background once it has stopped).
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

  /** The episode the player is on, to tell which one finished when it moves to the next. */
  private var current: Long? = null

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

          override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = current
            current = mediaItem.itemId
            // Only the one playing and the one after it stay in the playlist.
            if (player.currentMediaItemIndex > 0) {
              player.removeMediaItems(0, player.currentMediaItemIndex)
            }
            val ended = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            if (ended) {
              // Played to the end, and on to the next in Up Next: resume it where it was left.
              val start = mediaItem?.mediaMetadata?.extras?.getInt(START) ?: 0
              if (start > 0) player.seekTo(start * 1000L)
            }
            scope.launch {
              // The server takes a finished episode out of Up Next as it marks it read, so this
              // comes first.
              if (ended && previous != null) finished(this@PlaybackService, previous)
              queueNext(player)
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

  /** Puts the first episode in Up Next (other than the one playing) after the one playing. */
  private suspend fun queueNext(player: Player) {
    val playing = player.currentMediaItem.itemId ?: return
    val queue: List<Item>? =
        try {
          api(this).queue()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Offline: whatever follows now stays.
          return
        }
    // A server without a queue.
    if (queue == null) return
    val next = queue.firstOrNull { it.id != playing }
    val feeds = withContext(Dispatchers.IO) { Cache.load(this@PlaybackService)?.feeds.orEmpty() }
    // Still on the same episode after the request?
    if (player.currentMediaItem.itemId != playing) return
    player.followWith(next?.let { mediaItem(it, feeds.firstOrNull { f -> f.id == it.feedId }) })
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

/** Where a media item starts when the player moves on to it: seconds, in its metadata's extras. */
private const val START = "start"

/** Where to start an episode: where it was left, or the top if that was near the end. */
fun startAt(item: Item): Int {
  val duration = item.media?.duration
  return if (duration != null && item.position >= duration - 30) 0 else item.position
}

/** A media item that plays [item] (its video, with [video]), or `null` if it has nothing to play. */
fun mediaItem(
    item: Item,
    feed: Feed?,
    video: Boolean = false,
    start: Int = startAt(item),
): MediaItem? {
  val media = item.media ?: return null
  val enclosure = (if (video) media.video else media.audio) ?: return null
  val metadata =
      MediaMetadata.Builder()
          .setTitle(item.title)
          .setArtist(feed?.title ?: item.feedId)
          .setAlbumTitle(feed?.title ?: item.feedId)
          .apply { (media.image ?: feed?.image)?.let { setArtworkUri(Uri.parse(it)) } }
          .setExtras(bundleOf(START to start))
          .build()
  return MediaItem.Builder()
      .setMediaId(item.id.toString())
      .setUri(enclosure.url)
      .setMimeType(enclosure.mime)
      .setMediaMetadata(metadata)
      .build()
}

/** Makes [next] (or nothing) the only media item after the one playing. */
fun Player.followWith(next: MediaItem?) {
  val after = currentMediaItemIndex + 1
  if (after < mediaItemCount) removeMediaItems(after, mediaItemCount)
  next?.let { addMediaItem(it) }
}

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
