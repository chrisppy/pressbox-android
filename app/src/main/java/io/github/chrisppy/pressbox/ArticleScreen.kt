// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: MPL-2.0

package io.github.chrisppy.pressbox

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.format.DateUtils
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ArticleScreen(store: Store, itemId: Long) {
  val context = LocalContext.current
  val palette = Theme.palette
  var full by remember(itemId) { mutableStateOf<Item?>(null) }
  // The list's copy, so read/starred changes show immediately.
  val current = store.item(itemId) ?: full

  LaunchedEffect(itemId) {
    val item = store.item(itemId) ?: return@LaunchedEffect
    full = store.open(item)
  }

  Scaffold(
    bottomBar = {
      BottomControls {
        val item = current ?: return@BottomControls
        Action(
          if (item.starred) "Unstar" else "Star",
          if (item.starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
          tint = if (item.starred) palette.star else null,
        ) {
          store.setStarred(item, !item.starred)
        }
        Action(
          if (item.read) "Mark unread" else "Mark read",
          if (item.read) Icons.Outlined.RadioButtonUnchecked else Icons.Filled.Circle,
        ) {
          store.setReadAsync(item, !item.read)
        }
        item.url?.let { link ->
          Action("Share", Icons.Outlined.Share) { share(context, item.title, link) }
          Action("Open in browser", Icons.Outlined.OpenInBrowser) { openLink(context, link) }
        }
      }
    },
    containerColor = palette.background,
  ) { padding ->
    Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
      val article = full
      if (article == null) {
        CircularProgressIndicator()
      } else {
        val date =
          DateUtils.formatDateTime(
            context,
            article.published * 1000,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
          )
        val html = page(article, store.feedTitle(article.feedId), date, palette)
        ArticleWebView(html, baseUrl = article.url)
      }
    }
  }
}

/** Shown beside the lists when no article is open. */
@Composable
fun NoArticle() {
  Column(
    Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      Icons.Outlined.Description,
      contentDescription = null,
      tint = Theme.palette.mutedText,
      modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.size(12.dp))
    Text(
      "No item selected",
      style = MaterialTheme.typography.titleMedium,
      color = Theme.palette.mutedText,
    )
  }
}

/** Renders article HTML with JavaScript off; links open in the browser. */
@Composable
private fun ArticleWebView(html: String, baseUrl: String?) {
  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { context ->
      WebView(context).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        setBackgroundColor(AndroidColor.TRANSPARENT)
        webViewClient =
          object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
              view: WebView,
              request: WebResourceRequest,
            ): Boolean {
              openLink(view.context, request.url.toString())
              return true
            }
          }
      }
    },
    update = { view ->
      // The page last loaded, so recomposing doesn't reload it.
      if (view.tag != html) {
        view.tag = html
        view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
      }
    },
  )
}

private fun escape(s: String): String =
  s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun page(item: Item, feedTitle: String, date: String, palette: Palette): String {
  val meta = listOfNotNull(feedTitle, item.author, date).joinToString(" · ") { escape(it) }
  val title =
    item.url?.let { "<a href=\"${escape(it)}\">${escape(item.title)}</a>" } ?: escape(item.title)
  val body = item.content ?: item.summary ?: ""
  return """
    <!DOCTYPE html>
    <html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="script-src 'none'">
    <style>
      ${Theme.articleCSS(palette)}
      body { font-family: sans-serif; font-size: 17px; line-height: 1.5; margin: 20px;
             max-width: 42em; overflow-wrap: break-word; color: var(--text);
             background: transparent; }
      h1 { font-size: 1.6em; font-weight: 700; line-height: 1.2; margin: 0 0 6px; }
      h1 a { color: inherit; text-decoration: none; }
      .meta { color: var(--muted); font-size: 0.8em; margin-bottom: 20px; }
      img, video, iframe { max-width: 100%; height: auto; }
      pre { overflow-x: auto; }
      a { color: var(--link); }
    </style></head>
    <body><h1>$title</h1><div class="meta">$meta</div>$body</body></html>
  """
    .trimIndent()
}

private fun openLink(context: Context, url: String) {
  try {
    context.startActivity(
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  } catch (_: ActivityNotFoundException) {}
}

private fun share(context: Context, title: String, url: String) {
  val send =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, title)
      putExtra(Intent.EXTRA_TEXT, url)
    }
  context.startActivity(Intent.createChooser(send, null))
}
