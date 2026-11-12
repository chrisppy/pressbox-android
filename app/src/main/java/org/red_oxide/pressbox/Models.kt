// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package org.red_oxide.pressbox

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
    /** The feed's artwork. */
    val image: String? = null,
    /** `podcast:medium`: `podcast`, `music`, `video`, ... */
    val medium: String? = null,
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
    /** Audio or video (a podcast episode), if any. */
    val media: Media? = null,
    /** Where this user stopped playing it, in seconds; 0 if not started. */
    val position: Int = 0,
) {
  val isEpisode: Boolean
    get() = media != null
}

/** An item's audio and video, from its enclosures and the Podcasting 2.0 tags. */
@Serializable
data class Media(
    /** The feed's own enclosure first, then `podcast:alternateEnclosure`s. */
    val enclosures: List<Enclosure>,
    /** Seconds. */
    val duration: Int? = null,
    val image: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** The chapters file; read it through [ApiClient.chapters]. */
    val chapters: String? = null,
    val transcripts: List<TranscriptFile> = emptyList(),
) {
  val video: Enclosure?
    get() = enclosures.firstOrNull { it.isVideo }

  /** The first that isn't video, else the video. */
  val audio: Enclosure?
    get() = enclosures.firstOrNull { !it.isVideo } ?: video
}

@Serializable
data class Enclosure(
    val url: String,
    val mime: String? = null,
    val length: Long? = null,
    val bitrate: Int? = null,
    val height: Int? = null,
    val title: String? = null,
) {
  val isVideo: Boolean
    get() = mime?.startsWith("video/") == true
}

@Serializable
data class TranscriptFile(
    val url: String,
    val mime: String,
    val lang: String? = null,
    val rel: String? = null,
)

/** Body of `PUT /api/v1/items/{id}/position`. */
@Serializable data class Position(val position: Int)

/** `GET /api/v1/items/{id}/chapters`. */
@Serializable data class Chapters(val chapters: List<Chapter>)

@Serializable
data class Chapter(
    /** Seconds from the start. */
    val start: Double,
    val end: Double? = null,
    val title: String? = null,
    val image: String? = null,
    val url: String? = null,
    /** Only changes the artwork; left out of chapter lists. */
    val hidden: Boolean = false,
)

/** `GET /api/v1/items/{id}/transcript`. */
@Serializable data class Transcript(val segments: List<Segment>)

@Serializable
data class Segment(
    val start: Double,
    val end: Double,
    val speaker: String? = null,
    val text: String,
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

/** `GET /api/v1/queue`: the Up Next queue, in order. */
@Serializable data class Queue(val items: List<Item>)

/** Body for `PUT /api/v1/queue`: the whole queue, in order. */
@Serializable data class QueueOrder(val ids: List<Long>)

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

  /** The Up Next queue, in playing order. */
  data object UpNext : Source

  data class OfFeed(val id: String) : Source

  /** Every feed with this tag (the server's `tags`, or folders from OPML). */
  data class Tagged(val tag: String) : Source
}
