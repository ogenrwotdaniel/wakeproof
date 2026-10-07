package com.ogenrwotdaniel.wakeproof.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicSelectionTest {

    private fun track(id: Long, artist: String, albumId: Long, folder: String) = Track(
        id = id, title = "t$id", artist = artist, album = "a$albumId", albumId = albumId,
        folder = folder, durationMs = 1_000
    )

    private val library = listOf(
        track(1, "Ludovico Einaudi", 10, "Music/Study/"),
        track(2, "Ludovico Einaudi", 11, "Music/Study/"),
        track(3, "Hans Zimmer", 12, "Download/")
    )

    @Test
    fun `every selection survives an encode and decode round trip`() {
        listOf(
            MusicSelection.All,
            MusicSelection.Folder("Music/Study/"),
            MusicSelection.Album(42, "Elements: Part 2"),
            MusicSelection.Artist("Hans Zimmer"),
            MusicSelection.PickedFolder(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FStudy"
            )
        ).forEach { assertEquals(it, MusicSelection.decode(it.encode())) }
    }

    @Test
    fun `unreadable selections play everything rather than nothing`() {
        assertEquals(MusicSelection.All, MusicSelection.decode(null))
        assertEquals(MusicSelection.All, MusicSelection.decode("folder:"))
        assertEquals(MusicSelection.All, MusicSelection.decode("album:notanumber:x"))
        assertEquals(MusicSelection.All, MusicSelection.decode("playlist:7"))
    }

    @Test
    fun `selections pick the right tracks`() {
        assertEquals(3, library.select(MusicSelection.All).size)
        assertEquals(listOf(1L, 2L), library.select(MusicSelection.Folder("Music/Study/")).map { it.id })
        assertEquals(listOf(3L), library.select(MusicSelection.Artist("Hans Zimmer")).map { it.id })
        assertEquals(listOf(2L), library.select(MusicSelection.Album(11, "a11")).map { it.id })
    }

    @Test
    fun `groups count their songs`() {
        val (folders, albums, artists) = groupsOf(library)
        assertEquals(listOf("Download" to 1, "Music/Study" to 2), folders.map { it.label to it.count })
        assertEquals(3, albums.size)
        assertEquals(listOf("Hans Zimmer" to 1, "Ludovico Einaudi" to 2), artists.map { it.label to it.count })
    }

    @Test
    fun `picked folders recognise audio by type or by extension`() {
        assertEquals(true, isAudioFile("track.bin", "audio/mpeg"))
        assertEquals(true, isAudioFile("Nuvole Bianche.FLAC", "application/octet-stream"))
        assertEquals(true, isAudioFile("song.m4a", null))
        assertEquals(false, isAudioFile("cover.jpg", "image/jpeg"))
        assertEquals(false, isAudioFile("notes", null))
    }

    @Test
    fun `file names and folder ids read as people expect`() {
        assertEquals("05 - Nuvole Bianche", titleFromFileName("05 - Nuvole Bianche.mp3"))
        assertEquals("v1.2 mix", titleFromFileName("v1.2 mix.ogg"))
        assertEquals("Music/Study", folderLabelFromDocumentId("primary:Music/Study"))
        assertEquals("Internal storage", folderLabelFromDocumentId("primary:"))
        assertEquals("Songs", folderLabelFromDocumentId("1A2B-3C4D:Songs"))
    }
}
