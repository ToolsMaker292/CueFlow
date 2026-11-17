package com.cueflow.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cueflow.app.data.AppSettings
import com.cueflow.app.data.DocumentImportError
import com.cueflow.app.data.DocumentImportException
import com.cueflow.app.data.DocumentImportOutcome
import com.cueflow.app.data.DocumentImporter
import com.cueflow.app.data.ImportReport
import com.cueflow.app.data.ReadingTime
import com.cueflow.app.data.Script
import com.cueflow.app.data.ScriptCodec
import com.cueflow.app.data.ScriptLimits
import com.cueflow.app.data.ScriptRepository
import com.cueflow.app.data.SettingsRepository
import com.cueflow.app.data.ThemeMode
import com.cueflow.app.data.ocr.OcrException
import com.cueflow.app.data.ocr.OcrImporter
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EditorDraft(
    val id: String? = null,
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScriptRepository(application.filesDir)
    private val settingsRepository = SettingsRepository(application)
    private val saveMutex = Mutex()

    val scripts: StateFlow<List<Script>> = repository.scripts
    val loading: StateFlow<Boolean> = repository.loading
    val loadError: StateFlow<Boolean> = repository.loadError
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _draft = MutableStateFlow<EditorDraft?>(null)
    val draft: StateFlow<EditorDraft?> = _draft.asStateFlow()

    val copySuffix: String
        get() = getApplication<Application>().getString(R.string.copy_suffix)

    init {
        val now = System.currentTimeMillis()
        val app = getApplication<Application>()
        val seed = Script(
            id = UUID.randomUUID().toString(),
            title = app.getString(R.string.sample_title),
            content = app.getString(R.string.sample_content),
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch {
            repository.load(seed)
        }
    }

    fun openEditor(scriptId: String?): Boolean {
        if (scriptId == null) {
            val now = System.currentTimeMillis()
            _draft.value = EditorDraft(createdAt = now, updatedAt = now)
            return true
        }
        val script = scripts.value.firstOrNull { it.id == scriptId } ?: return false
        _draft.value = EditorDraft(
            id = script.id,
            title = script.title,
            content = script.content,
            createdAt = script.createdAt,
            updatedAt = script.updatedAt,
        )
        return true
    }

    fun updateDraftTitle(value: String) {
        val current = _draft.value ?: return
        _draft.value = current.copy(
            title = value.take(ScriptLimits.MAX_TITLE_LENGTH),
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun updateDraftContent(value: String) {
        val current = _draft.value ?: return
        _draft.value = current.copy(
            content = value.take(ScriptLimits.MAX_CONTENT_LENGTH),
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun clearDraft() {
        _draft.value = null
    }

    suspend fun saveDraft(): Boolean = saveMutex.withLock {
        val current = _draft.value ?: return@withLock false
        if (current.title.isBlank() && current.content.isBlank()) return@withLock false
        val now = System.currentTimeMillis()
        val id = current.id ?: UUID.randomUUID().toString()
        val normalizedTitle = current.title.trim()
        val normalizedContent = current.content.trimEnd()
        val existing = scripts.value.firstOrNull { it.id == id }
        if (existing != null &&
            existing.title == normalizedTitle &&
            existing.content == normalizedContent
        ) {
            // Opening or leaving an untouched script must not bump its "last edited" time.
            _draft.value = current.copy(
                title = existing.title,
                content = existing.content,
                updatedAt = existing.updatedAt,
            )
            return@withLock true
        }
        val script = Script(
            id = id,
            title = normalizedTitle,
            content = normalizedContent,
            createdAt = current.createdAt,
            updatedAt = now,
        )
        val saved = repository.upsert(script)
        _draft.value = current.copy(
            id = saved.id,
            title = saved.title,
            content = saved.content,
            updatedAt = saved.updatedAt,
        )
        true
    }

    suspend fun deleteScript(script: Script): Boolean = repository.delete(script.id)

    suspend fun duplicateScript(script: Script): Script? = runCatching {
        repository.duplicate(script, copySuffix)
    }.getOrNull()

    fun estimateMinutes(content: String): Int = ReadingTime.estimateMinutes(content)

    suspend fun exportScript(uri: Uri, script: Script): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(ScriptCodec.encodeText(script))
            } ?: throw IOException("Cannot open destination")
        }
    }

    suspend fun exportBackup(uri: Uri): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(repository.exportJson())
            } ?: throw IOException("Cannot open destination")
            scripts.value.size
        }
    }

    suspend fun importBackup(uri: Uri): Result<ImportReport> = runCatching {
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val text = resolver.openInputStream(uri)?.use { stream ->
                stream.readUtf8Limited(ScriptLimits.MAX_BACKUP_CHARS)
            } ?: throw IOException("Cannot open backup")
            repository.importJson(text)
        }
    }

    suspend fun importDocument(uri: Uri): Result<DocumentImportOutcome> = runCatching {
        withContext(Dispatchers.IO) {
            val application = getApplication<Application>()
            val resolver = application.contentResolver
            var displayName: String? = null
            var size: Long? = null
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
            if ((size ?: 0L) > DocumentImporter.MAX_IMPORT_BYTES) {
                throw DocumentImportException(DocumentImportError.TOO_LARGE)
            }

            val sourceName = displayName ?: uri.lastPathSegment?.substringAfterLast('/')
            val isImage = DocumentImporter.isImageName(sourceName)
            val isPdf = DocumentImporter.isPdfName(sourceName)
            val text = try {
                if (isImage) {
                    OcrImporter.recognizeImage(application, uri)
                } else {
                    try {
                        resolver.openInputStream(uri)?.use { stream ->
                            DocumentImporter.extractText(stream, sourceName)
                        } ?: throw DocumentImportException(DocumentImportError.INVALID)
                    } catch (error: DocumentImportException) {
                        if (isPdf && error.reason == DocumentImportError.NO_TEXT) {
                            OcrImporter.recognizePdf(application, uri)
                        } else {
                            throw error
                        }
                    }
                }
            } catch (error: OcrException) {
                throw DocumentImportException(DocumentImportError.OCR_FAILED)
            }
            if (text.isBlank()) throw DocumentImportException(DocumentImportError.NO_TEXT)

            val truncated = text.length > ScriptLimits.MAX_CONTENT_LENGTH
            val content = text.take(ScriptLimits.MAX_CONTENT_LENGTH)
            val fallbackTitle = application.getString(R.string.import_document_default_title)
            val title = sourceName
                ?.takeUnless { it.startsWith("ocr-photo-") }
                ?.substringBeforeLast('.')
                ?.trim()
                .orEmpty()
                .ifBlank {
                    content.lineSequence()
                        .firstOrNull { it.isNotBlank() }
                        ?.take(80)
                        ?: fallbackTitle
                }
                .take(ScriptLimits.MAX_TITLE_LENGTH)
            val now = System.currentTimeMillis()
            val script = Script(
                id = UUID.randomUUID().toString(),
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now,
            )
            val saved = repository.upsert(script)
            DocumentImportOutcome(
                title = saved.title,
                content = saved.content,
                truncated = truncated,
            )
        }
    }

    fun setThemeMode(mode: ThemeMode) = settingsRepository.update { it.copy(themeMode = mode) }
    fun setDynamicColor(enabled: Boolean) =
        settingsRepository.update { it.copy(dynamicColor = enabled) }
    fun setDefaultSpeed(value: Int) =
        settingsRepository.update { it.copy(defaultSpeed = value) }
    fun setDefaultFontSize(value: Int) =
        settingsRepository.update { it.copy(defaultFontSize = value) }
    fun setDefaultCountdown(value: Int) =
        settingsRepository.update { it.copy(defaultCountdown = value) }
    fun setMirrorHorizontal(enabled: Boolean) =
        settingsRepository.update { it.copy(mirrorHorizontal = enabled) }
    fun setMirrorVertical(enabled: Boolean) =
        settingsRepository.update { it.copy(mirrorVertical = enabled) }
    fun setKeepScreenOn(enabled: Boolean) =
        settingsRepository.update { it.copy(keepScreenOn = enabled) }
    fun resetSettings() = settingsRepository.reset()

    private fun InputStream.readUtf8Limited(maxChars: Int): String {
        val reader = bufferedReader(Charsets.UTF_8)
        val result = StringBuilder()
        val buffer = CharArray(8192)
        var total = 0
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxChars) throw IOException("Backup is too large")
            result.append(buffer, 0, read)
        }
        return result.toString()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    return MainViewModel(application) as T
                }
            }
    }
}
