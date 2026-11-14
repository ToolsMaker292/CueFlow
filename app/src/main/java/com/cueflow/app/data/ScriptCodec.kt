package com.cueflow.app.data

import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Pure serialization and validation logic for the local script library.
 *
 * Keeping this object free of Android dependencies makes the data format easy to
 * test and keeps corrupt or hostile backup files from reaching the UI.
 */
@OptIn(ExperimentalSerializationApi::class)
object ScriptCodec {
    const val CURRENT_SCHEMA = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encodeLibrary(scripts: List<Script>): String =
        json.encodeToString(
            ScriptLibrary.serializer(),
            ScriptLibrary(schemaVersion = CURRENT_SCHEMA, scripts = scripts),
        )

    fun decodeLibrary(text: String): List<Script> {
        require(text.isNotBlank()) { "Empty backup" }
        val library = try {
            json.decodeFromString(ScriptLibrary.serializer(), text)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Invalid backup", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid backup", error)
        }
        require(library.schemaVersion in 1..CURRENT_SCHEMA) {
            "Unsupported schema version ${library.schemaVersion}"
        }
        return sanitize(library.scripts)
    }

    fun sanitize(scripts: List<Script>, now: Long = System.currentTimeMillis()): List<Script> {
        val seen = HashSet<String>(scripts.size)
        val clean = ArrayList<Script>(minOf(scripts.size, ScriptLimits.MAX_SCRIPTS))
        for (raw in scripts.take(ScriptLimits.MAX_SCRIPTS)) {
            var id = raw.id.trim()
            if (id.isBlank() || !seen.add(id)) {
                id = UUID.randomUUID().toString()
                seen.add(id)
            }
            val createdAt = raw.createdAt.takeIf { it > 0L } ?: now
            val updatedAt = raw.updatedAt.takeIf { it > 0L }?.coerceAtLeast(createdAt) ?: createdAt
            clean += Script(
                id = id,
                title = raw.title.trim().take(ScriptLimits.MAX_TITLE_LENGTH),
                content = raw.content
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .take(ScriptLimits.MAX_CONTENT_LENGTH),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
        return clean.sortedByDescending { it.updatedAt }
    }

    fun encodeText(script: Script): String {
        val title = script.title.trim()
        return buildString {
            if (title.isNotEmpty()) {
                append(title)
                append("\n\n")
            }
            append(script.content.trimEnd())
            append('\n')
        }
    }

    fun duplicate(script: Script, suffix: String, now: Long, newId: String): Script = script.copy(
        id = newId,
        title = (script.title.trim() + suffix).take(ScriptLimits.MAX_TITLE_LENGTH),
        createdAt = now,
        updatedAt = now,
    )
}
