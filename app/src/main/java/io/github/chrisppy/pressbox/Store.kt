// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: MPL-2.0

package io.github.chrisppy.pressbox

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

  init {
    isLocked = lockEnabled
    Cache.load(app)?.let {
      feeds = it.feeds
      items = it.items
      articles = it.articles
    }
    if (!isConfigured) showSettings = true
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

  /** The full item with content, from the server or the offline cache, and marks it read. */
  suspend fun open(item: Item): Item {
    var full = articles[item.id] ?: item
    run {
      full = api.item(item.id)
      articles = articles + (item.id to full)
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
      feeds =
        feeds.map {
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
    pairJob =
      viewModelScope.launch {
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
}
