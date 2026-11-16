package com.cueflow.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cueflow.app.MainViewModel
import com.cueflow.app.R
import com.cueflow.app.data.ReadingTime
import com.cueflow.app.data.Script
import com.cueflow.app.data.DocumentImportError
import com.cueflow.app.data.DocumentImportException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNewScript: () -> Unit,
    onEditScript: (String) -> Unit,
    onPlayScript: (String) -> Unit,
    onOpenSettings: () -> Unit,
    notify: (String) -> Unit,
) {
    val context = LocalContext.current
    val scripts by viewModel.scripts.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Script?>(null) }
    var exportTarget by remember { mutableStateOf<Script?>(null) }

    val importDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                viewModel.importDocument(uri).fold(
                    onSuccess = { imported ->
                        notify(
                            context.getString(
                                if (imported.truncated) {
                                    R.string.import_document_truncated
                                } else {
                                    R.string.import_document_success
                                },
                                imported.title,
                            ),
                        )
                    },
                    onFailure = { error ->
                        val message = when ((error as? DocumentImportException)?.reason) {
                            DocumentImportError.NO_TEXT -> R.string.import_document_no_text
                            DocumentImportError.TOO_LARGE -> R.string.import_document_too_large
                            DocumentImportError.UNSUPPORTED -> R.string.import_document_unsupported
                            else -> R.string.import_document_failed
                        }
                        notify(context.getString(message))
                    },
                )
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            scope.launch {
                val result = viewModel.exportScript(uri, target)
                notify(
                    context.getString(
                        if (result.isSuccess) R.string.export_success else R.string.export_failed,
                    ),
                )
            }
        }
    }

    val filtered = remember(scripts, query) {
        if (query.isBlank()) {
            scripts
        } else {
            val needle = query.trim()
            scripts.filter {
                it.title.contains(needle, ignoreCase = true) ||
                    it.content.contains(needle, ignoreCase = true)
            }
        }
    }

    val totalCharacters = remember(scripts) {
        scripts.sumOf { ReadingTime.countCharacters(it.content) }
    }
    val scriptCountLabel = pluralStringResource(
        R.plurals.script_count,
        scripts.size,
        scripts.size,
    )
    val characterCountLabel = pluralStringResource(
        R.plurals.character_count,
        totalCharacters,
        totalCharacters,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "CueFlow",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNewScript,
                        modifier = Modifier.testTag("home_new_script"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.home_new_script),
                        )
                    }
                    IconButton(
                        onClick = {
                            importDocumentLauncher.launch(DOCUMENT_MIME_TYPES)
                        },
                        modifier = Modifier.testTag("home_import_document"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileOpen,
                            contentDescription = stringResource(R.string.home_import_document),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                HeroPanel(
                    scriptCountLabel = scriptCountLabel,
                    characterCountLabel = characterCountLabel,
                )
            }

            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    placeholder = { Text(stringResource(R.string.home_search_hint)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        AnimatedVisibility(visible = query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.home_search_clear),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                )
            }

            if (loadError) {
                item(key = "load_error") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.home_load_error),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        searching = query.isNotBlank(),
                        onNewScript = onNewScript,
                    )
                }
            } else {
                items(items = filtered, key = { it.id }) { script ->
                    ScriptCard(
                        script = script,
                        onEdit = { onEditScript(script.id) },
                        onPlay = { onPlayScript(script.id) },
                        onDuplicate = {
                            scope.launch {
                                val copy = viewModel.duplicateScript(script)
                                notify(
                                    context.getString(
                                        if (copy != null) R.string.duplicate_done else R.string.error_generic,
                                    ),
                                )
                            }
                        },
                        onExport = {
                            exportTarget = script
                            exportLauncher.launch(exportFileName(script))
                        },
                        onDelete = { pendingDelete = script },
                    )
                }
            }
        }
    }

    pendingDelete?.let { script ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_body,
                        script.title.ifBlank { stringResource(R.string.script_untitled) },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val deleted = viewModel.deleteScript(script)
                            notify(
                                context.getString(
                                    if (deleted) R.string.delete_done else R.string.error_generic,
                                ),
                            )
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HeroPanel(scriptCountLabel: String, characterCountLabel: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        colors.primaryContainer,
                        colors.tertiaryContainer.copy(alpha = 0.72f),
                        colors.surface,
                    ),
                ),
            )
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = colors.surface.copy(alpha = 0.72f),
                    shape = CircleShape,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = colors.primary,
                        )
                        Text(
                            text = stringResource(R.string.home_local_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurface,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.home_stats, scriptCountLabel, characterCountLabel),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.home_hero_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScriptCard(
    script: Script,
    onEdit: () -> Unit,
    onPlay: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val excerpt = remember(script.content) {
        script.content.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    }
    val minutes = remember(script.content) { ReadingTime.estimateMinutes(script.content) }
    val title = script.title.ifBlank { stringResource(R.string.script_untitled) }
    val readingLabel = if (minutes > 0) {
        stringResource(R.string.script_read_time, minutes)
    } else {
        ""
    }
    val characterCount = ReadingTime.countCharacters(script.content)
    val charactersLabel = pluralStringResource(
        R.plurals.script_character_count,
        characterCount,
        characterCount,
    )
    val editedLabel = relativeTime(script.updatedAt)
    val metaLabel = listOf(readingLabel, charactersLabel, editedLabel)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

    Card(
        onClick = onEdit,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 10.dp, top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp),
                )
                FilledIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("play_$title"),
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.actions_play_description, title),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.actions_more),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.actions_edit)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.actions_duplicate)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.actions_export)) },
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.actions_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            if (excerpt.isNotEmpty()) {
                Text(
                    text = excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }

            Text(
                text = metaLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 10.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean, onNewScript: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (searching) Icons.Rounded.Search else Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(
            text = stringResource(
                if (searching) R.string.home_no_results_title else R.string.home_empty_title,
            ),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(
                if (searching) R.string.home_no_results_body else R.string.home_empty_body,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (!searching) {
            TextButton(onClick = onNewScript) {
                Text(stringResource(R.string.home_new_script))
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun relativeTime(timestamp: Long): String {
    val now = remember(timestamp) { System.currentTimeMillis() }
    val deltaMinutes = ((now - timestamp) / 60_000L).coerceAtLeast(0L)
    return when {
        deltaMinutes < 1 -> stringResource(R.string.script_time_now)
        deltaMinutes < 60 -> stringResource(R.string.script_time_minutes, deltaMinutes.toInt())
        deltaMinutes < 60 * 24 -> stringResource(R.string.script_time_hours, (deltaMinutes / 60).toInt())
        else -> stringResource(R.string.script_time_days, (deltaMinutes / (60 * 24)).toInt())
    }
}

private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "application/pdf",
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "text/html",
    "application/xhtml+xml",
    "application/rtf",
    "text/rtf",
    "application/vnd.oasis.opendocument.text",
    "application/epub+zip",
    "text/csv",
    "text/comma-separated-values",
    "text/*",
    "application/octet-stream",
)

private fun exportFileName(script: Script): String {
    val base = script.title
        .ifBlank { "CueFlow" }
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim()
        .take(60)
        .ifBlank { "CueFlow" }
    return "$base.txt"
}
