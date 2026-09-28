package com.lastwave.app.data.spotify

import android.util.Log
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.CsvPlaylistImporter
import com.lastwave.app.data.playlist.CsvRawTrack
import com.lastwave.app.data.playlist.ExternalTrackRow
import com.lastwave.app.data.playlist.LikedSongsManager
import com.lastwave.app.data.playlist.PlaylistRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val TAG = "SpotifyLibraryImport"

/** Where imported Spotify Liked Songs land. */
enum class LikedSongsTarget {
    /** Merge into LastWave's own ♥ Liked Songs (duplicates skipped). */
    LASTWAVE_LIKED_SONGS,
    /** Save as a separate "Spotify Liked Songs" playlist. */
    SEPARATE_PLAYLIST,
}

data class SpotifyImportRequest(
    val likedSongs: Boolean,
    val likedSongsTarget: LikedSongsTarget,
    val collections: List<SpotifyCollection>,
) {
    val stepCount: Int get() = (if (likedSongs) 1 else 0) + collections.size
}

/** Result of one imported source (Liked Songs, a playlist or an album). */
data class SpotifyImportOutcome(
    val name: String,
    val totalRows: Int,
    val matched: Int,
    val detail: String,
    val error: String? = null,
)

sealed interface SpotifyImportState {
    data object Idle : SpotifyImportState

    data class Running(
        val stepIndex: Int,
        val stepCount: Int,
        val label: String,
        val done: Int,
        val total: Int,
        val finished: List<SpotifyImportOutcome>,
    ) : SpotifyImportState

    data class Finished(
        val outcomes: List<SpotifyImportOutcome>,
        val cancelled: Boolean = false,
    ) : SpotifyImportState
}

/**
 * Imports a signed-in Spotify library: Liked Songs, playlists and saved
 * albums. Each source is read through [SpotifyWebApi], every track is matched
 * with [CsvPlaylistImporter.matchTrack] (the same strict verifier as file
 * imports), and the result is saved locally.
 *
 * Runs in the application scope so a long library import survives leaving the
 * screen; progress is published through [state].
 */
