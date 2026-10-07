package com.ogenrwotdaniel.wakeproof.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One song on the phone, as MediaStore indexes it. */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    /** Folder relative to the storage root, e.g. "Music/Study/". */
    val folder: String,
    val durationMs: Long,
    /**
     * Set for a song found by walking a folder the user picked; null for one
     * MediaStore indexed. Kept as a string so the class stays testable on the JVM.
     */
    val documentUri: String? = null
) {
    /** Where the file is read from. */
    val uri: Uri
        get() = documentUri?.let(Uri::parse) ?: ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id
        )
}

/**
 * Which part of the library plays during a session. Stored as a string in
 * `WakeProofSettings.musicSelection` via [encode] / [decode].
 */
sealed interface MusicSelection {
    data object All : MusicSelection
    data class Folder(val path: String) : MusicSelection
    data class Album(val albumId: Long, val name: String) : MusicSelection
    data class Artist(val name: String) : MusicSelection

    /**
     * A folder picked with the system's own folder browser, subfolders included.
     * [treeUri] is the `content://` tree URI that browser hands back.
     */
    data class PickedFolder(val treeUri: String) : MusicSelection

    fun encode(): String = when (this) {
        All -> "all"
        is Folder -> "folder:$path"
        is Album -> "album:$albumId:$name"
        is Artist -> "artist:$name"
        is PickedFolder -> "tree:$treeUri"
    }

    companion object {
        /** Anything unreadable plays everything — the user asked for music, not silence. */
        fun decode(value: String?): MusicSelection {
            if (value == null) return All
            val kind = value.substringBefore(':')
            val rest = value.substringAfter(':', "")
            return when {
                kind == "folder" && rest.isNotEmpty() -> Folder(rest)
                kind == "artist" && rest.isNotEmpty() -> Artist(rest)
                kind == "tree" && rest.isNotEmpty() -> PickedFolder(rest)
                kind == "album" -> {
                    val id = rest.substringBefore(':').toLongOrNull() ?: return All
                    Album(id, rest.substringAfter(':', ""))
                }
                else -> All
            }
        }
    }
}

/** The tracks [selection] picks out of [tracks], in library order. */
fun List<Track>.select(selection: MusicSelection): List<Track> = when (selection) {
    MusicSelection.All -> this
    is MusicSelection.Folder -> filter { it.folder == selection.path }
    is MusicSelection.Album -> filter { it.albumId == selection.albumId }
    is MusicSelection.Artist -> filter { it.artist == selection.name }
    // Walked separately (MusicLibrary.folderTracks); the MediaStore list cannot answer it.
    is MusicSelection.PickedFolder -> emptyList()
}

/** Extensions played from a picked folder when the provider reports no audio MIME type. */
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr")

/** True for a file the picked-folder walk should queue. */
fun isAudioFile(displayName: String, mimeType: String?): Boolean =
    mimeType?.startsWith("audio/") == true ||
        displayName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

/** "05 - Nuvole Bianche.mp3" → "05 - Nuvole Bianche": the title a picked file shows until it plays. */
fun titleFromFileName(displayName: String): String =
    displayName.substringBeforeLast('.').ifBlank { displayName }

/**
 * "primary:Music/Study" (a tree's document id) → "Music/Study"; a folder on an SD
 * card keeps its volume prefix dropped the same way. Empty means the storage root.
 */
fun folderLabelFromDocumentId(documentId: String): String =
    documentId.substringAfter(':', documentId).trim('/').ifEmpty { "Internal storage" }

/** A group the picker can offer, with how many songs it holds. */
data class MusicGroup(val selection: MusicSelection, val label: String, val count: Int)

/** Folders, albums and artists present in [tracks], each sorted by name. */
fun groupsOf(tracks: List<Track>): Triple<List<MusicGroup>, List<MusicGroup>, List<MusicGroup>> {
    val folders = tracks.groupBy { it.folder }.map { (folder, list) ->
        MusicGroup(MusicSelection.Folder(folder), folder.trimEnd('/').ifEmpty { "Storage root" }, list.size)
    }.sortedBy { it.label.lowercase() }
    val albums = tracks.groupBy { it.albumId }.map { (id, list) ->
        val name = list.first().album
        MusicGroup(MusicSelection.Album(id, name), name, list.size)
    }.sortedBy { it.label.lowercase() }
    val artists = tracks.groupBy { it.artist }.map { (artist, list) ->
        MusicGroup(MusicSelection.Artist(artist), artist, list.size)
    }.sortedBy { it.label.lowercase() }
    return Triple(folders, albums, artists)
}

