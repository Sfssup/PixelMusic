package com.lostf1sh.pixelplayeross.data.repository

import android.content.Context
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.utils.AlbumArtUtils
import com.lostf1sh.pixelplayeross.utils.FolderArtistArtUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtistImageRepositoryTest {

    @Test
    fun `calculateCustomImageSampleSize keeps small bitmaps at full resolution`() {
        assertEquals(1, ArtistImageRepository.calculateCustomImageSampleSize(1024, 1024))
    }

    @Test
    fun `calculateCustomImageSampleSize aggressively downsamples oversized inputs`() {
        val sampleSize = ArtistImageRepository.calculateCustomImageSampleSize(12000, 8000)

        assertTrue(sampleSize >= 4)
        assertEquals(8, sampleSize)
    }

    @Test
    fun `locally stored content URI is returned`() = runTest {
        val musicDao = mockk<MusicDao>()
        val repository = ArtistImageRepository(musicDao, userPreferencesRepository(), mockk())
        val localUri = "content://media/external/images/media/42"

        coEvery { musicDao.getArtistIdByNormalizedName("Artist Name") } returns 42L
        coEvery { musicDao.getArtistImageUrl(42L) } returns localUri

        assertEquals(localUri, repository.getArtistImageUrl("Artist Name", 42L))
    }

    @Test
    fun `remote artist image URL stored in database is ignored`() = runTest {
        val musicDao = mockk<MusicDao>()
        val repository = ArtistImageRepository(musicDao, userPreferencesRepository(), mockk())

        coEvery { musicDao.getArtistIdByNormalizedName("Artist Name") } returns 42L
        coEvery { musicDao.getArtistImageUrl(42L) } returns "https://example.com/artist.jpg"
        coEvery { musicDao.getArtistImageUrlByNormalizedName("Artist Name") } returns null

        assertEquals(null, repository.getArtistImageUrl("Artist Name", 42L))
        coVerify(exactly = 0) { musicDao.updateArtistImageUrl(any(), any()) }
    }

    @Test
    fun `folder portrait is preferred when folder artwork is enabled`() = runTest {
        val musicDao = mockk<MusicDao>()
        val context = mockk<Context>()
        val image = File("/Music/Artist Name/artist.jpg")
        val directories = listOf("/Music/Artist Name/Album")
        val repository = ArtistImageRepository(
            musicDao,
            userPreferencesRepository(folderArtworkEnabled = true),
            context
        )
        coEvery { musicDao.getLocalArtistDirectories(42L, 64) } returns directories
        mockkObject(AlbumArtUtils, FolderArtistArtUtils)
        try {
            every { AlbumArtUtils.canReadImageFiles(context) } returns true
            every { FolderArtistArtUtils.findArtistImage(directories, "Artist Name", any()) } returns image

            assertEquals(image.absolutePath, repository.getArtistImageUrl("Artist Name", 42L))
        } finally {
            unmockkObject(AlbumArtUtils, FolderArtistArtUtils)
        }
    }

    @Test
    fun `manual artist image retains priority over opted in folder image`() = runTest {
        val musicDao = mockk<MusicDao>()
        val preferences = userPreferencesRepository(folderArtworkEnabled = true)
        val repository = ArtistImageRepository(musicDao, preferences, mockk())
        coEvery { musicDao.getArtistCustomImage(42L) } returns "/internal/custom_artist.jpg"

        assertEquals(
            "/internal/custom_artist.jpg",
            repository.getEffectiveArtistImageUrl(42L, "Artist Name")
        )
        coVerify(exactly = 0) { musicDao.getLocalArtistDirectories(any(), any()) }
        verify(exactly = 0) { preferences.useFolderAlbumArtFlow }
    }

    @Test
    fun `disabling folder artwork rejects a previously cached remote URL`() = runTest {
        val musicDao = mockk<MusicDao>()
        val context = mockk<Context>()
        val enabled = MutableStateFlow(true)
        val preferences = userPreferencesRepository()
        every { preferences.useFolderAlbumArtFlow } returns enabled
        val repository = ArtistImageRepository(musicDao, preferences, context)
        val directories = listOf("/Music/Artist Name/Album")
        val image = File("/Music/Artist Name/artist.jpg")
        coEvery { musicDao.getLocalArtistDirectories(42L, 64) } returns directories
        coEvery { musicDao.getArtistIdByNormalizedName("Artist Name") } returns 42L
        coEvery { musicDao.getArtistImageUrl(42L) } returns "https://example.com/artist.jpg"
        coEvery { musicDao.getArtistImageUrlByNormalizedName("Artist Name") } returns null
        mockkObject(AlbumArtUtils, FolderArtistArtUtils)
        try {
            every { AlbumArtUtils.canReadImageFiles(context) } returns true
            every { FolderArtistArtUtils.findArtistImage(directories, "Artist Name", any()) } returns image

            assertEquals(image.absolutePath, repository.getArtistImageUrl("Artist Name", 42L))
            enabled.value = false
            assertEquals(null, repository.getArtistImageUrl("Artist Name", 42L))
        } finally {
            unmockkObject(AlbumArtUtils, FolderArtistArtUtils)
        }
    }

    @Test
    fun `folder portrait lookup skips disk discovery without image permission`() = runTest {
        val musicDao = mockk<MusicDao>()
        val context = mockk<Context>()
        val repository = ArtistImageRepository(
            musicDao,
            userPreferencesRepository(folderArtworkEnabled = true),
            context
        )
        mockkObject(AlbumArtUtils)
        try {
            every { AlbumArtUtils.canReadImageFiles(context) } returns false

            assertEquals(null, repository.getFolderArtistImageUrl(42L, "Artist Name"))

            coVerify(exactly = 0) { musicDao.getLocalArtistDirectories(any(), any()) }
        } finally {
            unmockkObject(AlbumArtUtils)
        }
    }

    @Test
    fun `clearing images signals folder image consumers to reload`() {
        val repository = ArtistImageRepository(mockk(), userPreferencesRepository(), mockk())

        repository.clearCache()

        assertEquals(1L, repository.folderArtworkRevision.value)
    }

    private fun userPreferencesRepository(
        folderArtworkEnabled: Boolean = false
    ): UserPreferencesRepository {
        return mockk {
            every { this@mockk.useFolderAlbumArtFlow } returns flowOf(folderArtworkEnabled)
        }
    }
}
