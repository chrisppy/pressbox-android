// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package io.github.chrisppy.pressbox

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** App state shared by every screen. */
class Store(app: Application) : AndroidViewModel(app) {
  private val context: Context
    get() = getApplication()

  private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var feeds by mutableStateOf(listOf<Feed>())
    private set

  var items by mutableStateOf(listOf<Item>())
    private set

  /** The list on screen; `null` is the feed list itself. */
  var source by mutableStateOf<Source?>(null)
    private set

  /** The article on screen. */
  var selectedItem by mutableStateOf<Long?>(null)

  var unreadOnly by mutableStateOf(true)
    private set

  /** Active full-text search; searches include read items. */
  var search by mutableStateOf<String?>(null)
    private set

  /** Where the next page of [items] starts, if there is one. */
  private var nextPage: NextPage? = null
  private var loadingMore = false

  var isLoading by mutableStateOf(false)
    private set

  var errorMessage by mutableStateOf<String?>(null)

  /** The last load failed for lack of a connection; showing cached data. */
  var isOffline by mutableStateOf(false)
    private set

  /** Full articles opened or prefetched, for offline reading. */
  private var articles: Map<Long, Item> = emptyMap()

  // Pairing in progress: the code to show the user.
  var pairCode by mutableStateOf<String?>(null)
    private set

  var pairStatus by mutableStateOf<String?>(null)
    private set

  private var pairJob: Job? = null

  var isLocked by mutableStateOf(false)
    private set

  /** Who this device is signed in as. */
  var me by mutableStateOf<Me?>(null)
    private set

  var serverVersion by mutableStateOf<ServerVersion?>(null)
    private set

  var showSettings by mutableStateOf(false)

  var server by mutableStateOf(prefs.getString("server", "") ?: "")
    private set

  var token by mutableStateOf(TokenStore.token(app) ?: "")
    private set

  var lockEnabled by mutableStateOf(prefs.getBoolean("lockEnabled", false))
    private set

  /** Unread count as a silent notification, which launchers show as a badge. */
  var notifyUnread by mutableStateOf(prefs.getBoolean(Unread.PREF, false))
    private set

  /** Mark items read as they scroll off the top of the list. */
  var markReadOnScroll by mutableStateOf(prefs.getBoolean("markReadOnScroll", false))
    private set

  // Podcasts: the episode playing in [PlaybackService], as its media controller reports it.

  /** The episode playing or paused; `null` when stopped. */
  var nowPlaying by mutableStateOf<Item?>(null)
    private set

  var playingVideo by mutableStateOf(false)
    private set

  var isPlaying by mutableStateOf(false)
    private set

  /** Seconds into the episode. */
  var position by mutableStateOf(0.0)
    private set

  /** Seconds, once known. */
  var duration by mutableStateOf<Double?>(null)
    private set

  var speed by mutableStateOf(1f)
    private set

  /** The playing episode's chapters. */
  var chapters by mutableStateOf(listOf<Chapter>())
    private set

  /** The full player is showing. */
  var showPlayer by mutableStateOf(false)

  /** Talks to [PlaybackService]; also the player the video view draws. */
  var controller by mutableStateOf<MediaController?>(null)
    private set

  init {
    isLocked = lockEnabled
    Cache.load(app)?.let {
      feeds = it.feeds
      items = it.items
      articles = it.articles
    }
    if (!isConfigured) showSettings = true
    connectPlayer()
  }

  val isConfigured: Boolean
    get() = server.isNotBlank() && token.isNotEmpty()

  val totalUnread: Int
    get() = feeds.filter { !it.isArchived }.sumOf { it.unread }

  /** Tags of the feeds in the server's config, sorted. */
  val tags: List<String>
    get() = feeds.filter { !it.isArchived }.flatMap { it.tags }.toSortedSet().toList()

  /** Feeds in the server's config with this tag. */
  fun feedsTagged(tag: String): List<Feed> = feeds.filter { !it.isArchived && tag in it.tags }

  fun unread(tagged: String): Int = feedsTagged(tagged).sumOf { it.unread }

  private val api: ApiClient
    get() = ApiClient(server, token)

  fun title(source: Source): String =
      when (source) {
        Source.All -> "All items"
        Source.Starred -> "Starred"
        is Source.OfFeed -> feedTitle(source.id)
        is Source.Tagged -> source.tag
      }

  fun feedTitle(id: String): String = feeds.firstOrNull { it.id == id }?.title ?: id

  fun item(id: Long): Item? = items.firstOrNull { it.id == id } ?: articles[id]

  /** Runs a request, showing any error instead of throwing it. */
  private suspend fun run(body: suspend () -> Unit) {
    try {
      body()
      errorMessage = null
      isOffline = false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (e.isOffline()) {
        isOffline = true
        errorMessage = null
      } else {
        errorMessage = e.message ?: e.toString()
      }
    }
  }