@Singleton
class SpotifyLibraryImporter @Inject constructor(
    private val api: SpotifyWebApi,
    private val matcher: CsvPlaylistImporter,
    private val playlistRepository: PlaylistRepository,
    private val likedSongsManager: LikedSongsManager,
    private val applicationScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val state: StateFlow<SpotifyImportState> = _state.asStateFlow()

    private var job: Job? = null

    /** Match results shared across sources: the same song in five playlists is searched once. */
    private val matchCache = ConcurrentHashMap<String, MatchResult>()

    val isRunning: Boolean get() = job?.isActive == true

    fun start(request: SpotifyImportRequest): Boolean {
        if (isRunning || request.stepCount == 0) return false
        matchCache.clear()
        job = applicationScope.launch(Dispatchers.IO) { run(request) }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished summary so the screen returns to the picker. */
    fun dismiss() {
        if (!isRunning) _state.value = SpotifyImportState.Idle
    }

    private suspend fun run(request: SpotifyImportRequest) {
        val outcomes = mutableListOf<SpotifyImportOutcome>()
        var step = 0
        try {
            if (request.likedSongs) {
                step++
                outcomes += runStep("Liked Songs") {
                    val (rows, tracks) = importSource(step, request.stepCount, "Liked Songs", outcomes) { onPage ->
                        api.likedSongs(onPage)
                    }
                    saveLikedSongs(rows, tracks, request.likedSongsTarget)
                }
            }
            for (collection in request.collections) {
                step++
                outcomes += runStep(collection.name) {
                    val (rows, tracks) = importSource(step, request.stepCount, collection.name, outcomes) { onPage ->
                        when (collection.kind) {
                            SpotifyCollection.Kind.PLAYLIST -> api.playlistTracks(collection.id, onPage)
                            SpotifyCollection.Kind.ALBUM -> api.albumTracks(collection.id)
                        }
                    }
                    saveCollection(collection, rows, tracks)
                }
            }
            _state.value = SpotifyImportState.Finished(outcomes.toList())
        } catch (e: CancellationException) {
            _state.value = SpotifyImportState.Finished(outcomes.toList(), cancelled = true)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Spotify import stopped", e)
            outcomes += SpotifyImportOutcome("Import stopped", 0, 0, "", error = e.message ?: "Unknown error")
            _state.value = SpotifyImportState.Finished(outcomes.toList())
        }
    }

    /**
     * Runs one source. A failure (e.g. a followed playlist Spotify hides from
     * development-mode apps) is recorded and the import moves on; only an
     * expired session stops everything.
     */
    private suspend fun runStep(name: String, block: suspend () -> SpotifyImportOutcome): SpotifyImportOutcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyAuthException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Import of $name failed", e)
            SpotifyImportOutcome(name, 0, 0, "Not imported", error = e.message ?: "Unknown error")
        }

    /** Reads one source, then matches its rows with bounded parallelism. */
    private suspend fun importSource(
        step: Int,
        stepCount: Int,
        label: String,
        finished: List<SpotifyImportOutcome>,
        fetch: suspend (onPage: (Int, Int?) -> Unit) -> List<ExternalTrackRow>,
    ): Pair<List<ExternalTrackRow>, List<GeneratedTrack>> {
        fun publish(text: String, done: Int, total: Int) {
            _state.value = SpotifyImportState.Running(step, stepCount, text, done, total, finished.toList())
        }
        publish("Reading $label…", 0, 0)
        val rows = fetch { loaded, total -> publish("Reading $label…", loaded, total ?: loaded) }
        val done = AtomicInteger(0)
        publish("Matching $label", 0, rows.size)
        val limiter = Semaphore(MATCH_PARALLELISM)
        val matched = coroutineScope {
            rows.map { row ->
                async {
                    limiter.withPermit {
                        val track = match(row)
                        val progress = done.incrementAndGet()
                        _state.update { current ->
                            (current as? SpotifyImportState.Running)?.copy(done = progress) ?: current
                        }
                        track
                    }
                }
            }.awaitAll()
        }
        return rows to matched.filterNotNull()
    }

    private suspend fun match(row: ExternalTrackRow): GeneratedTrack? {
        val key = "${row.title}|${row.artist}|${row.album.orEmpty()}".lowercase()
        matchCache[key]?.let { return it.track }
        val track = matcher.matchTrack(CsvRawTrack(title = row.title, artist = row.artist, album = row.album))
        matchCache[key] = MatchResult(track)
        return track
    }

    private suspend fun saveLikedSongs(
        rows: List<ExternalTrackRow>,
        tracks: List<GeneratedTrack>,
        target: LikedSongsTarget,
    ): SpotifyImportOutcome {
        if (tracks.isEmpty()) {
            return SpotifyImportOutcome("Liked Songs", rows.size, 0, "No songs could be matched")
        }
        return when (target) {
            LikedSongsTarget.LASTWAVE_LIKED_SONGS -> {
                // Spotify lists newest first; LastWave appends new likes at the
                // end, so add oldest first to keep the same chronology.
                val added = likedSongsManager.addAll(tracks.reversed())
                val already = tracks.distinctBy { it.key }.size - added
                SpotifyImportOutcome(
                    name = "Liked Songs",
                    totalRows = rows.size,
                    matched = tracks.size,
                    detail = buildString {
                        append("$added added to Liked Songs")
                        if (already > 0) append(", $already already liked")
                    },
                )
            }
            LikedSongsTarget.SEPARATE_PLAYLIST -> {
                val title = saveOrReplace(LIKED_PLAYLIST_TITLE, rows.size, tracks)
                SpotifyImportOutcome("Liked Songs", rows.size, tracks.size, "Saved as \"$title\"")
            }
        }
    }

    private suspend fun saveCollection(
        collection: SpotifyCollection,
        rows: List<ExternalTrackRow>,
        tracks: List<GeneratedTrack>,
    ): SpotifyImportOutcome {
        if (tracks.isEmpty()) {
            return SpotifyImportOutcome(collection.name, rows.size, 0, "No songs could be matched")
        }
        val title = saveOrReplace(collection.name, rows.size, tracks)
        return SpotifyImportOutcome(collection.name, rows.size, tracks.size, "Saved as \"$title\"")
    }

    /**
     * Re-importing refreshes the playlist a previous Spotify import created
     * instead of stacking "Name 2", "Name 3"… copies.
     */
    private suspend fun saveOrReplace(title: String, totalRows: Int, tracks: List<GeneratedTrack>): String {
        val subtitle = "$SUBTITLE_PREFIX • ${tracks.size} imported, ${totalRows - tracks.size} skipped"
        val previous = playlistRepository.findByTitle(title)?.takeIf { it.subtitle.startsWith(SUBTITLE_PREFIX) }
        if (previous != null && playlistRepository.replaceTracksForSync(previous.id, tracks) != null) {
            return previous.title
        }
        return playlistRepository.save(title = title, subtitle = subtitle, mode = "custom", tracks = tracks).title
    }

    private data class MatchResult(val track: GeneratedTrack?)

    private companion object {
        const val MATCH_PARALLELISM = 4
        const val SUBTITLE_PREFIX = "Spotify Import"
        const val LIKED_PLAYLIST_TITLE = "Spotify Liked Songs"
    }
}
