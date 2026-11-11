// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package io.github.chrisppy.pressbox

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

sealed class ApiException(message: String) : Exception(message) {
  class NotConfigured : ApiException("Set the server address in Settings.")

  class InvalidServer : ApiException("The server address isn't a valid URL.")

  class Unauthorized :
      ApiException("The server rejected this device's token. Pair it again in Settings.")

  class Forbidden : ApiException("Only admins can approve devices for other users.")

  class NotFound : ApiException("Not found. The code may be wrong or expired.")

  class Status(code: Int) : ApiException("The server returned HTTP $code.")
}

/** Talks to pressboxd's HTTP API. */
class ApiClient(private val server: String, private val token: String?) {
  companion object {
    val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    private val jsonType = "application/json".toMediaType()

    /** Whether an http address is on the local network or a tailnet. */
    fun isLocalHttp(server: String): Boolean {
      val url = server.trim().toHttpUrlOrNull() ?: return true
      if (url.scheme != "http") return true
      val host = url.host
      if (host == "localhost" || host.endsWith(".local") || host.endsWith(".ts.net")) return true
      val octets = host.split(".").mapNotNull { it.toIntOrNull() }
      if (octets.size != 4) return false
      val (a, b) = octets[0] to octets[1]
      return a == 10 ||
          a == 127 ||
          (a == 172 && b in 16..31) ||
          (a == 192 && b == 168) ||
          (a == 100 && b in 64..127)
    }
  }

  private fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
    val base = server.trim()
    if (base.isEmpty()) throw ApiException.NotConfigured()
    val parsed = base.trimEnd('/').toHttpUrlOrNull() ?: throw ApiException.InvalidServer()
    val prefix = parsed.encodedPath.trimEnd('/')
    val builder = parsed.newBuilder().encodedPath(prefix + path)
    for ((name, value) in query) builder.addQueryParameter(name, value)
    return builder.build()
  }

  /** Sends a request and returns the body and HTTP status. */
  private suspend fun raw(
      method: String,
      path: String,
      query: List<Pair<String, String>> = emptyList(),
      body: String? = null,
  ): Pair<String, Int> {
    val requestBody: RequestBody? =
        when {
          body != null -> body.toRequestBody(jsonType)
          method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
          else -> null
        }
    val request =
        Request.Builder()
            .url(url(path, query))
            .method(method, requestBody)
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .build()
    return withContext(Dispatchers.IO) {
      http.newCall(request).execute().use { response ->
        response.body.string() to response.code
      }
    }
  }

  private suspend fun send(
      method: String,
      path: String,
      query: List<Pair<String, String>> = emptyList(),
      body: String? = null,
  ): String {
    val (data, status) = raw(method, path, query, body)
    return when (status) {
      in 200..299 -> data
      401 -> throw ApiException.Unauthorized()
      403 -> throw ApiException.Forbidden()
      404 -> throw ApiException.NotFound()
      else -> throw ApiException.Status(status)
    }
  }

  private inline fun <reified T> decode(data: String): T =
      json.decodeFromString(serializer<T>(), data)

  private inline fun <reified T> encode(value: T): String =
      json.encodeToString(serializer<T>(), value)

  suspend fun feeds(): List<Feed> = decode(send("GET", "/api/v1/feeds"))

  suspend fun items(
      source: Source,
      unreadOnly: Boolean,
      search: String? = null,
      after: NextPage? = null,
      limit: Int = 100,
  ): ItemPage {
    val query = mutableListOf("limit" to limit.toString())
    when (source) {
      Source.All -> {}
      Source.Starred -> query += "starred" to "true"
      is Source.OfFeed -> query += "feed" to source.id
      is Source.Tagged -> query += "tag" to source.tag
    }
    if (!search.isNullOrEmpty()) {
      query += "q" to search
    } else if (unreadOnly && source != Source.Starred) {
      query += "unread" to "true"
    }
    when (after) {
      is NextPage.Cursor -> query += "cursor" to after.cursor
      is NextPage.Offset -> query += "offset" to after.offset.toString()
      null -> {}
    }
    return decode(send("GET", "/api/v1/items", query))
  }

  suspend fun item(id: Long): Item = decode(send("GET", "/api/v1/items/$id"))

  suspend fun setRead(id: Long, value: Boolean) {
    send("PUT", "/api/v1/items/$id/read", body = encode(Flag(value)))
  }

  suspend fun setStarred(id: Long, value: Boolean) {
    send("PUT", "/api/v1/items/$id/starred", body = encode(Flag(value)))
  }

  /** Saves where playback of an item's media stopped, in seconds. */
  suspend fun setPosition(id: Long, position: Int) {
    send("PUT", "/api/v1/items/$id/position", body = encode(Position(position)))
  }

  /** An item's chapters; none if it has no chapters file. */
  suspend fun chapters(id: Long): List<Chapter> =
      try {
        decode<Chapters>(send("GET", "/api/v1/items/$id/chapters")).chapters
      } catch (_: ApiException.NotFound) {
        emptyList()
      }

  /** An item's transcript; `null` if it has none. */
  suspend fun transcript(id: Long): Transcript? =
      try {
        decode<Transcript>(send("GET", "/api/v1/items/$id/transcript"))
      } catch (_: ApiException.NotFound) {
        null
      }

  suspend fun markFeedRead(feed: String): Int =
      decode<Updated>(send("POST", "/api/v1/feeds/${pathSegment(feed)}/read-all")).updated

  suspend fun refresh() {
    send("POST", "/api/v1/refresh")
  }

  // Pairing

  /** Asks the server to pair this device; show the returned code to the user. */
  suspend fun pairStart(name: String): PairStart =
      decode(send("POST", "/api/v1/pair", body = encode(PairRequest(name))))

  /**
   * The token once approved, `null` while still waiting. Throws [ApiException.NotFound] if the
   * request was denied or expired.
   */
  suspend fun pairPoll(pollId: String): PairApproved? {
    val (data, status) = raw("GET", "/api/v1/pair/${pathSegment(pollId)}")
    return when (status) {
      200 -> decode(data)
      202 -> null
      404 -> throw ApiException.NotFound()
      else -> throw ApiException.Status(status)
    }
  }

  suspend fun version(): ServerVersion = decode(send("GET", "/api/v1/version"))

  suspend fun me(): Me = decode(send("GET", "/api/v1/me"))

  /** Approves a pairing code, for [user] if given (admins only). */
  suspend fun approve(code: String, user: String? = null): Approved {
    val cleanCode = code.trim().uppercase()
    val cleanUser = user?.trim()?.ifEmpty { null }
    return decode(
        send(
            "POST",
            "/api/v1/pairings/${pathSegment(cleanCode)}/approve",
            body = encode(ApproveRequest(cleanUser)),
        )
    )
  }

  private fun pathSegment(value: String): String =
      HttpUrl.Builder()
          .scheme("http")
          .host("x")
          .addPathSegment(value)
          .build()
          .encodedPathSegments
          .first()
}

/** Errors that mean "no connection": show cached data instead of an error. */
fun Throwable.isOffline(): Boolean =
    this is java.net.UnknownHostException ||
        this is java.net.ConnectException ||
        this is java.net.SocketTimeoutException ||
        this is java.net.NoRouteToHostException ||
        (this is IOException && message?.contains("timeout", ignoreCase = true) == true)
