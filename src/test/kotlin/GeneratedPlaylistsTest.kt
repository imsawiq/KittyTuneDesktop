package com.alananasss.kittytune.data.mix

import com.alananasss.kittytune.domain.Track
import java.sql.DriverManager
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedPlaylistsTest {
    @Test fun `generated playlist preserves downloads and source while persisting deduplicated order`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { st ->
                st.execute("""CREATE TABLE downloaded_playlists (
                    id INTEGER PRIMARY KEY, title TEXT, artist TEXT, artworkUrl TEXT, trackCount INTEGER,
                    isUserCreated INTEGER, localCoverPath TEXT, permalinkUrl TEXT, isAlbum INTEGER,
                    addedAt INTEGER, isDownloaded INTEGER)""")
                st.execute("""CREATE TABLE downloaded_tracks (
                    id INTEGER PRIMARY KEY, title TEXT, artist TEXT, artworkUrl TEXT, duration INTEGER,
                    localAudioPath TEXT, localArtworkPath TEXT, downloadedAt INTEGER, source TEXT, permalinkUrl TEXT)""")
                st.execute("""CREATE TABLE playlist_track_cross_ref (
                    playlistId INTEGER, trackId INTEGER, addedAt INTEGER, PRIMARY KEY(playlistId, trackId))""")
                st.execute("INSERT INTO downloaded_tracks(id,title,localAudioPath,source) VALUES(2,'Downloaded','local.flac','spotify')")
            }
            val tracks = listOf(
                Track(2, "Remote copy", null, 1000L, null),
                Track(1, "First", "cover.jpg", 2000L, null, source = "youtube"),
                Track(2, "Duplicate", null, 1000L, null),
            )
            db.autoCommit = false
            GeneratedPlaylists.statements(-5, "Warm-up", "Me", tracks, null, 100).forEach { (sql, args) ->
                db.prepareStatement(sql).use { statement ->
                    args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                    statement.executeUpdate()
                }
            }
            db.commit()
            db.createStatement().use { st ->
                st.executeQuery("SELECT * FROM downloaded_playlists WHERE id=-5").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Warm-up", rs.getString("title"))
                    assertEquals("cover.jpg", rs.getString("artworkUrl"))
                    assertEquals(2, rs.getInt("trackCount"))
                    assertEquals(1, rs.getInt("isUserCreated"))
                }
                st.executeQuery("SELECT trackId FROM playlist_track_cross_ref ORDER BY addedAt").use { rs ->
                    val ids = buildList { while (rs.next()) add(rs.getLong(1)) }
                    assertEquals(listOf(2L, 1L), ids)
                }
                st.executeQuery("SELECT * FROM downloaded_tracks WHERE id=2").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("local.flac", rs.getString("localAudioPath"))
                    assertEquals("spotify", rs.getString("source"))
                }
                st.executeQuery("SELECT source FROM downloaded_tracks WHERE id=1").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("youtube", rs.getString(1))
                }
            }
        }
    }
}
