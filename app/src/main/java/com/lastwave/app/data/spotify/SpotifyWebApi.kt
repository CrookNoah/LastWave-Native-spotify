package com.lastwave.app.data.spotify

import com.lastwave.app.data.playlist.ExternalTrackRow
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

data class SpotifyProfile(
    val id: String,
    val displayName: String,
    val imageUrl: String?,
)

/** A playlist or saved album offered for import. */
data class SpotifyCollection(
    val id: String,
    val kind: Kind,
    val name: String,
    val subtitle: String,
    val trackCount: Int?,
    val imageUrl: String?,
) {
    enum class Kind { PLAYLIST, ALBUM }
}

/** One page of a Spotify paging object, already reduced to what we use. */
internal data class SpotifyPage<T>(
    val items: List<T>,
    val next: String?,
    val total: Int?,
)

/**
 * Minimal read-only Spotify Web API client for the account importer.
 *
 * Responses are walked as loose JSON rather than strict DTOs: Spotify has
 * been renaming fields (playlist `tracks` → `items`, playlist entry `track`
 * → `item`), and both spellings are accepted.
 */
@Singleton
class SpotifyWebApi @Inject constructor(
    okHttpClient: OkHttpClient,
    private val auth: SpotifyAuthManager,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun profile(): SpotifyProfile {
        val obj = getJson("$API/me")
        return parseProfile(obj) ?: throw IOException("Spotify returned an unreadable profile.")
    }

    /** Liked Songs total without downloading the whole library. */
    suspend fun likedSongsCount(): Int? = parseTotal(getJson("$API/me/tracks?limit=1"))

    /** Every Liked Song, newest first (Spotify's order). */
    suspend fun likedSongs(onPage: (loaded: Int, total: Int?) -> Unit = { _, _ -> }): List<ExternalTrackRow> =
        collectPages("$API/me/tracks?limit=50", ::parseTrackPage, onPage)

    // Offset paging can repeat an entry if the library changes mid-listing.
    suspend fun playlists(): List<SpotifyCollection> =
        collectPages("$API/me/playlists?limit=50", ::parsePlaylistPage).distinctBy { it.id }

    suspend fun savedAlbums(): List<SpotifyCollection> =
        collectPages("$API/me/albums?limit=50", ::parseAlbumPage).distinctBy { it.id }

    suspend fun playlistTracks(
        playlistId: String,
        onPage: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): List<ExternalTrackRow> {
        val itemsUrl = "$API/playlists/$playlistId/items?limit=50"
        return try {
            collectPages(itemsUrl, ::parseTrackPage, onPage)
        } catch (e: SpotifyApiException) {
            // Older API surface: /tracks predates the /items rename.
            if (e.code != 404) throw e
            collectPages("$API/playlists/$playlistId/tracks?limit=50", ::parseTrackPage, onPage)
        }
    }

    suspend fun albumTracks(albumId: String): List<ExternalTrackRow> {
        val album = getJson("$API/albums/$albumId")
        val albumName = album.string("name")
        return collectPages("$API/albums/$albumId/tracks?limit=50", { parseAlbumTrackPage(it, albumName) })
    }

    private suspend fun <T> collectPages(
        firstUrl: String,
        parse: (JsonObject) -> SpotifyPage<T>,
        onPage: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): List<T> {
        val all = mutableListOf<T>()
        var url: String? = firstUrl
        var pages = 0
        while (url != null && pages < MAX_PAGES) {
            val page = parse(getJson(url))
            all += page.items
            onPage(all.size, page.total)
            url = page.next?.takeIf { it.startsWith(API) }
            pages++
        }
        return all
    }

    private suspend fun getJson(url: String): JsonObject = withContext(Dispatchers.IO) {
        var refreshNext = false
        var refreshed = false
        var attempt = 0
        while (true) {
            attempt++
            val token = auth.accessToken(forceRefresh = refreshNext)
            refreshNext = false
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val text = response.body?.string().orEmpty()
                        return@withContext runCatching { json.parseToJsonElement(text).jsonObject }
                            .getOrElse { throw IOException("Spotify returned unreadable JSON.") }
                    }
                    response.code == 401 && !refreshed -> {
                        refreshed = true
                        refreshNext = true
                    }
                    response.code == 429 && attempt <= MAX_RETRIES -> {
                        val waitSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60) ?: 5L
                        delay(waitSeconds * 1000L)
                    }
                    response.code >= 500 && attempt <= MAX_RETRIES -> delay(attempt * 1000L)
                    else -> throw SpotifyApiException(response.code, errorMessage(response.code, response.body?.string()))
                }
            }
        }
        error("unreachable")
    }

    private fun errorMessage(code: Int, body: String?): String {
        val detail = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.let { (it["error"] as? JsonObject)?.string("message") }
        return when (code) {
            403 -> "Spotify refused access (${detail ?: "403"}). If your Spotify app is in development mode, add this account under User Management in the Spotify dashboard."
            404 -> "Spotify could not find that item."
            else -> "Spotify request failed (HTTP $code${detail?.let { ": $it" } ?: ""})."
        }
    }

    internal companion object {
        const val API = "https://api.spotify.com/v1"
        const val MAX_RETRIES = 5
        /** 50 per page: 400 pages covers 20,000 Liked Songs, above Spotify's own cap. */
        const val MAX_PAGES = 400

        fun parseProfile(obj: JsonObject): SpotifyProfile? {
            val id = obj.string("id") ?: return null
            return SpotifyProfile(
                id = id,
                displayName = obj.string("display_name")?.takeIf(String::isNotBlank) ?: id,
                imageUrl = obj.firstImage(),
            )
        }

        fun parseTotal(obj: JsonObject): Int? = obj.int("total")

        /**
         * Parses `/me/tracks` and `/playlists/{id}/items` pages. Entries hold
         * the track under `track` (or `item`); episodes and removed tracks are
         * skipped.
         */
        fun parseTrackPage(obj: JsonObject): SpotifyPage<ExternalTrackRow> {
            val rows = obj.array("items").mapNotNull { entry ->
                val wrapper = entry as? JsonObject ?: return@mapNotNull null
                val track = (wrapper["track"] as? JsonObject) ?: (wrapper["item"] as? JsonObject) ?: return@mapNotNull null
                trackRow(track, albumOverride = null)
            }
            return SpotifyPage(rows, obj.string("next"), obj.int("total"))
        }

        /** `/albums/{id}/tracks` returns simplified tracks with no album object. */
        fun parseAlbumTrackPage(obj: JsonObject, albumName: String?): SpotifyPage<ExternalTrackRow> {
            val rows = obj.array("items").mapNotNull { (it as? JsonObject)?.let { track -> trackRow(track, albumName) } }
            return SpotifyPage(rows, obj.string("next"), obj.int("total"))
        }

        fun parsePlaylistPage(obj: JsonObject): SpotifyPage<SpotifyCollection> {
            val playlists = obj.array("items").mapNotNull { element ->
                val playlist = element as? JsonObject ?: return@mapNotNull null
                val id = playlist.string("id") ?: return@mapNotNull null
                val owner = (playlist["owner"] as? JsonObject)?.let { it.string("display_name") ?: it.string("id") }
                // Renamed from `tracks` to `items`; both are {href, total}.
                val count = (playlist["items"] as? JsonObject)?.int("total")
                    ?: (playlist["tracks"] as? JsonObject)?.int("total")
                SpotifyCollection(
                    id = id,
                    kind = SpotifyCollection.Kind.PLAYLIST,
                    name = playlist.string("name")?.clean().orEmpty().ifBlank { "Untitled playlist" },
                    subtitle = owner?.let { "by $it" }.orEmpty(),
                    trackCount = count,
                    imageUrl = playlist.firstImage(),
                )
            }
            return SpotifyPage(playlists, obj.string("next"), obj.int("total"))
        }

        fun parseAlbumPage(obj: JsonObject): SpotifyPage<SpotifyCollection> {
            val albums = obj.array("items").mapNotNull { element ->
                val album = (element as? JsonObject)?.get("album") as? JsonObject ?: return@mapNotNull null
                val id = album.string("id") ?: return@mapNotNull null
                SpotifyCollection(
                    id = id,
                    kind = SpotifyCollection.Kind.ALBUM,
                    name = album.string("name")?.clean().orEmpty().ifBlank { "Untitled album" },
                    subtitle = album.artistNames(),
                    trackCount = album.int("total_tracks"),
                    imageUrl = album.firstImage(),
                )
            }
            return SpotifyPage(albums, obj.string("next"), obj.int("total"))
        }

        private fun trackRow(track: JsonObject, albumOverride: String?): ExternalTrackRow? {
            val type = track.string("type")
            if (type != null && type != "track") return null // podcast episodes
            val title = track.string("name")?.clean()?.takeIf(String::isNotBlank) ?: return null
            val album = albumOverride ?: (track["album"] as? JsonObject)?.string("name")
            return ExternalTrackRow(
                title = title,
                // Primary artist only: the matcher compares it against the
                // uploader, and "A, B, C" rarely equals any single channel.
                artist = track.artistNames(limit = 1),
                album = album?.clean()?.takeIf(String::isNotBlank)
                    // Local files carry a placeholder album; do not require it.
                    ?.takeUnless { track.bool("is_local") == true },
            )
        }

        private fun JsonObject.artistNames(limit: Int = Int.MAX_VALUE): String =
            array("artists").mapNotNull { (it as? JsonObject)?.string("name")?.clean()?.takeIf(String::isNotBlank) }
                .take(limit)
                .joinToString(", ")

        private fun JsonObject.firstImage(): String? =
            (array("images").firstOrNull() as? JsonObject)?.string("url")

        private fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()
        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
        private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
        private fun String.clean(): String = replace(' ', ' ').trim()
    }
}

class SpotifyApiException(val code: Int, message: String) : IOException(message)