  private fun saveCache() {
    Cache.save(context, viewModelScope, Snapshot(feeds, items, articles))
  }

  private fun launch(body: suspend () -> Unit) {
    viewModelScope.launch { body() }
  }

  fun reloadAsync() = launch { reload() }

  suspend fun reload() {
    if (!isConfigured) {
      errorMessage = ApiException.NotConfigured().message
      return
    }
    isLoading = true
    try {
      run {
        feeds = api.feeds()
        loadItems()
        if (me == null) me = api.me()
        // Older servers don't have the endpoint; that's not an error.
        if (serverVersion == null) serverVersion = runCatching { api.version() }.getOrNull()
      }
    } finally {
      isLoading = false
    }
    updateUnread()
    saveCache()
    prefetchUnread()
  }

  private suspend fun loadItems() {
    val source = source ?: return
    val page = api.items(source, unreadOnly, search)
    items = page.items
    nextPage = page.next
  }

  fun select(source: Source?) {
    this.source = source
    selectedItem = null
    search = null
    items = emptyList()
    nextPage = null
    if (source != null) launch { run { loadItems() } }
  }

  fun setUnreadOnly(value: Boolean) = launch {
    unreadOnly = value
    run { loadItems() }
  }

  fun setSearch(text: String?) = launch {
    search = text?.trim()?.ifEmpty { null }
    run { loadItems() }
  }

  /** Loads the next page when [index] is among the last few rows. */
  fun loadMoreIfNeeded(index: Int) {
    val next = nextPage ?: return
    val source = source ?: return
    if (loadingMore || index < items.size - 5) return
    loadingMore = true
    launch {
      run {
        val page = api.items(source, unreadOnly, search, after = next)
        items = items + page.items
        nextPage = page.next
      }
      loadingMore = false
    }
  }

  /**
   * The full item with content, from the server or the offline cache, and marks it read, unless it
   * is a podcast episode: those are read once played to the end.
   */
  suspend fun open(item: Item): Item {
    var full = articles[item.id] ?: item
    run {
      full = api.item(item.id)
      articles = articles + (item.id to full)
    }
    if (full.isEpisode) {
      saveCache()
      return full
    }
    setRead(full, true)
    saveCache()
    return full.copy(read = true)
  }

  /** Downloads the content of unread items not cached yet, so they can be read offline. */
  private suspend fun prefetchUnread() {
    val missing = items.filter { !it.read && it.id !in articles }.take(30)
    if (missing.isEmpty()) return
    for (item in missing) {
      val full = runCatching { api.item(item.id) }.getOrNull() ?: break
      articles = articles + (item.id to full)
    }
    saveCache()
  }

  suspend fun setRead(item: Item, value: Boolean) {
    // The list's copy, which may be newer than the caller's.
    if ((this.item(item.id) ?: item).read == value) return
    run {
      api.setRead(item.id, value)
      update(item.id) { it.copy(read = value) }
      feeds = feeds.map {
        if (it.id == item.feedId) it.copy(unread = maxOf(0, it.unread + if (value) -1 else 1))
        else it
      }
    }
    updateUnread()
  }

  fun setReadAsync(item: Item, value: Boolean) = launch { setRead(item, value) }

  fun setStarred(item: Item, value: Boolean) = launch {
    run {
      api.setStarred(item.id, value)
      update(item.id) { it.copy(starred = value) }
    }
  }

  fun markAllRead() = launch {
    val targets =
        when (val source = source) {
          is Source.OfFeed -> listOf(source.id)
          is Source.Tagged -> feedsTagged(source.tag).filter { it.unread > 0 }.map { it.id }
          Source.All -> feeds.filter { it.unread > 0 }.map { it.id }
          else -> return@launch
        }
    run { for (id in targets) api.markFeedRead(id) }
    reload()
  }

  fun refreshServer() = launch {
    run { api.refresh() }
    delay(3000)
    reload()
  }

  private fun update(id: Long, change: (Item) -> Item) {
    items = items.map { if (it.id == id) change(it) else it }
    articles[id]?.let { articles = articles + (id to change(it)) }
  }

  // Pairing

