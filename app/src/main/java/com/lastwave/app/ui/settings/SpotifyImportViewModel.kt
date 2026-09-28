package com.lastwave.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.spotify.LikedSongsTarget
import com.lastwave.app.data.spotify.SpotifyAuthManager
import com.lastwave.app.data.spotify.SpotifyAuthStatus
import com.lastwave.app.data.spotify.SpotifyCollection
import com.lastwave.app.data.spotify.SpotifyConnection
import com.lastwave.app.data.spotify.SpotifyImportRequest
import com.lastwave.app.data.spotify.SpotifyImportState
import com.lastwave.app.data.spotify.SpotifyLibraryImporter
import com.lastwave.app.data.spotify.SpotifyWebApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Library listing + picker selections; everything the screen owns locally. */
data class SpotifyLibraryState(
    val clientIdInput: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val likedCount: Int? = null,
    val playlists: List<SpotifyCollection> = emptyList(),
    val albums: List<SpotifyCollection> = emptyList(),
    val loaded: Boolean = false,
    val includeLiked: Boolean = true,
    val likedTarget: LikedSongsTarget = LikedSongsTarget.LASTWAVE_LIKED_SONGS,
    val selectedKeys: Set<String> = emptySet(),
)

data class SpotifyImportUiState(
    val clientId: String = "",
    val connection: SpotifyConnection? = null,
    val authStatus: SpotifyAuthStatus = SpotifyAuthStatus.Idle,
    val library: SpotifyLibraryState = SpotifyLibraryState(),
    val importState: SpotifyImportState = SpotifyImportState.Idle,
) {
    /** Text field value: the user's in-progress edit, else the saved ID. */
    val clientIdField: String get() = library.clientIdInput ?: clientId

    val selectedCount: Int
        get() = (if (library.includeLiked) 1 else 0) + library.selectedKeys.size
}

internal fun SpotifyCollection.selectionKey(): String = "${kind.name}:$id"

/**
 * Backs [SpotifyImportScreen]: Spotify sign-in (PKCE via a Custom Tab), the
 * library picker, and hand-off to [SpotifyLibraryImporter], which keeps
 * running if the user leaves the screen.
 */
@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    private val auth: SpotifyAuthManager,
    private val api: SpotifyWebApi,
    private val importer: SpotifyLibraryImporter,
) : ViewModel() {

    private val library = MutableStateFlow(SpotifyLibraryState())
    private var loadJob: Job? = null

    val uiState: StateFlow<SpotifyImportUiState> = combine(
        auth.clientId,
        auth.connection,
        auth.status,
        library,
        importer.state,
    ) { clientId, connection, status, lib, importState ->
        SpotifyImportUiState(clientId, connection, status, lib, importState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpotifyImportUiState())

    init {
        // Load the library whenever an account becomes connected (including
        // right after the browser callback finishes the token exchange).
        viewModelScope.launch {
            auth.connection
                .distinctUntilChangedBy { it?.refreshToken }
                .collect { connection ->
                    if (connection == null) {
                        loadJob?.cancel()
                        library.update { SpotifyLibraryState(clientIdInput = it.clientIdInput) }
                    } else if (!library.value.loaded) {
                        loadLibrary()
                    }
                }
        }
    }

    fun onClientIdChange(value: String) {
        library.update { it.copy(clientIdInput = value) }
    }

    /** Saves the client ID and returns the Spotify authorize page to open. */
    fun startLogin(open: (Uri) -> Unit) {
        viewModelScope.launch {
            try {
                library.value.clientIdInput?.let { auth.setClientId(it) }
                open(auth.beginLogin())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                library.update { it.copy(error = e.message ?: "Could not start Spotify sign-in.") }
            }
        }
    }

    fun disconnect() {
        if (importer.isRunning) return
        viewModelScope.launch { auth.disconnect() }
    }

    fun dismissAuthError() = auth.clearStatus()

    fun loadLibrary() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            library.update { it.copy(isLoading = true, error = null) }
            try {
                val profile = api.profile()
                auth.setProfile(profile.displayName, profile.id, profile.imageUrl)
                coroutineScope {
                    val liked = async { runCatching { api.likedSongsCount() }.getOrNull() }
                    val playlists = async { api.playlists() }
                    val albums = async { runCatching { api.savedAlbums() }.getOrDefault(emptyList()) }
                    val (likedCount, playlistList, albumList) = Triple(liked.await(), playlists.await(), albums.await())
                    library.update {
                        it.copy(
                            isLoading = false,
                            loaded = true,
                            likedCount = likedCount,
                            playlists = playlistList,
                            albums = albumList,
                            // Default: everything the user owns or follows.
                            selectedKeys = if (it.loaded) it.selectedKeys else playlistList.map { p -> p.selectionKey() }.toSet(),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                library.update { it.copy(isLoading = false, error = e.message ?: "Could not read your Spotify library.") }
            }
        }
    }

    fun setIncludeLiked(include: Boolean) = library.update { it.copy(includeLiked = include) }

    fun setLikedTarget(target: LikedSongsTarget) = library.update { it.copy(likedTarget = target) }

    fun toggle(collection: SpotifyCollection) = library.update {
        val key = collection.selectionKey()
        it.copy(selectedKeys = if (key in it.selectedKeys) it.selectedKeys - key else it.selectedKeys + key)
    }

    fun setAll(collections: List<SpotifyCollection>, selected: Boolean) = library.update {
        val keys = collections.map { c -> c.selectionKey() }.toSet()
        it.copy(selectedKeys = if (selected) it.selectedKeys + keys else it.selectedKeys - keys)
    }

    fun clearError() = library.update { it.copy(error = null) }

    fun startImport() {
        val lib = library.value
        val chosen = (lib.playlists + lib.albums).filter { it.selectionKey() in lib.selectedKeys }
        val started = importer.start(
            SpotifyImportRequest(
                likedSongs = lib.includeLiked,
                likedSongsTarget = lib.likedTarget,
                collections = chosen,
            ),
        )
        if (!started) {
            library.update { it.copy(error = "Pick at least one thing to import.") }
        }
    }

    fun cancelImport() = importer.cancel()

    fun dismissResult() {
        importer.dismiss()
        // A finished import may have ended on an expired session.
        viewModelScope.launch {
            if (auth.connection.first() != null && !library.value.loaded) loadLibrary()
        }
    }
}
