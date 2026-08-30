package com.ogenrwotdaniel.wakeproof.verify.water.corpus

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes the depth corpus to disk: one JPEG per shot plus the `shots.csv`
 * manifest `tools/water/sequences.py` expands.
 *
 * ### Where it writes, and why there
 *
 * `getExternalFilesDir` — the app's own directory on shared storage. That is
 * app-scoped (uninstall takes it with it, no storage permission needed, nothing
 * of the user's is touched) while still being readable over `adb pull`, which
 * is how the corpus actually gets to `tools/water/` on the workstation. The
 * media store was the alternative and is worse for this: corpus shots are
 * working data, not photographs, and dropping seventy pictures of a water
 * bottle into the camera roll is a mess to undo.
 *
 * ### This is debug-only on purpose
 *
 * The app does not retain verification photos — that is a deliberate privacy
 * property, stated in `docs/PRIVACY_POLICY.md`, and the whole point of a
 * corpus tool is retaining photos. The two are reconciled by the caller: the
 * capture screen is reachable only under `BuildConfig.DEBUG`, so nothing that
 * keeps an image survives into a release build. Do not wire this into a
 * shipping code path.
 */
class CorpusRecorder(private val context: Context) {

    /** `Android/data/<pkg>/files/water-corpus` on shared storage. */
    val directory: File
        get() = File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }

    private val manifest: File get() = File(directory, MANIFEST_NAME)

    /**
     * Saves one shot's image and appends its manifest row.
     *
     * The image is written *before* the row, so a failure between the two
     * leaves an orphan image rather than a manifest row pointing at nothing.
     * That direction matters: `sequences.py` builds pairs from manifest rows,
     * so a dangling row would silently produce pairs that cannot be evaluated,
     * while a spare file on disk is inert.
     */
    suspend fun record(shot: CorpusShot, jpeg: ByteArray): File = withContext(Dispatchers.IO) {
        val image = File(directory, shot.filename)
        image.writeBytes(jpeg)

        if (!manifest.exists()) {
            manifest.writeText(CORPUS_CSV_HEADER + "\n")
        }
        manifest.appendText(shot.toCsvRow() + "\n")
        image
    }

    /** Rows already recorded, so the UI can number the next shot and show progress. */
    suspend fun shotCount(): Int = withContext(Dispatchers.IO) {
        if (!manifest.exists()) 0 else (manifest.readLines().count { it.isNotBlank() } - 1).coerceAtLeast(0)
    }

    /** How many shots [sequence] already holds — the index for the next filename. */
    suspend fun shotsIn(sequence: String): Int = withContext(Dispatchers.IO) {
        if (!manifest.exists()) return@withContext 0
        manifest.readLines()
            .drop(1)
            .count { it.substringBefore(',').trim('"') == sequence }
    }

    private companion object {
        const val DIR_NAME = "water-corpus"
        const val MANIFEST_NAME = "shots.csv"
    }
}
