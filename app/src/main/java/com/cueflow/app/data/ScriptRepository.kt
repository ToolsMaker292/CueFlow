package com.cueflow.app.data

import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * File-backed script store. Writes are atomic, reads recover from a failed write,
 * and a corrupt library is preserved for manual recovery instead of being deleted.
 */
class ScriptRepository(
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val libraryFile = File(filesDir, LIBRARY_FILE)
    private val atomicFile = AtomicFile(libraryFile)

    private val _scripts = MutableStateFlow<List<Script>>(emptyList())
    val scripts: StateFlow<List<Script>> = _scripts.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    suspend fun load(seed: Script): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val loaded = readLibrary()
                if (loaded == null) {
                    val fresh = ScriptCodec.sanitize(listOf(seed), clock())
                    persistLocked(fresh)
                    _scripts.value = fresh
                } else {
                    _scripts.value = loaded
                }
            } catch (_: Exception) {
                preserveCorruptFile()
                _loadError.value = true
                val fresh = ScriptCodec.sanitize(listOf(seed), clock())
                runCatching { persistLocked(fresh) }
                _scripts.value = fresh
            } finally {
                _loading.value = false
            }
        }
    }

    suspend fun upsert(script: Script): Script = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = ScriptCodec.sanitize(listOf(script), clock()).first()
            val next = _scripts.value
                .filterNot { it.id == clean.id }
                .plus(clean)
                .sortedByDescending { it.updatedAt }
                .take(ScriptLimits.MAX_SCRIPTS)
            persistLocked(next)
            _scripts.value = next
            clean
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _scripts.value.filterNot { it.id == id }
            if (next.size == _scripts.value.size) {
                false
            } else {
                persistLocked(next)
                _scripts.value = next
                true
            }
        }
    }

    suspend fun duplicate(script: Script, suffix: String): Script = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = clock()
            val copy = ScriptCodec.duplicate(
                script = script,
                suffix = suffix,
                now = now,
                newId = UUID.randomUUID().toString(),
            )
            val next = (_scripts.value + copy)
                .sortedByDescending { it.updatedAt }
                .take(ScriptLimits.MAX_SCRIPTS)
            persistLocked(next)
            _scripts.value = next
            copy
        }
    }

    fun exportJson(): String = ScriptCodec.encodeLibrary(_scripts.value)

    suspend fun importJson(text: String): ImportReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val incoming = ScriptCodec.decodeLibrary(text)
            val current = _scripts.value.associateBy { it.id }.toMutableMap()
            var added = 0
            var updated = 0
            var skipped = 0
            for (script in incoming) {
                val existing = current[script.id]
                when {
                    existing == null -> {
                        current[script.id] = script
                        added++
                    }
                    script.updatedAt > existing.updatedAt -> {
                        current[script.id] = script
                        updated++
                    }
                    else -> skipped++
                }
            }
            if (added + updated > 0) {
                val next = current.values
                    .sortedByDescending { it.updatedAt }
                    .take(ScriptLimits.MAX_SCRIPTS)
                persistLocked(next)
                _scripts.value = next
            }
            ImportReport(added = added, updated = updated, skipped = skipped)
        }
    }

    private fun readLibrary(): List<Script>? {
        if (!libraryFile.exists()) return null
        return try {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                ScriptCodec.decodeLibrary(reader.readText())
            }
        } catch (error: FileNotFoundException) {
            null
        }
    }

    private fun persistLocked(scripts: List<Script>) {
        val bytes = ScriptCodec.encodeLibrary(scripts).toByteArray(Charsets.UTF_8)
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: IOException) {
            atomicFile.failWrite(stream)
            throw error
        } catch (error: RuntimeException) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun preserveCorruptFile() {
        if (!libraryFile.exists()) return
        runCatching {
            val target = File(filesDir, "scripts-corrupt-${clock()}.json")
            libraryFile.copyTo(target, overwrite = false)
        }
    }

    private companion object {
        const val LIBRARY_FILE = "scripts.json"
    }
}
