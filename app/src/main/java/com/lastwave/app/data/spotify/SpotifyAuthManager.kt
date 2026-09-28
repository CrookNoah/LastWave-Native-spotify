package com.lastwave.app.data.spotify

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "SpotifyAuth"

/** Redirect registered in the user's Spotify developer app; handled by MainActivity. */
const val SPOTIFY_REDIRECT_URI = "lastwave://spotify-callback"

/** Read-only scopes: Liked Songs, saved albums, and private/collaborative playlists. */
internal const val SPOTIFY_SCOPES =
    "user-library-read playlist-read-private playlist-read-collaborative user-read-private"

/** A signed-in Spotify account. Tokens never leave the app's DataStore. */
data class SpotifyConnection(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val displayName: String = "",
    val userId: String = "",
    val imageUrl: String? = null,
)

/** Progress of the browser round-trip, observed by the import screen. */
sealed interface SpotifyAuthStatus {
    data object Idle : SpotifyAuthStatus
    data object Exchanging : SpotifyAuthStatus
    data class Failed(val message: String) : SpotifyAuthStatus
}

/** [httpCode] is the token endpoint status when the failure came from Spotify, else 0. */
class SpotifyAuthException(message: String, val httpCode: Int = 0) : IOException(message)

/**
 * Spotify sign-in via Authorization Code with PKCE.
 *
 * PKCE needs only a client ID — no client secret ships in the APK. Spotify
 * keeps apps in development mode limited to a small allow-list of users, so
 * each user can register their own free developer app and paste its client
 * ID; a build may also bake one in through `SPOTIFY_CLIENT_ID`.
 *
 * The verifier and state are persisted before the browser opens so a login
 * still completes when Android kills the process while the browser is up.
 */
