package com.lastwave.app.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyParsingTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun codeChallengeMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SpotifyAuthManager.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun parsesTokenResponseAndRejectsMissingAccessToken() {
        val tokens = SpotifyAuthManager.parseTokenResponse(
            obj("""{"access_token":"a","token_type":"Bearer","expires_in":3600,"refresh_token":"r"}"""),
        )!!
        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
        assertEquals(3600L, tokens.expiresInSeconds)
        // Refresh responses may omit refresh_token; the caller keeps the old one.
        assertNull(SpotifyAuthManager.parseTokenResponse(obj("""{"access_token":"a","expires_in":10}"""))!!.refreshToken)
        assertNull(SpotifyAuthManager.parseTokenResponse(obj("""{"error":"invalid_grant"}""")))
    }

    @Test
    fun parsesLikedSongsPageSkippingEpisodesAndRemovedTracks() {
        val page = SpotifyWebApi.parseTrackPage(
            obj(
                """
                {"total": 4, "next": "https://api.spotify.com/v1/me/tracks?offset=50&limit=50",
                 "items": [
                   {"added_at": "2024-01-01T00:00:00Z", "track": {"type": "track", "name": "Song A ",
                     "artists": [{"name": "Artist A"}, {"name": "Guest"}], "album": {"name": "Album A"}}},
                   {"track": {"type": "episode", "name": "Podcast"}},
                   {"track": null},
                   {"item": {"type": "track", "name": "Song B", "artists": [{"name": "Artist B"}],
                     "album": {"name": "Local"}, "is_local": true}}
                 ]}
                """.trimIndent(),
            ),
        )
        assertEquals(2, page.items.size)
        assertEquals("Song A", page.items[0].title)
        assertEquals("Artist A", page.items[0].artist)
        assertEquals("Album A", page.items[0].album)
        assertEquals("Song B", page.items[1].title)
        assertNull(page.items[1].album)
        assertEquals(4, page.total)
        assertEquals("https://api.spotify.com/v1/me/tracks?offset=50&limit=50", page.next)
    }

    @Test
    fun parsesPlaylistsWithEitherTrackCountField() {
        val page = SpotifyWebApi.parsePlaylistPage(
            obj(
                """
                {"next": null, "items": [
                  {"id": "p1", "name": "Road Trip", "owner": {"id": "u", "display_name": "Noah"},
                   "tracks": {"total": 12}, "images": [{"url": "https://i/1"}]},
                  {"id": "p2", "name": "", "owner": {"id": "u2"}, "items": {"total": 3}, "images": []},
                  {"name": "No id"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("p1", "p2"), page.items.map { it.id })
        assertEquals(12, page.items[0].trackCount)
        assertEquals("by Noah", page.items[0].subtitle)
        assertEquals("https://i/1", page.items[0].imageUrl)
        assertEquals("Untitled playlist", page.items[1].name)
        assertEquals(3, page.items[1].trackCount)
        assertNull(page.next)
    }

    @Test
    fun parsesSavedAlbumsAndAlbumTracks() {
        val albums = SpotifyWebApi.parseAlbumPage(
            obj(
                """
                {"items": [{"album": {"id": "a1", "name": "Record", "total_tracks": 9,
                  "artists": [{"name": "Band"}, {"name": "Other"}]}}]}
                """.trimIndent(),
            ),
        )
        assertEquals("a1", albums.items.single().id)
        assertEquals(SpotifyCollection.Kind.ALBUM, albums.items.single().kind)
        assertEquals("Band, Other", albums.items.single().subtitle)

        val tracks = SpotifyWebApi.parseAlbumTrackPage(
            obj("""{"items": [{"type": "track", "name": "Opener", "artists": [{"name": "Band"}]}]}"""),
            albumName = "Record",
        )
        assertEquals("Record", tracks.items.single().album)
        assertEquals("Band", tracks.items.single().artist)
    }
}
