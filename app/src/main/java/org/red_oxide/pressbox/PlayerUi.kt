// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package org.red_oxide.pressbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** `m:ss`, or `h:mm:ss` from an hour. */
fun formatTime(seconds: Double): String {
  val s = maxOf(0, seconds.toLong())
  val (h, m, sec) = Triple(s / 3600, s / 60 % 60, s % 60)
  return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Above an episode's article: play, watch, chapters and transcript. */
@Composable
fun EpisodeHeader(store: Store, item: Item) {
  val media = item.media ?: return
  var showChapters by remember { mutableStateOf(false) }
  var showTranscript by remember { mutableStateOf(false) }
  val current = store.isCurrent(item)
  val playingAudio = current && !store.playingVideo
  val playingVideo = current && store.playingVideo
  val facts = buildList {
    media.duration?.let { add(formatTime(it.toDouble())) }
    when {
      media.season != null && media.episode != null ->
          add("Season ${media.season}, episode ${media.episode}")
      media.episode != null -> add("Episode ${media.episode}")
    }
  }
  Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { store.play(item) }) {
        Icon(
            if (playingAudio && store.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            when {
              playingAudio -> if (store.isPlaying) "Pause" else "Resume"
              item.position > 0 -> "Resume from ${formatTime(item.position.toDouble())}"
              else -> "Play"
            }
        )
      }
      if (media.video != null) {
        FilledTonalButton(onClick = { store.play(item, video = true) }) {
          Icon(Icons.Outlined.OndemandVideo, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(if (playingVideo && store.isPlaying) "Pause" else "Video")
        }
      }
      if (media.chapters != null) {
        IconButton(onClick = { showChapters = true }) {
          Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Chapters")
        }
      }
      if (media.transcripts.isNotEmpty()) {
        IconButton(onClick = { showTranscript = true }) {
          Icon(Icons.Outlined.ClosedCaption, contentDescription = "Transcript")
        }
      }
      // Servers without a queue: no Up Next.
      if (store.queue != null) {
        val queued = store.isQueued(item)
        IconButton(onClick = { if (queued) store.dequeue(item) else store.enqueue(item) }) {
          Icon(
              if (queued) Icons.AutoMirrored.Filled.PlaylistAddCheck
              else Icons.AutoMirrored.Filled.PlaylistAdd,
              contentDescription = if (queued) "Remove from Up Next" else "Add to Up Next",
          )
        }
      }
    }
    if (facts.isNotEmpty()) {
      Text(
          facts.joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = Theme.palette.mutedText,
      )
    }
  }
  if (showChapters) {
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    LaunchedEffect(item.id) { chapters = store.chapters(item) }
    Sheet(onDismiss = { showChapters = false }) {
      ChapterList(store, item, if (current) store.chapters else chapters.orEmpty())
    }
  }
  if (showTranscript) {
    Sheet(onDismiss = { showTranscript = false }) { TranscriptList(store, item) }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  ) {
    content()
  }
}

/** An episode's chapters; while it plays, the current one is marked and a tap jumps to one. */
@Composable
fun ChapterList(store: Store, item: Item, chapters: List<Chapter>) {
  val playing = store.isCurrent(item)
  val now = if (playing) store.chapter else null
  val visible = chapters.filter { !it.hidden }
  if (visible.isEmpty()) {
    Text("No chapters", Modifier.padding(24.dp), color = Theme.palette.mutedText)
    return
  }
  LazyColumn {
    items(visible) { chapter ->
      Row(
          Modifier.fillMaxWidth()
              .clickable(enabled = playing) { store.seekTo(chapter.start) }
              .padding(horizontal = 20.dp, vertical = 12.dp)
      ) {
        Text(
            formatTime(chapter.start),
            color = Theme.palette.mutedText,
            modifier = Modifier.width(72.dp),
        )
        Text(
            chapter.title ?: "",
            fontWeight = if (chapter == now) FontWeight.SemiBold else FontWeight.Normal,
            color = if (chapter == now) Theme.palette.accent else Theme.palette.text,
        )
      }
    }
  }
}

/** An episode's transcript; while it plays, the line being heard is highlighted and followed. */
@Composable
fun TranscriptList(store: Store, item: Item) {
  var transcript by remember { mutableStateOf<Transcript?>(null) }
  var loaded by remember { mutableStateOf(false) }
  LaunchedEffect(item.id) {
    transcript = store.transcript(item)
    loaded = true
  }
  val segments = transcript?.segments.orEmpty()
  val playing = store.isCurrent(item)
  val current =
      if (playing) segments.indexOfLast { it.start <= store.position && store.position < it.end }
      else -1
  val list = rememberLazyListState()
  LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem(current) }
  when {
    !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
    transcript == null ->
        Text("No transcript", Modifier.padding(24.dp), color = Theme.palette.mutedText)
    else ->
        LazyColumn(state = list) {
          items(segments.size) { i ->
            val segment = segments[i]
            Column(
                Modifier.fillMaxWidth()
                    .clickable(enabled = playing) { store.seekTo(segment.start) }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
              Text(
                  listOfNotNull(formatTime(segment.start), segment.speaker).joinToString(" · "),
                  style = MaterialTheme.typography.labelMedium,
                  color = Theme.palette.mutedText,
              )
              Text(
                  segment.text,
                  color = if (i == current) Theme.palette.accent else Theme.palette.text,
              )
            }
          }
        }
  }
}