/**
 * The songs stored on this phone — the user's own downloaded library, played by
 * WakeProof itself during a session. Two ways in: a folder picked in the system
 * folder browser ([folderTracks], no permission needed, the simplest), or the
 * whole MediaStore index ([tracks], needs READ_MEDIA_AUDIO).
 *
 * Read from MediaStore rather than a folder the user picks with the file
 * browser: every file Samsung Music can see is already indexed there, with
 * title, artist and album parsed, and a 200-song library lists in milliseconds.
 * A copy kept in Google Drive is a backup as far as this is concerned — only
 * the copy on the phone plays, which is also what makes it work offline and
 * under the focus pin.
 */
@Singleton
class MusicLibrary @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Every music file on the device, sorted by title. Empty without the permission. */
    suspend fun tracks(): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DURATION
        )
        // IS_MUSIC leaves out ringtones, notification sounds and voice notes,
        // which also live in MediaStore.Audio and are not a study playlist.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val result = mutableListOf<Track>()
        runCatching {
            context.contentResolver.query(
                collection, projection, selection, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += Track(
                        id = id,
                        title = c.getString(titleCol) ?: "Unknown",
                        artist = c.getString(artistCol)?.takeUnless { it == "<unknown>" }
                            ?: "Unknown artist",
                        album = c.getString(albumCol) ?: "Unknown album",
                        albumId = c.getLong(albumIdCol),
                        folder = c.getString(pathCol) ?: "",
                        durationMs = c.getLong(durationCol)
                    )
                }
            }
        }
        result
    }

    /** The songs [selection] plays: MediaStore for most, a folder walk for [MusicSelection.PickedFolder]. */
    suspend fun tracksFor(selection: MusicSelection): List<Track> = when (selection) {
        is MusicSelection.PickedFolder -> folderTracks(selection.treeUri)
        else -> tracks().select(selection)
    }

    /**
     * Keeps read access to a folder picked in the system browser across reboots,
     * and lets go of the previously picked one — a grant nobody uses is just a
     * standing permission.
     */
    fun rememberFolder(treeUri: Uri, previous: MusicSelection) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (previous is MusicSelection.PickedFolder && previous.treeUri != treeUri.toString()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(previous.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    /** "Music/Study" for a picked folder's tree URI. */
    fun folderLabel(treeUri: String): String = runCatching {
        folderLabelFromDocumentId(DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)))
    }.getOrDefault("Picked folder")

    /**
     * Every audio file under a folder the user picked, subfolders included,
     * sorted by name.
     *
     * Needs no media permission at all: the system folder browser granted read
     * access to exactly this folder and nothing else. It also finds songs
     * MediaStore never indexed — a folder with a `.nomedia` file in it, or
     * downloads still waiting for the media scanner — which is precisely the
     * "I can see them in My Files but WakeProof says no music" case.
     *
     * Titles come from file names here (reading tags from 200 files up front
     * would take seconds); the real title and artist are read when a song
     * starts playing. Empty if the grant has been revoked.
     */
    suspend fun folderTracks(treeUri: String): List<Track> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val result = mutableListOf<Track>()
        runCatching {
            val pending = ArrayDeque<String>()
            pending += DocumentsContract.getTreeDocumentId(tree)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            var visited = 0
            // Breadth-first with a cap, so a folder pointed at the whole of
            // internal storage cannot spin through every file on the phone.
            while (pending.isNotEmpty() && visited < MAX_FOLDERS_WALKED) {
                val parent = pending.removeFirst()
                visited++
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR -> pending += docId
                            isAudioFile(name, mime) -> result += Track(
                                id = docId.hashCode().toLong(),
                                title = titleFromFileName(name),
                                artist = "",
                                album = "",
                                albumId = 0,
                                folder = "",
                                durationMs = 0,
                                documentUri = DocumentsContract
                                    .buildDocumentUriUsingTree(tree, docId).toString()
                            )
                        }
                    }
                }
            }
        }
        result.sortedBy { it.title.lowercase() }
    }

    companion object {
        private const val MAX_FOLDERS_WALKED = 500
        const val PERMISSION = Manifest.permission.READ_MEDIA_AUDIO
        const val SAMSUNG_MUSIC_PACKAGE = "com.sec.android.app.music"
    }
}
