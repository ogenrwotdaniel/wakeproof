package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ogenrwotdaniel.wakeproof.music.MusicGroup
import com.ogenrwotdaniel.wakeproof.music.MusicLibrary
import com.ogenrwotdaniel.wakeproof.music.MusicSelection
import com.ogenrwotdaniel.wakeproof.pomodoro.NowPlaying

/**
 * The user's own music as the study sound: what plays, shuffle, and the
 * transport for the song in progress.
 *
 * Replaces the Spotify hand-off. That worked by deep-linking out to another app
 * and firing media keys at whichever app held the media session — so it could
 * not fade, it could not open at all while the phone was pinned, and its keys
 * landed on the wrong app as often as the right one. Playing the files on the
 * phone from inside the timer's own service has none of those problems.
 */
@Composable
fun MusicLibraryControls(
    library: PomodoroViewModel.LibraryState,
    selection: MusicSelection,
    shuffle: Boolean,
    nowPlaying: NowPlaying?,
    sessionActive: Boolean,
    onLoad: () -> Unit,
    onSelect: (MusicSelection) -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onLoad()
        if (granted) picking = true
    }
    // The system's own folder browser. The easiest way in: tap a folder, done —
    // no permission dialog, and WakeProof can read that folder and nothing else.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onFolderPicked) }

    LaunchedEffect(Unit) { onLoad() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val picked = selection as? MusicSelection.PickedFolder
        when {
            picked != null -> {
                Text(
                    text = "📁 ${library.pickedFolderLabel ?: "Picked folder"}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val count = library.pickedFolderCount
                Text(
                    text = when (count) {
                        null -> "Counting songs…"
                        0 -> "No songs found in this folder or its subfolders."
                        else -> "$count songs, subfolders included"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (count == 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            library.hasPermission && library.tracks.isNotEmpty() -> {
                Text(
                    text = selectionLabel(selection),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${library.countFor(selection)} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> Text(
                text = "Point WakeProof at the folder your songs are in. They play from " +
                    "the phone, offline — nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (picked != null) "📁 Choose a different folder" else "📁 Choose a music folder")
        }
        TextButton(
            onClick = {
                if (library.hasPermission) picking = true
                else permissionLauncher.launch(MusicLibrary.PERMISSION)
            }
        ) { Text("Or pick an album or artist from all my music") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Shuffle", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = shuffle, onCheckedChange = onShuffleChange)
        }

        if (sessionActive && nowPlaying != null) {
            NowPlayingRow(nowPlaying = nowPlaying, onPrevious = onPrevious, onNext = onNext)
        } else {
            Text(
                text = "Plays and pauses with the timer, fading in and out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Samsung Music stays the place to manage the library — playlists,
        // tags, deleting songs. Not offered mid-session: the pin would refuse it.
        val samsungMusic = remember {
            context.packageManager.getLaunchIntentForPackage(MusicLibrary.SAMSUNG_MUSIC_PACKAGE)
        }
        if (samsungMusic != null && !sessionActive) {
            TextButton(
                onClick = {
                    context.startActivity(samsungMusic.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            ) { Text("Manage songs in Samsung Music") }
        }
    }

    if (picking) {
        MusicPickerDialog(
            library = library,
            selected = selection,
            onPick = {
                onSelect(it)
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}

/** Song, artist and ⏮ ⏭ — shared by the normal screen and focus mode. */
@Composable
fun NowPlayingRow(
    nowPlaying: NowPlaying,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start
    ) {
        Column(Modifier.weight(1f, fill = !centered)) {
            Text(
                text = "♪ ${nowPlaying.title}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start
            )
            Text(
                text = nowPlaying.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start
            )
        }
        TextButton(onClick = onPrevious) { Text("⏮") }
        TextButton(onClick = onNext) { Text("⏭") }
    }
}

private fun selectionLabel(selection: MusicSelection): String = when (selection) {
    MusicSelection.All -> "All my music"
    is MusicSelection.Folder -> "Folder: ${selection.path.trimEnd('/').substringAfterLast('/')}"
    is MusicSelection.Album -> "Album: ${selection.name}"
    is MusicSelection.Artist -> "Artist: ${selection.name}"
    is MusicSelection.PickedFolder -> "Picked folder"
}

@Composable
private fun MusicPickerDialog(
    library: PomodoroViewModel.LibraryState,
    selected: MusicSelection,
    onPick: (MusicSelection) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Folders", "Albums", "Artists")
    val groups: List<MusicGroup> = when (tab) {
        0 -> library.folders
        1 -> library.albums
        else -> library.artists
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What should play?") },
        text = {
            Column {
                OutlinedButton(
                    onClick = { onPick(MusicSelection.All) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        (if (selected == MusicSelection.All) "✓ " else "") +
                            "All my music (${library.tracks.size})"
                    )
                }
                PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(groups, key = { it.selection.encode() }) { group ->
                        TextButton(onClick = { onPick(group.selection) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = (if (group.selection == selected) "✓ " else "") +
                                    "${group.label} (${group.count})",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