@Singleton
class SpotifyAuthManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    okHttpClient: OkHttpClient,
    private val applicationScope: CoroutineScope,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val random = SecureRandom()

    private val _status = MutableStateFlow<SpotifyAuthStatus>(SpotifyAuthStatus.Idle)
    val status: StateFlow<SpotifyAuthStatus> = _status.asStateFlow()

    private val prefs: Flow<Preferences> = dataStore.data.recoverPreferences("SpotifyAuthManager")

    /** User-entered client ID, falling back to one baked into the build. */
    val clientId: Flow<String> = prefs.map { it.readSafely(CLIENT_ID_KEY)?.trim().orEmpty().ifBlank { BUILT_IN_CLIENT_ID } }

    val connection: Flow<SpotifyConnection?> = prefs.map { it.toConnection() }

    suspend fun setClientId(value: String) {
        val clean = value.trim()
        dataStore.edit { prefs ->
            if (clean.isBlank()) prefs.remove(CLIENT_ID_KEY) else prefs[CLIENT_ID_KEY] = clean
        }
    }

    /**
     * Prepares a login and returns the Spotify authorize page to open in a
     * Custom Tab. The matching callback arrives through [capture].
     */
    suspend fun beginLogin(): Uri {
        val id = clientId.first()
        if (id.isBlank()) throw SpotifyAuthException("Add your Spotify client ID first.")
        val verifier = randomToken(64)
        val state = randomToken(24)
        dataStore.edit { prefs ->
            prefs[PENDING_VERIFIER_KEY] = verifier
            prefs[PENDING_STATE_KEY] = state
        }
        _status.value = SpotifyAuthStatus.Idle
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SPOTIFY_SCOPES)
            .build()
    }

    /**
     * Claims `lastwave://spotify-callback?...` intents. Returns true when the
     * intent was a Spotify callback; the token exchange runs in the background
     * and reports through [status] / [connection].
     */
    fun capture(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "lastwave" || uri.host != CALLBACK_HOST) return false
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        // Consume the data so a configuration change does not replay it.
        intent.data = null
        applicationScope.launch(Dispatchers.IO) {
            completeLogin(code, state, error)
        }
        return true
    }

    private suspend fun completeLogin(code: String?, state: String?, error: String?) {
        val stored = prefs.first()
        val expectedState = stored.readSafely(PENDING_STATE_KEY)
        val verifier = stored.readSafely(PENDING_VERIFIER_KEY)
        if (expectedState == null || verifier == null) return // stale or replayed callback
        if (state != expectedState) {
            _status.value = SpotifyAuthStatus.Failed("Spotify sign-in did not match this request. Try again.")
            return
        }
        clearPending()
        if (error != null || code.isNullOrBlank()) {
            _status.value = SpotifyAuthStatus.Failed(
                if (error == "access_denied") "Spotify access was cancelled." else "Spotify sign-in failed: ${error ?: "no code returned"}",
            )
            return
        }
        _status.value = SpotifyAuthStatus.Exchanging
        try {
            val id = clientId.first()
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", SPOTIFY_REDIRECT_URI)
                .add("client_id", id)
                .add("code_verifier", verifier)
                .build()
            val tokens = postToken(body)
            storeTokens(id, tokens, previousRefreshToken = null)
            _status.value = SpotifyAuthStatus.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Token exchange failed", e)
            _status.value = SpotifyAuthStatus.Failed(e.message ?: "Could not finish Spotify sign-in.")
        }
    }

    /**
     * A usable access token, refreshed when it is about to expire or when the
     * caller saw a 401 ([forceRefresh]).
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String = refreshMutex.withLock {
        val current = connection.first() ?: throw SpotifyAuthException("Sign in to Spotify first.")
        if (!forceRefresh && current.expiresAtMillis - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
            return@withLock current.accessToken
        }
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", current.refreshToken)
            .add("client_id", current.clientId)
            .build()
        val tokens = try {
            postToken(body)
        } catch (e: SpotifyAuthException) {
            // 400 invalid_grant: the user revoked access or the refresh token
            // aged out. Anything else (5xx, network) keeps the session.
            if (e.httpCode != 400) throw e
            disconnect()
            throw SpotifyAuthException("Your Spotify session expired. Sign in again.")
        }
        storeTokens(current.clientId, tokens, previousRefreshToken = current.refreshToken)
        tokens.accessToken
    }

    suspend fun setProfile(displayName: String, userId: String, imageUrl: String?) {
        dataStore.edit { prefs ->
            prefs[DISPLAY_NAME_KEY] = displayName
            prefs[USER_ID_KEY] = userId
            if (imageUrl.isNullOrBlank()) prefs.remove(IMAGE_URL_KEY) else prefs[IMAGE_URL_KEY] = imageUrl
        }
    }

    /** Forgets tokens and profile; the client ID stays for the next sign-in. */
    suspend fun disconnect() {
        dataStore.edit { prefs ->
            listOf(ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, DISPLAY_NAME_KEY, USER_ID_KEY, IMAGE_URL_KEY)
                .forEach { prefs.remove(it) }
            prefs.remove(EXPIRES_AT_KEY)
            prefs.remove(TOKEN_CLIENT_ID_KEY)
        }
        _status.value = SpotifyAuthStatus.Idle
    }

    fun clearStatus() {
        _status.value = SpotifyAuthStatus.Idle
    }

    private suspend fun clearPending() {
        dataStore.edit { prefs ->
            prefs.remove(PENDING_VERIFIER_KEY)
            prefs.remove(PENDING_STATE_KEY)
        }
    }

    private suspend fun storeTokens(clientId: String, tokens: TokenResponse, previousRefreshToken: String?) {
        val refresh = tokens.refreshToken ?: previousRefreshToken
            ?: throw SpotifyAuthException("Spotify did not return a refresh token.")
        dataStore.edit { prefs ->
            prefs[TOKEN_CLIENT_ID_KEY] = clientId
            prefs[ACCESS_TOKEN_KEY] = tokens.accessToken
            prefs[REFRESH_TOKEN_KEY] = refresh
            prefs[EXPIRES_AT_KEY] = System.currentTimeMillis() + tokens.expiresInSeconds * 1000L
        }
    }

    private suspend fun postToken(body: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val description = obj?.string("error_description") ?: obj?.string("error")
                throw SpotifyAuthException(
                    when {
                        description?.contains("redirect", ignoreCase = true) == true ->
                            "Spotify rejected the redirect URI. Add $SPOTIFY_REDIRECT_URI to your Spotify app."
                        description != null -> "Spotify: $description"
                        else -> "Spotify token request failed (HTTP ${response.code})."
                    },
                    httpCode = response.code,
                )
            }
            parseTokenResponse(obj) ?: throw SpotifyAuthException("Spotify returned an unreadable token response.")
        }
    }

    private fun Preferences.toConnection(): SpotifyConnection? {
        val access = readSafely(ACCESS_TOKEN_KEY)?.takeIf(String::isNotBlank) ?: return null
        val refresh = readSafely(REFRESH_TOKEN_KEY)?.takeIf(String::isNotBlank) ?: return null
        return SpotifyConnection(
            clientId = readSafely(TOKEN_CLIENT_ID_KEY).orEmpty(),
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = readSafely(EXPIRES_AT_KEY) ?: 0L,
            displayName = readSafely(DISPLAY_NAME_KEY).orEmpty(),
            userId = readSafely(USER_ID_KEY).orEmpty(),
            imageUrl = readSafely(IMAGE_URL_KEY),
        )
    }

    private fun randomToken(length: Int): String {
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return buildString(length) { bytes.forEach { append(VERIFIER_ALPHABET[(it.toInt() and 0xff) % VERIFIER_ALPHABET.length]) } }
    }

    internal data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long,
    )

    internal companion object {
        const val CALLBACK_HOST = "spotify-callback"
        const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        const val EXPIRY_MARGIN_MS = 60_000L
        const val VERIFIER_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val BUILT_IN_CLIENT_ID: String = BuildConfig.SPOTIFY_CLIENT_ID

        val CLIENT_ID_KEY = stringPreferencesKey("spotify_client_id")
        val TOKEN_CLIENT_ID_KEY = stringPreferencesKey("spotify_token_client_id")
        val ACCESS_TOKEN_KEY = stringPreferencesKey("spotify_access_token")
        val REFRESH_TOKEN_KEY = stringPreferencesKey("spotify_refresh_token")
        val EXPIRES_AT_KEY = longPreferencesKey("spotify_expires_at")
        val DISPLAY_NAME_KEY = stringPreferencesKey("spotify_display_name")
        val USER_ID_KEY = stringPreferencesKey("spotify_user_id")
        val IMAGE_URL_KEY = stringPreferencesKey("spotify_image_url")
        val PENDING_VERIFIER_KEY = stringPreferencesKey("spotify_pending_verifier")
        val PENDING_STATE_KEY = stringPreferencesKey("spotify_pending_state")

        /** RFC 7636 S256: base64url(sha256(verifier)) without padding. */
        fun codeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun parseTokenResponse(obj: JsonObject?): TokenResponse? {
            obj ?: return null
            val access = obj.string("access_token")?.takeIf(String::isNotBlank) ?: return null
            return TokenResponse(
                accessToken = access,
                refreshToken = obj.string("refresh_token")?.takeIf(String::isNotBlank),
                expiresInSeconds = (obj["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L,
            )
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    }
}