/** The bar along the bottom while an episode plays: tap it for the full player. */
@Composable
fun MiniPlayer(store: Store, modifier: Modifier = Modifier) {
  val item = store.nowPlaying ?: return
  Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Column {
      val duration = store.duration
      if (duration != null && duration > 0) {
        LinearProgressIndicator(
            progress = { (store.position / duration).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
      }
      Row(
          Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f).clickable { store.showPlayer = true }) {
          Text(
              item.title,
              style = MaterialTheme.typography.titleSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              store.chapter?.title ?: store.feedTitle(item.feedId),
              style = MaterialTheme.typography.bodySmall,
              color = Theme.palette.mutedText,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = { store.skip(forward = false) }) {
          Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds")
        }
        IconButton(onClick = { store.togglePause() }) {
          Icon(
              if (store.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
              contentDescription = if (store.isPlaying) "Pause" else "Play",
          )
        }
      }
    }
  }
  if (store.showPlayer) NowPlaying(store)
}

/** The full player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlaying(store: Store) {
  val item = store.nowPlaying ?: return
  var showChapters by remember { mutableStateOf(false) }
  var showTranscript by remember { mutableStateOf(false) }
  var scrubbing by remember { mutableStateOf<Float?>(null) }
  ModalBottomSheet(
      onDismissRequest = { store.showPlayer = false },
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  ) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (store.playingVideo) VideoSurface(store)
      Text(
          item.title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          store.chapter?.title ?: store.feedTitle(item.feedId),
          style = MaterialTheme.typography.bodyMedium,
          color = Theme.palette.mutedText,
      )
      val duration = store.duration ?: maxOf(store.position, 1.0)
      val shown = scrubbing?.toDouble() ?: store.position
      Slider(
          value = shown.toFloat(),
          onValueChange = { scrubbing = it },
          onValueChangeFinished = {
            scrubbing?.let { store.seekTo(it.toDouble()) }
            scrubbing = null
          },
          valueRange = 0f..duration.toFloat(),
      )
      Row(Modifier.fillMaxWidth()) {
        Text(formatTime(shown), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        store.duration?.let {
          Text("-" + formatTime(it - shown), style = MaterialTheme.typography.labelMedium)
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { store.jumpChapter(next = false) },
            enabled = store.chapters.isNotEmpty(),
        ) {
          Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter")
        }
        IconButton(onClick = { store.skip(forward = false) }) {
          Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds")
        }
        IconButton(onClick = { store.togglePause() }, modifier = Modifier.size(72.dp)) {
          Icon(
              if (store.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
              contentDescription = if (store.isPlaying) "Pause" else "Play",
              modifier = Modifier.size(48.dp),
          )
        }
        IconButton(onClick = { store.skip(forward = true) }) {
          Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds")
        }
        IconButton(
            onClick = { store.jumpChapter(next = true) },
            enabled = store.chapters.isNotEmpty(),
        ) {
          Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter")
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { store.nextSpeed() }) {
          val speed = store.speed
          Text(if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×")
        }
        if (store.chapters.isNotEmpty()) {
          IconButton(onClick = { showChapters = true }) {
            Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Chapters")
          }
        }
        if (item.media?.transcripts?.isNotEmpty() == true) {
          IconButton(onClick = { showTranscript = true }) {
            Icon(Icons.Outlined.ClosedCaption, contentDescription = "Transcript")
          }
        }
        IconButton(onClick = { store.stopPlaying() }) {
          Icon(Icons.Filled.Stop, contentDescription = "Stop")
        }
      }
    }
  }
  if (showChapters) {
    Sheet(onDismiss = { showChapters = false }) { ChapterList(store, item, store.chapters) }
  }
  if (showTranscript) {
    Sheet(onDismiss = { showTranscript = false }) { TranscriptList(store, item) }
  }
}

/** The episode's video, drawn by Media3's player view (with its full-screen button). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(store: Store) {
  val controller = store.controller ?: return
  AndroidView(
      modifier =
          Modifier.fillMaxWidth()
              .aspectRatio(16f / 9f)
              .background(androidx.compose.ui.graphics.Color.Black),
      factory = { context ->
        PlayerView(context).apply {
          player = controller
          useController = true
        }
      },
      update = { view -> view.player = controller },
      onRelease = { view -> view.player = null },
  )
}
