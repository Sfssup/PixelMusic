package com.lostf1sh.pixelplayeross.data.musicbrainz

import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
data class MusicBrainzMatch(
    val recordingId: String,
    val releaseId: String?,
    val artistId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val durationMs: Long?,
    val score: Int,
    val disambiguation: String?
)

@Singleton
class MusicBrainzRepository @Inject constructor(
    private val musicDao: MusicDao
) {
    /**
     * Online MusicBrainz lookups are intentionally disabled in this offline-only fork.
     * Existing local tags remain editable through the normal metadata editor.
     */
    suspend fun search(song: Song): List<MusicBrainzMatch> = emptyList()

    suspend fun apply(song: Song, match: MusicBrainzMatch) = withContext(Dispatchers.IO) {
        val songId = song.id.toLongOrNull()
            ?: musicDao.getSongIdByContentUri(song.contentUriString)
            ?: error("Song is not present in the unified library")
        musicDao.applyMusicBrainzMatch(
            songId = songId,
            title = match.title,
            artist = match.artist,
            album = match.album,
            year = match.year,
            recordingId = match.recordingId,
            releaseId = match.releaseId,
            artistId = match.artistId
        )
    }
}