  /** Asks the server to pair this device and waits for approval. */
  fun startPairing(newServer: String, name: String) {
    pairJob?.cancel()
    setServer(newServer)
    pairCode = null
    pairStatus = "Contacting the server…"
    val client = ApiClient(server, null)
    pairJob = viewModelScope.launch {
      try {
        val start = client.pairStart(name)
        pairCode = start.code
        pairStatus = "Waiting for approval…"
        val deadline = System.currentTimeMillis() + start.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
          delay(2000)
          val approved = client.pairPoll(start.pollId)
          if (approved != null) {
            setToken(approved.token)
            me = null
            pairCode = null
            pairStatus = "Paired as ${approved.name}."
            reload()
            if (errorMessage == null) showSettings = false
            return@launch
          }
        }
        pairCode = null
        pairStatus = "The code expired. Try again."
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        pairCode = null
        pairStatus = e.message ?: e.toString()
      }
    }
  }

  fun cancelPairing() {
    pairJob?.cancel()
    pairCode = null
    pairStatus = null
  }

  /** Signs in with a token from `pressboxctl add-device` instead of pairing. */
  fun useToken(newServer: String, newToken: String) = launch {
    setServer(newServer)
    setToken(newToken)
    me = null
    reload()
    if (errorMessage == null) showSettings = false
  }

  /**
   * Approves another device's pairing code, for [user] if given (admins only). Returns "device for
   * user" on success.
   */
  suspend fun approve(code: String, user: String?): String? {
    var result: String? = null
    run {
      val approved = api.approve(code, user)
      result = "${approved.name} for ${approved.user}"
    }
    return result
  }

  fun forget() {
    controller?.stop()
    controller?.clearMediaItems()
    setToken("")
    me = null
    serverVersion = null
    feeds = emptyList()
    items = emptyList()
    articles = emptyMap()
    source = null
    selectedItem = null
    Cache.clear(context)
    Unread.cancel(context)
  }

  private fun setServer(value: String) {
    server = value.trim()
    prefs.edit { putString("server", server) }
    serverVersion = null
  }

  private fun setToken(value: String) {
    token = value.trim()
    TokenStore.setToken(context, token)
  }

  // Settings

  fun updateLockEnabled(value: Boolean) {
    lockEnabled = value
    prefs.edit { putBoolean("lockEnabled", value) }
  }

  fun updateMarkReadOnScroll(value: Boolean) {
    markReadOnScroll = value
    prefs.edit { putBoolean("markReadOnScroll", value) }
  }

  fun updateNotifyUnread(value: Boolean) {
    notifyUnread = value
    prefs.edit { putBoolean(Unread.PREF, value) }
    Unread.schedule(context)
    updateUnread()
  }

  private fun updateUnread() {
    Unread.show(context, if (notifyUnread) totalUnread else 0)
  }

  // Lock

  fun lockIfEnabled() {
    if (lockEnabled) isLocked = true
  }

  fun unlocked() {
    isLocked = false
  }

  // Podcasts

  /** Connects to [PlaybackService], which may already be playing, and follows it. */
  private fun connectPlayer() {
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val future = MediaController.Builder(context, token).buildAsync()
    future.addListener(
        {
          val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
          controller = connected
          connected.addListener(
              object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = follow()

                override fun onPlaybackStateChanged(state: Int) {
                  if (state == Player.STATE_ENDED) ended()
                }
              }
          )
          follow()
          // The position moves without events; follow it about once a second.
          viewModelScope.launch {
            while (true) {
              delay(1000)
              if (nowPlaying != null) follow()
            }
          }
        },
        ContextCompat.getMainExecutor(context),
    )
  }

  /** The local copy of the position, kept close enough for the list's marks. */
  private var localPosition = 0

  /** Takes in what the controller says. */
  private fun follow() {
    val player = controller ?: return
    val id = player.currentMediaItem.itemId
    if (id == null || player.playbackState == Player.STATE_IDLE) {
      nowPlaying = null
      isPlaying = false
      return
    }
    if (nowPlaying?.id != id) {
      // Playing something this screen didn't start (the app was away meanwhile).
      nowPlaying =
          item(id)
              ?: Item(
                  id = id,
                  feedId = "",
                  title = player.mediaMetadata.title?.toString() ?: "",
                  published = 0,
                  read = false,
                  starred = false,
              )
      chapters = emptyList()
      launch { loadChapters(id) }
    }
    isPlaying = player.isPlaying
    position = player.currentPosition / 1000.0
    duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 }
    speed = player.playbackParameters.speed
    val seconds = position.toInt()
    if (kotlin.math.abs(seconds - localPosition) >= 15 || !isPlaying) {
      localPosition = seconds
      update(id) { it.copy(position = seconds) }
    }
  }

  /** Played to the end: the service has told the server; catch up here. */
  private fun ended() {
    val item = nowPlaying ?: return
    controller?.clearMediaItems()
    nowPlaying = null
    isPlaying = false
    chapters = emptyList()
    finishedHere(item)
  }

  /** An episode finished (and the server told): read, position forgotten, counts updated. */
  private fun finishedHere(item: Item) {
    val wasUnread = !(this.item(item.id) ?: item).read
    update(item.id) { it.copy(position = 0, read = true) }
    if (wasUnread) {
      feeds = feeds.map {
        if (it.id == item.feedId) it.copy(unread = maxOf(0, it.unread - 1)) else it
      }
      updateUnread()
    }
  }

  private suspend fun loadChapters(id: Long) {
    val item = item(id) ?: return
    if (item.media?.chapters == null) return
    val loaded = runCatching { api.chapters(id) }.getOrDefault(emptyList())
    if (nowPlaying?.id == id) chapters = loaded
  }

  /** Whether [item] is the episode playing (or paused). */
  fun isCurrent(item: Item): Boolean = nowPlaying?.id == item.id

  /**
   * Starts an episode (its video, with [video]), resuming where it was left; or, if it is the one
   * playing that way, pauses or resumes it.
   */
  fun play(item: Item, video: Boolean = false) {
    val player = controller ?: return
    if (isCurrent(item) && playingVideo == video) {
      togglePause()
      return
    }
    val media = item.media ?: return
    val enclosure = (if (video) media.video else media.audio) ?: return
    val switching = isCurrent(item)
    // Save the one playing before it is replaced; the service only saves what it plays.
    nowPlaying?.let { old ->
      val at = (player.currentPosition / 1000).toInt()
      update(old.id) { it.copy(position = at) }
      launch { run { api.setPosition(old.id, at) } }
    }
    var start = if (switching) position.toInt() else (this.item(item.id) ?: item).position
    // Played to the end before: from the top.
    if (media.duration != null && start >= media.duration - 30) start = 0
    val feed = feeds.firstOrNull { it.id == item.feedId }
    val metadata =
        MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(feed?.title ?: item.feedId)
            .setAlbumTitle(feed?.title ?: item.feedId)
            .apply { (media.image ?: feed?.image)?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
    val mediaItem =
        MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setUri(enclosure.url)
            .setMimeType(enclosure.mime)
            .setMediaMetadata(metadata)
            .build()
    nowPlaying = item
    playingVideo = video
    position = start.toDouble()
    duration = media.duration?.toDouble()
    localPosition = start
    chapters = emptyList()
    player.setMediaItem(mediaItem, start * 1000L)
    player.prepare()
    player.play()
    launch { loadChapters(item.id) }
  }

  fun togglePause() {
    val player = controller ?: return
    if (player.isPlaying) player.pause() else player.play()
  }

  fun seekTo(seconds: Double) {
    val player = controller ?: return
    val to = duration?.let { seconds.coerceIn(0.0, it) } ?: maxOf(0.0, seconds)
    player.seekTo((to * 1000).toLong())
    position = to
  }

  fun skip(forward: Boolean) {
    val player = controller ?: return
    if (forward) player.seekForward() else player.seekBack()
  }

  /**
   * The next chapter, or (`next` false) the start of this one, or the one before near its start.
   */
  fun jumpChapter(next: Boolean) {
    val starts = chapters.filter { !it.hidden }.map { it.start }
    if (next) {
      starts.firstOrNull { it > position + 0.5 }?.let { seekTo(it) }
    } else {
      seekTo(starts.lastOrNull { it < position - 3 } ?: 0.0)
    }
  }

  /** The chapter at the current position. */
  val chapter: Chapter?
    get() = chapters.filter { !it.hidden }.lastOrNull { it.start <= position }

  /** Steps through 1×, 1.25×, 1.5×, 1.75×, 2× and 0.75×. */
  fun nextSpeed() {
    val player = controller ?: return
    val speeds = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
    val i = speeds.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
    player.setPlaybackSpeed(speeds[(i + 1) % speeds.size])
  }

  /** Stops playing; the service saves the position as it pauses. */
  fun stopPlaying() {
    val player = controller ?: return
    val item = nowPlaying ?: return
    val at = (player.currentPosition / 1000).toInt()
    val nearEnd = duration?.let { position >= it - 30 } ?: false
    player.pause()
    player.clearMediaItems()
    nowPlaying = null
    isPlaying = false
    chapters = emptyList()
    showPlayer = false
    launch {
      if (nearEnd) {
        finished(context, item.id)
        finishedHere(item)
      } else {
        update(item.id) { it.copy(position = at) }
        run { api.setPosition(item.id, at) }
      }
    }
  }

  suspend fun chapters(item: Item): List<Chapter> =
      runCatching { api.chapters(item.id) }.getOrDefault(emptyList())

  suspend fun transcript(item: Item): Transcript? {
    var transcript: Transcript? = null
    run { transcript = api.transcript(item.id) }
    return transcript
  }

  override fun onCleared() {
    controller?.release()
    super.onCleared()
  }
}
