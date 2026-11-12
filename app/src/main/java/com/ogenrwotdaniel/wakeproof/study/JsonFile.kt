package com.ogenrwotdaniel.wakeproof.study

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * One small value kept as a JSON file in the app's private storage, for notes
 * that belong to this device and need no table, migration or sync.
 *
 * Read lazily on first use; a file that is missing or damaged reads as [empty]
 * and is never a throw. While storage is locked (before the first unlock)
 * nothing is read or written, and changes live in memory only.
 * Written beside itself and renamed, so a kill part-way leaves the old file.
 */
class JsonFile<T>(
    private val file: () -> File,
    private val serializer: KSerializer<T>,
    private val empty: T,
    /**
     * Whether the storage can be read at all. Before the first unlock after a
     * restart it cannot, and the file looks missing: taken for empty then, the
     * next save would replace everything on disk with one entry.
     */
    private val readable: () -> Boolean
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var loaded = false
    private val _value = MutableStateFlow(empty)

    /** The value as last read or written; [empty] until [read] or [update] has run. */
    val value: StateFlow<T> = _value

    suspend fun read(): T = mutex.withLock { load() }

    /** Applies [change] and stores the result. A failed write keeps the new value in memory. */
    suspend fun update(change: (T) -> T): T = mutex.withLock {
        val next = change(load())
        _value.value = next
        // Not read yet (locked storage): writing now would replace what is on disk.
        if (loaded) withContext(Dispatchers.IO) {
            runCatching {
                val target = file()
                target.parentFile?.mkdirs()
                val partial = File(target.path + ".part")
                partial.writeText(json.encodeToString(serializer, next))
                if (!partial.renameTo(target)) partial.delete()
            }
        }
        next
    }

    private suspend fun load(): T {
        if (!loaded && runCatching(readable).getOrDefault(false)) {
            val read = withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString(serializer, file().readText()) }
            }
            // Missing is empty. A file that is there and cannot be parsed is
            // read as empty too, and the next save replaces it.
            loaded = true
            _value.value = read.getOrDefault(empty)
        }
        return _value.value
    }
}
