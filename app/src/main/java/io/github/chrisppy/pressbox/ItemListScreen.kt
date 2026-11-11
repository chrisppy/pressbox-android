// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package io.github.chrisppy.pressbox

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemListScreen(store: Store) {
  val palette = Theme.palette
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  var searching by rememberSaveable(store.source) { mutableStateOf(false) }
  var query by rememberSaveable(store.source) { mutableStateOf("") }
  var showMenu by remember { mutableStateOf(false) }
  val focus = remember { FocusRequester() }

  // Page in more as the end of the list comes into view.
  LaunchedEffect(listState, store.items) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
        .collect { store.loadMoreIfNeeded(it) }
  }

  // Mark items read as they scroll off the top while the user scrolls (not
  // in search results). Rows leaving because the list itself changes happen
  // while not scrolling.
  LaunchedEffect(listState) {
    var previous = listState.firstVisibleItemIndex
    snapshotFlow { listState.firstVisibleItemIndex }
        .collect { first ->
          if (
              first > previous &&
                  listState.isScrollInProgress &&
                  store.markReadOnScroll &&
                  store.search == null
          ) {
            store.items
                .subList(
                    previous.coerceAtMost(store.items.size),
                    first.coerceAtMost(store.items.size),
                )
                .filter { !it.read }
                .forEach { store.setReadAsync(it, true) }
          }
          previous = first
        }
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = {
              if (searching) {
                TextField(
                    value = query,
                    onValueChange = {
                      query = it
                      if (it.isEmpty() && store.search != null) store.setSearch(null)
                    },
                    placeholder = { Text("Search titles and text") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { store.setSearch(query) }),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
              } else {
                Text(
                    store.source?.let(store::title) ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
              }
            },
            actions = {
              if (searching) {
                IconButton(
                    onClick = {
                      searching = false
                      query = ""
                      if (store.search != null) store.setSearch(null)
                    }
                ) {
                  Icon(Icons.Outlined.Close, contentDescription = "Close search")
                }
              }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background),
        )
      },
      bottomBar = {
        BottomControls {
          Action(
              "Mark all as read",
              Icons.Outlined.DoneAll,
              enabled = store.source != Source.Starred && store.items.isNotEmpty(),
          ) {
            store.markAllRead()
          }
          Box {
            Action("Show", Icons.Outlined.FilterList) { showMenu = true }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
              for ((label, value) in listOf("Unread" to true, "All" to false)) {
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                      RadioButton(selected = store.unreadOnly == value, onClick = null)
                    },
                    onClick = {
                      showMenu = false
                      store.setUnreadOnly(value)
                    },
                )
              }
            }
          }
          Action("Search", Icons.Outlined.Search) { searching = true }
        }
      },
      containerColor = palette.background,
  ) { padding ->
    PullToRefreshBox(
        isRefreshing = store.isLoading,
        onRefresh = { scope.launch { store.reload() } },
        modifier = Modifier.padding(padding).fillMaxSize(),
    ) {
      LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(store.items, key = { _, item -> item.id }) { _, item ->
          SwipeableRow(store, item)
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
      }
      if (store.items.isEmpty() && !store.isLoading) {
        EmptyState(
            if (store.search != null) "No results for “${store.search}”"
            else if (store.unreadOnly) "All caught up" else "No items"
        )
      }
      Box(Modifier.align(Alignment.BottomCenter)) {
        if (store.isOffline) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBanner("Offline: showing saved items", error = false)
          }
        } else {
          store.errorMessage?.let { StatusBanner(it, error = true) }
        }
      }
    }
  }
}

@Composable
private fun EmptyState(text: String) {
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
        Icons.Outlined.CheckCircle,
        contentDescription = null,
        tint = Theme.palette.mutedText,
        modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.size(12.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = Theme.palette.mutedText)
  }
}

/** A row that toggles read when swiped right and starred when swiped left. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(store: Store, item: Item) {
  val palette = Theme.palette
  val current by rememberUpdatedState(item)
  val state =
      rememberSwipeToDismissBoxState(
          confirmValueChange = { value ->
            when (value) {
              SwipeToDismissBoxValue.StartToEnd -> store.setReadAsync(current, !current.read)
              SwipeToDismissBoxValue.EndToStart -> store.setStarred(current, !current.starred)
              SwipeToDismissBoxValue.Settled -> {}
            }
            // Snap back: the row stays, changed.
            false
          }
      )
  SwipeToDismissBox(
      state = state,
      backgroundContent = {
        val toRead = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        val toStar = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        when {
                          toRead -> palette.read
                          toStar -> palette.star
                          else -> Color.Transparent
                        }
                    )
                    .padding(horizontal = 24.dp),
            contentAlignment = if (toStar) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
          if (toRead) {
            Icon(
                if (item.read) Icons.Filled.Circle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (item.read) "Unread" else "Read",
                tint = Color.White,
            )
          } else if (toStar) {
            Icon(
                if (item.starred) Icons.Outlined.StarOutline else Icons.Filled.Star,
                contentDescription = if (item.starred) "Unstar" else "Star",
                tint = Color.White,
            )
          }
        }
      },
  ) {
    ItemRow(
        item = item,
        feedTitle = store.feedTitle(item.feedId),
        selected = store.selectedItem == item.id,
        playing = store.isCurrent(item),
        modifier =
            Modifier.clickable { store.selectedItem = item.id }
                .semantics {
                  customActions =
                      listOf(
                          CustomAccessibilityAction(if (item.read) "Mark unread" else "Mark read") {
                            store.setReadAsync(item, !item.read)
                            true
                          },
                          CustomAccessibilityAction(if (item.starred) "Unstar" else "Star") {
                            store.setStarred(item, !item.starred)
                            true
                          },
                      )
                },
    )
  }
}

@Composable
fun ItemRow(
    item: Item,
    feedTitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
) {
  val palette = Theme.palette
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .background(
                  if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                  else palette.background
              )
              .padding(horizontal = 16.dp, vertical = 10.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
          Modifier.size(8.dp)
              .background(if (item.read) Color.Transparent else palette.accent, CircleShape)
      )
      Spacer(Modifier.width(6.dp))
      Text(
          feedTitle,
          style = MaterialTheme.typography.labelMedium,
          color = palette.mutedText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
      )
      if (item.isEpisode) {
        Icon(
            when {
              playing -> Icons.AutoMirrored.Filled.VolumeUp
              item.position > 0 -> Icons.Filled.Contrast
              else -> Icons.Filled.GraphicEq
            },
            contentDescription =
                when {
                  playing -> "Playing"
                  item.position > 0 -> "Started"
                  else -> "Episode"
                },
            tint = palette.accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
      }
      if (item.starred) {
        Icon(
            Icons.Filled.Star,
            contentDescription = "Starred",
            tint = palette.star,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
      }
      Text(
          relative(item.published),
          style = MaterialTheme.typography.labelMedium,
          color = palette.mutedText,
      )
    }
    Spacer(Modifier.size(4.dp))
    Text(
        item.title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
        color = if (item.read) palette.mutedText else palette.text,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun relative(published: Long): String =
    DateUtils.getRelativeTimeSpanString(
            published * 1000,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        .toString()
