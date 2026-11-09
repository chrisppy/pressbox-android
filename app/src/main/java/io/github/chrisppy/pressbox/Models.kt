// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: MPL-2.0

package io.github.chrisppy.pressbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// JSON shapes of the pressbox API (see crates/pressbox-api in pressbox).
// Timestamps are Unix seconds.

@Serializable
data class Feed(
  val id: String,
  val title: String,
  val url: String,
  @SerialName("site_url") val siteUrl: String? = null,
  val tags: List<String> = emptyList(),
  val unread: Int,
  val total: Int,
  @SerialName("last_fetched") val lastFetched: Long? = null,
  @SerialName("last_error") val lastError: String? = null,
  /** Removed from the server config but kept for its starred items. */
  val archived: Boolean? = null,
) {
  val isArchived: Boolean
    get() = archived ?: false
}

@Serializable
data class Item(
  val id: Long,
  @SerialName("feed_id") val feedId: String,
  val title: String,
  val url: String? = null,
  val author: String? = null,
  val summary: String? = null,
  val content: String? = null,
  val published: Long,
  val read: Boolean,
  val starred: Boolean,
)

@Serializable
data class ItemPage(
  val items: List<Item>,
  @SerialName("next_cursor") val nextCursor: String? = null,
  /** Only from servers too old to give cursors. */
  @SerialName("next_offset") val nextOffset: Int? = null,
) {
  val next: NextPage?
    get() = nextCursor?.let { NextPage.Cursor(it) } ?: nextOffset?.let { NextPage.Offset(it) }
}

/** Where the next page of items starts. */
sealed interface NextPage {
  data class Cursor(val cursor: String) : NextPage

  data class Offset(val offset: Int) : NextPage
}

@Serializable data class Flag(val value: Boolean)

@Serializable data class Updated(val updated: Int)

@Serializable data class PairRequest(val name: String)

@Serializable
data class PairStart(
  val code: String,
  @SerialName("poll_id") val pollId: String,
  @SerialName("expires_in") val expiresIn: Int,
)

@Serializable data class PairApproved(val token: String, val name: String)

@Serializable data class ApproveRequest(val user: String?)

@Serializable data class Approved(val name: String, val user: String)

/** `GET /api/v1/version`. */
@Serializable data class ServerVersion(val version: String, val api: Int)

/** Who this device's token belongs to. */
@Serializable data class Me(val user: String, val admin: Boolean, val device: String? = null)

/** What the item list shows. */
sealed interface Source {
  data object All : Source

  data object Starred : Source

  data class OfFeed(val id: String) : Source

  /** Every feed with this tag (the server's `tags`, or folders from OPML). */
  data class Tagged(val tag: String) : Source
}
