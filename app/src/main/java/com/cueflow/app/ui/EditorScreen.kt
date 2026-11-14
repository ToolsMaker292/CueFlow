package com.cueflow.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cueflow.app.MainViewModel
import com.cueflow.app.R
import com.cueflow.app.data.ScriptLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: MainViewModel,
    scriptId: String?,
    onBack: () -> Unit,
    notify: (String) -> Unit,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var openFailed by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableStateOf(false) }
    var hasUserEdited by remember(scriptId) { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }

    LaunchedEffect(scriptId) {
        if (!viewModel.openEditor(scriptId)) {
            openFailed = true
        }
    }

    LaunchedEffect(draft != null, scriptId) {
        if (draft != null && scriptId == null) {
            delay(280)
            runCatching { titleFocus.requestFocus() }
        }
    }

    // Debounced autosave: leaving the screen after a pause never loses work.
    LaunchedEffect(hasUserEdited, draft?.title, draft?.content) {
        val current = draft
        if (hasUserEdited &&
            current != null &&
            (current.title.isNotBlank() || current.content.isNotBlank())
        ) {
            delay(800)
            runCatching { viewModel.saveDraft() }
                .onSuccess { if (it) savedFlash = true }
        }
    }

    LaunchedEffect(savedFlash) {
        if (savedFlash) {
            delay(1_800)
            savedFlash = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                scope.launch { runCatching { viewModel.saveDraft() } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun persist(exitAfter: Boolean) {
        val data = draft ?: return
        if (data.title.isBlank() && data.content.isBlank()) {
            if (exitAfter) {
                viewModel.clearDraft()
                onBack()
            } else {
                notify(context.getString(R.string.editor_empty_hint))
            }
            return
        }
        scope.launch {
            val saved = runCatching { viewModel.saveDraft() }.getOrElse {
                notify(context.getString(R.string.error_generic))
                false
            }
            // State writes and navigation must always happen on the main thread, even when
            // this coroutine is resumed by a test dispatcher.
            withContext(Dispatchers.Main.immediate) {
                if (saved) {
                    savedFlash = true
                    if (exitAfter) {
                        viewModel.clearDraft()
                        onBack()
                    }
                } else {
                    notify(context.getString(R.string.error_generic))
                }
            }
        }
    }

    BackHandler {
        persist(exitAfter = true)
    }

    if (openFailed) {
        LaunchedEffect(Unit) {
            notify(context.getString(R.string.error_generic))
            onBack()
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (scriptId == null) R.string.editor_new_title else R.string.editor_edit_title,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { persist(exitAfter = true) },
                        modifier = Modifier.testTag("editor_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    AnimatedVisibility(visible = savedFlash) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.editor_saved),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { persist(exitAfter = false) }) {
                        Icon(
                            Icons.Rounded.Save,
                            contentDescription = stringResource(R.string.editor_save),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            EditorBottomBar(
                characters = draft?.content?.length ?: 0,
                minutes = viewModel.estimateMinutes(draft?.content.orEmpty()),
                onSave = { persist(exitAfter = false) },
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = draft?.title.orEmpty(),
                onValueChange = {
                    hasUserEdited = true
                    viewModel.updateDraftTitle(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .focusRequester(titleFocus)
                    .testTag("editor_title"),
                placeholder = {
                    Text(
                        stringResource(R.string.editor_title_hint),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    lineHeight = 32.sp,
                ),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )

            TextField(
                value = draft?.content.orEmpty(),
                onValueChange = {
                    hasUserEdited = true
                    viewModel.updateDraftContent(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("editor_content"),
                placeholder = {
                    Text(
                        text = stringResource(R.string.editor_content_hint),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 28.sp,
                    fontSize = 18.sp,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(0.dp),
            )
        }
    }
}

@Composable
private fun EditorBottomBar(
    characters: Int,
    minutes: Int,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nearLimit = characters >= ScriptLimits.MAX_CONTENT_LENGTH
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.editor_char_count,
                        characters,
                        ScriptLimits.MAX_CONTENT_LENGTH,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (nearLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = if (minutes > 0) {
                        stringResource(R.string.editor_reading_time, minutes)
                    } else {
                        stringResource(R.string.editor_empty_hint)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(16.dp),
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier.testTag("editor_save"),
            ) {
                Icon(
                    Icons.Rounded.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.editor_save))
            }
        }
    }
}
