// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package org.red_oxide.pressbox

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * What the app last saw, kept on disk so it opens instantly and can be read offline: the feed list,
 * the last item list, and full articles that were opened or prefetched.
 */
@Serializable
data class Snapshot(
    val feeds: List<Feed> = emptyList(),
    val items: List<Item> = emptyList(),
    val articles: Map<Long, Item> = emptyMap(),
)

object Cache {
  /** Most full articles kept; the oldest are dropped first. */
  const val MAX_ARTICLES = 300

  /** One write at a time, in order. */
  private val writer = Dispatchers.IO.limitedParallelism(1)

  private fun file(context: Context) = AtomicFile(File(context.filesDir, "pressbox-cache.json"))

  fun load(context: Context): Snapshot? =
      try {
        val text = file(context).readFully().toString(Charsets.UTF_8)
        ApiClient.json.decodeFromString(Snapshot.serializer(), text)
      } catch (_: Exception) {
        null
      }

  fun save(context: Context, scope: CoroutineScope, snapshot: Snapshot) {
    val trimmed =
        if (snapshot.articles.size > MAX_ARTICLES) {
          snapshot.copy(
              articles =
                  snapshot.articles.values
                      .sortedByDescending { it.published }
                      .take(MAX_ARTICLES)
                      .associateBy { it.id }
          )
        } else {
          snapshot
        }
    scope.launch(writer) {
      val file = file(context)
      val out =
          try {
            file.startWrite()
          } catch (_: Exception) {
            return@launch
          }
      try {
        out.write(
            ApiClient.json
                .encodeToString(Snapshot.serializer(), trimmed)
                .toByteArray(Charsets.UTF_8)
        )
        file.finishWrite(out)
      } catch (_: Exception) {
        // A cache that fails to save is not worth bothering the user about.
        file.failWrite(out)
      }
    }
  }

  fun clear(context: Context) {
    file(context).delete()
  }
}
