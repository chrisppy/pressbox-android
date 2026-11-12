// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package org.red_oxide.pressbox

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedListScreen(store: Store) {
  val scope = rememberCoroutineScope()
  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("pressbox") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Theme.palette.background),
        )
      },
      bottomBar = {
        BottomControls {
          Action("Settings", Icons.Outlined.Settings) { store.showSettings = true }
          Action("Refresh feeds", Icons.Outlined.Refresh, enabled = !store.isLoading) {
            store.refreshServer()
          }
        }
      },
      containerColor = Theme.palette.background,
  ) { padding ->
    PullToRefreshBox(
        isRefreshing = store.isLoading,
        onRefresh = { scope.launch { store.reload() } },
        modifier = Modifier.padding(padding).fillMaxSize(),
    ) {
      LazyColumn(Modifier.fillMaxSize()) {
        row(store, Source.All, "All items", Icons.Outlined.AllInbox, store.totalUnread)
        row(store, Source.Starred, "Starred", Icons.Outlined.StarOutline, 0)
        val tags = store.tags
        if (tags.isNotEmpty()) {
          header("Tags")
          for (tag in tags) {
            row(
                store,
                Source.Tagged(tag),
                tag,
                Icons.AutoMirrored.Outlined.Label,
                store.unread(tag),
            )
          }
        }
        header("Feeds")
        items(store.feeds.filter { !it.isArchived }, key = { "feed:" + it.id }) { feed ->
          SourceRow(
              store = store,
              source = Source.OfFeed(feed.id),
              title = feed.title,
              icon = Icons.Outlined.RssFeed,
              unread = feed.unread,
              error = feed.lastError,
          )
        }
        val archived = store.feeds.filter { it.isArchived }
        if (archived.isNotEmpty()) {
          header("Removed feeds")
          items(archived, key = { "archived:" + it.id }) { feed ->
            SourceRow(
                store = store,
                source = Source.OfFeed(feed.id),
                title = feed.title,
                icon = Icons.Outlined.RssFeed,
                unread = 0,
                muted = true,
            )
          }
          item {
            Text(
                "No longer in the server's config. Kept until you unstar their items.",
                style = MaterialTheme.typography.bodySmall,
                color = Theme.palette.mutedText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }
      }
    }
  }
}

private fun LazyListScope.header(title: String) {
  item(key = "header:$title") {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
  }
}

private fun LazyListScope.row(
    store: Store,
    source: Source,
    title: String,
    icon: ImageVector,
    unread: Int,
) {
  item(key = source.toString()) { SourceRow(store, source, title, icon, unread) }
}

@Composable
private fun SourceRow(
    store: Store,
    source: Source,
    title: String,
    icon: ImageVector,
    unread: Int,
    error: String? = null,
    muted: Boolean = false,
) {
  val palette = Theme.palette
  val selected = store.source == source
  ListItem(
      modifier =
          Modifier.selectable(selected = selected, role = Role.Button) { store.select(source) },
      colors =
          ListItemDefaults.colors(
              containerColor =
                  if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                  else palette.background
          ),
      leadingContent = { Icon(icon, contentDescription = null, tint = palette.mutedText) },
      headlineContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              title,
              color = if (muted) palette.mutedText else palette.text,
              maxLines = 1,
              modifier = Modifier.weight(1f, fill = false),
          )
          if (error != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.Warning,
                contentDescription = "Last fetch failed: $error",
                tint = palette.error,
                modifier = Modifier.size(18.dp),
            )
          }
        }
      },
      trailingContent =
          if (unread > 0) {
            { Text(unread.toString(), color = palette.mutedText) }
          } else null,
  )
}
