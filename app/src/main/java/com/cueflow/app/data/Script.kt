package com.cueflow.app.data

import kotlinx.serialization.Serializable

/** A single teleprompter script. All content is stored locally as UTF-8 JSON. */
@Serializable
data class Script(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class ScriptLibrary(
    val schemaVersion: Int = ScriptCodec.CURRENT_SCHEMA,
    val scripts: List<Script> = emptyList(),
)

data class ImportReport(
    val added: Int,
    val updated: Int,
    val skipped: Int,
) {
    val changed: Int get() = added + updated
}

object ScriptLimits {
    const val MAX_TITLE_LENGTH = 120
    const val MAX_CONTENT_LENGTH = 50_000
    const val MAX_SCRIPTS = 500
    const val MAX_BACKUP_CHARS = 8_000_000
}
