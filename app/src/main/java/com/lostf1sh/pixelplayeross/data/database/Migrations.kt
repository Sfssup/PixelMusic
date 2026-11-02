package com.lostf1sh.pixelplayeross.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Returns true if [table] already has a column named [column].
 *
 * Platform Auto Backup can restore a database that reports the right schema version but whose
 * columns have drifted, so migrations guard each `ALTER` with this check instead of assuming a
 * bare `ADD COLUMN` is safe (see the migration notes in CLAUDE.md / CONTRIBUTING).
 */
private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}

private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, ddl: String) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE `$table` ADD COLUMN $ddl")
    }
}

/**
 * v1 -> v2: album-artist support for the unified library (issue #8).
 *
 * - `songs.album_artist_id`: id of the *effective* album artist (the song's `album_artist` when
 *   present, otherwise its primary track artist). Source-independent, so the "Group by Album
 *   Artist" Artists tab can collapse on it at runtime without forcing a re-sync.
 * - `navidrome_songs.album_artist` / `jellyfin_songs.album_artist`: carry the server's
 *   album-artist tag through the cloud cache so the unified projection can populate the above.
 *
 * Additive and idempotent — each column is added only when missing. `album_artist_id` is then
 * backfilled to the existing primary artist so the collapsed Artists tab is populated before the
 * next library sync recomputes precise values (e.g. collapsing compilations under one artist).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("songs", "album_artist_id", "`album_artist_id` INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("navidrome_songs", "album_artist", "`album_artist` TEXT")
        db.addColumnIfMissing("jellyfin_songs", "album_artist", "`album_artist` TEXT")

        db.execSQL("UPDATE songs SET album_artist_id = artist_id WHERE album_artist_id = 0")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_album_artist_id` ON `songs` (`album_artist_id`)")
    }
}

/**
 * v2 -> v3: opt-in ListenBrainz scrobbling + MusicBrainz identifier storage.
 *
 * - `listenbrainz_pending_listens`: offline scrobble queue. Rows snapshot track metadata at
 *   enqueue time and are deleted on successful submission or permanent rejection.
 * - `songs.mb_recording_id` / `mb_release_id` / `mb_artist_id`: MusicBrainz identifiers, read
 *   from embedded file tags or applied via tag lookup. Scrobbles carry the recording MBID when
 *   known for better server-side matching.
 *
 * Additive and idempotent, per the Auto Backup drift guard above.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `listenbrainz_pending_listens` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `listened_at_ms` INTEGER NOT NULL,
                    `track_name` TEXT NOT NULL,
                    `artist_name` TEXT NOT NULL,
                    `release_name` TEXT,
                    `duration_ms` INTEGER,
                    `recording_mbid` TEXT,
                    `source` TEXT NOT NULL,
                    `attempts` INTEGER NOT NULL DEFAULT 0,
                    `created_at_ms` INTEGER NOT NULL
                )
            """.trimIndent()
        )

        db.addColumnIfMissing("songs", "mb_recording_id", "`mb_recording_id` TEXT")
        db.addColumnIfMissing("songs", "mb_release_id", "`mb_release_id` TEXT")
        db.addColumnIfMissing("songs", "mb_artist_id", "`mb_artist_id` TEXT")
    }
}

/**
 * v3 -> v4: audio bookmarks — named position markers inside a track (audiobooks, DJ sets,
 * podcasts), grouped per song on the Bookmarks screen.
 *
 * `audio_bookmarks` snapshots song metadata at save time so a bookmark stays presentable even
 * if the underlying song leaves the library. Additive and idempotent, per the Auto Backup
 * drift guard above.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `audio_bookmarks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `song_title` TEXT NOT NULL,
                    `artist_name` TEXT NOT NULL,
                    `album_art_uri` TEXT,
                    `title` TEXT NOT NULL,
                    `timestamp_ms` INTEGER NOT NULL,
                    `created_time` INTEGER NOT NULL
                )
            """.trimIndent()
        )
    }
}

/** v4 -> v5: app-private offline copies of Navidrome and Jellyfin tracks. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `offline_tracks` (
                    `download_id` TEXT NOT NULL,
                    `attempt_id` TEXT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `source_uri` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `mime_type` TEXT,
                    `local_path` TEXT,
                    `state` TEXT NOT NULL,
                    `bytes_downloaded` INTEGER NOT NULL,
                    `total_bytes` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `error_message` TEXT,
                    PRIMARY KEY(`download_id`)
                )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_tracks_source_uri` " +
                "ON `offline_tracks` (`source_uri`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_tracks_song_id` " +
                "ON `offline_tracks` (`song_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_tracks_state` " +
                "ON `offline_tracks` (`state`)"
        )
    }
}

/**
 * v5 -> v6: playlist positions are unique, while song ids may repeat.
 *
 * M3U playlists can intentionally contain the same track more than once. The previous
 * `(playlist_id, song_id)` primary key silently collapsed those entries during persistence.
 * Existing rows are copied in their current order and assigned dense positions so even legacy
 * databases with duplicate sort values migrate without dropping a song.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `playlist_songs_v6`")
        db.execSQL(
            """
                CREATE TABLE `playlist_songs_v6` (
                    `playlist_id` TEXT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    PRIMARY KEY(`playlist_id`, `sort_order`)
                )
            """.trimIndent()
        )
        db.execSQL(
            """
                INSERT INTO `playlist_songs_v6` (`playlist_id`, `song_id`, `sort_order`)
                SELECT
                    `playlist_id`,
                    `song_id`,
                    ROW_NUMBER() OVER (
                        PARTITION BY `playlist_id`
                        ORDER BY `sort_order`, `rowid`
                    ) - 1
                FROM `playlist_songs`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `playlist_songs`")
        db.execSQL("ALTER TABLE `playlist_songs_v6` RENAME TO `playlist_songs`")
        db.execSQL(
            "CREATE INDEX `index_playlist_songs_playlist_id_sort_order` " +
                "ON `playlist_songs` (`playlist_id`, `sort_order`)"
        )
        db.execSQL(
            "CREATE INDEX `index_playlist_songs_song_id` ON `playlist_songs` (`song_id`)"
        )
    }
}


/** v6 -> v7: remove cloud-provider caches, scrobble queue, and cloud offline downloads. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Remove provider-backed rows that were stored in the shared local-library tables.
        db.execSQL(
            "DELETE FROM playlist_songs WHERE playlist_id IN " +
                "(SELECT id FROM playlists WHERE id LIKE 'navidrome_playlist:%' OR id LIKE 'jellyfin_playlist:%')"
        )
        db.execSQL(
            "DELETE FROM playlist_songs WHERE song_id IN " +
                "(SELECT CAST(id AS TEXT) FROM songs WHERE source_type IN (5, 6))"
        )
        db.execSQL(
            "DELETE FROM favorites WHERE songId IN " +
                "(SELECT id FROM songs WHERE source_type IN (5, 6))"
        )
        db.execSQL(
            "DELETE FROM lyrics WHERE songId IN " +
                "(SELECT id FROM songs WHERE source_type IN (5, 6))"
        )
        db.execSQL(
            "DELETE FROM song_artist_cross_ref WHERE song_id IN " +
                "(SELECT id FROM songs WHERE source_type IN (5, 6))"
        )
        db.execSQL(
            "DELETE FROM song_engagements WHERE song_id IN " +
                "(SELECT CAST(id AS TEXT) FROM songs WHERE source_type IN (5, 6))"
        )
        db.execSQL(
            "DELETE FROM audio_bookmarks WHERE song_id IN " +
                "(SELECT CAST(id AS TEXT) FROM songs WHERE source_type IN (5, 6))"
        )
        db.execSQL(
            "DELETE FROM transition_rules WHERE " +
                "fromTrackId IN (SELECT CAST(id AS TEXT) FROM songs WHERE source_type IN (5, 6)) OR " +
                "toTrackId IN (SELECT CAST(id AS TEXT) FROM songs WHERE source_type IN (5, 6)) OR " +
                "playlistId LIKE 'navidrome_playlist:%' OR playlistId LIKE 'jellyfin_playlist:%'"
        )
        db.execSQL(
            "DELETE FROM album_art_themes WHERE " +
                "albumArtUriString LIKE 'navidrome:%' OR albumArtUriString LIKE 'jellyfin:%'"
        )
        // Remove remote artwork URLs left by the old online metadata providers.
        db.execSQL(
            "UPDATE artists SET image_url = NULL WHERE image_url LIKE 'http://%' OR image_url LIKE 'https://%'"
        )
        db.execSQL(
            "UPDATE artists SET custom_image_uri = NULL WHERE custom_image_uri LIKE 'http://%' OR custom_image_uri LIKE 'https://%'"
        )
        db.execSQL(
            "UPDATE albums SET album_art_uri_string = NULL WHERE album_art_uri_string LIKE 'http://%' OR album_art_uri_string LIKE 'https://%'"
        )
        db.execSQL(
            "UPDATE songs SET album_art_uri_string = NULL WHERE album_art_uri_string LIKE 'http://%' OR album_art_uri_string LIKE 'https://%'"
        )
        db.execSQL(
            "DELETE FROM album_art_themes WHERE albumArtUriString LIKE 'http:%' OR albumArtUriString LIKE 'https:%'"
        )
        db.execSQL("DELETE FROM songs WHERE source_type IN (5, 6)")
        db.execSQL(
            "DELETE FROM albums WHERE " +
                "(id <= -10000000000000 AND id > -11000000000000) OR " +
                "(id <= -13000000000000 AND id > -14000000000000)"
        )
        db.execSQL(
            "DELETE FROM artists WHERE " +
                "(id <= -11000000000000 AND id > -12000000000000) OR " +
                "(id <= -14000000000000 AND id > -15000000000000)"
        )
        db.execSQL(
            "DELETE FROM playlists WHERE id LIKE 'navidrome_playlist:%' OR id LIKE 'jellyfin_playlist:%'"
        )

        // Drop cloud provider caches and background-service queues.
        db.execSQL("DROP TABLE IF EXISTS `navidrome_playlists`")
        db.execSQL("DROP TABLE IF EXISTS `navidrome_songs`")
        db.execSQL("DROP TABLE IF EXISTS `jellyfin_playlists`")
        db.execSQL("DROP TABLE IF EXISTS `jellyfin_songs`")
        db.execSQL("DROP TABLE IF EXISTS `listenbrainz_pending_listens`")
        db.execSQL("DROP TABLE IF EXISTS `offline_tracks`")
    }
}
