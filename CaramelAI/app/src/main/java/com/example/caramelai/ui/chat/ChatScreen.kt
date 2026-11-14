package com.example.caramelai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.R
import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.MessageEntity
import com.example.caramelai.data.db.MessageRole
import com.example.caramelai.data.db.MessageVariants
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.ui.components.Avatar
import com.example.caramelai.ui.components.TypingDots
import com.example.caramelai.ui.theme.LocalCaramelColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
    onEditCharacter: () -> Unit,
) {
    val character by viewModel.character.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activePersona by viewModel.activePersona.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val colors = LocalCaramelColors.current
    var menuMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var showCharacterMenu by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showPersonaPicker by remember { mutableStateOf(false) }
    val characterSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val historySheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val personaSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    val specsCopiedMessage = stringResource(R.string.chat_specs_copied)

    LaunchedEffect(messages.size, uiState.streamingText, uiState.isGenerating) {
        val extra = when {
            uiState.streamingText.isNotEmpty() -> 1
            uiState.isGenerating -> 1
            else -> 0
        }
        val total = messages.size + extra
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatViewModel.ChatEvent.Error -> snackbar.showSnackbar(event.message)
                is ChatViewModel.ChatEvent.OpenConversation ->
                    onOpenConversation(event.conversationId)
                is ChatViewModel.ChatEvent.Exported -> {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("character.json", event.json),
                    )
                    snackbar.showSnackbar(specsCopiedMessage)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showCharacterMenu = true }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(
                        uri = character?.avatarUri,
                        contentDescription = character?.name,
                        size = 36.dp,
                        rounded = false,
                    )
                    Text(
                        text = character?.name ?: stringResource(R.string.chat_fallback_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }

            val visibleMessages = remember(messages, uiState.regeneratingMessageId, uiState.isGenerating) {
                if (uiState.isGenerating && uiState.regeneratingMessageId != null) {
                    messages.filter { it.id != uiState.regeneratingMessageId }
                } else {
                    messages
                }
            }
            val lastVisibleId = visibleMessages.lastOrNull()?.id

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visibleMessages, key = { it.id }) { message ->
                    val variants = MessageVariants.listOf(message)
                    val showVariantNav = !uiState.isGenerating &&
                        message.id == lastVisibleId &&
                        message.role == MessageRole.ASSISTANT &&
                        variants.size > 1
                    MessageRow(
                        message = message,
                        botAvatar = character?.avatarUri,
                        userAvatar = activePersona?.avatarUri,
                        onLongClick = { menuMessage = message },
                        variantIndex = if (showVariantNav) {
                            message.activeVariantIndex.coerceIn(0, variants.lastIndex)
                        } else {
                            null
                        },
                        variantCount = if (showVariantNav) variants.size else null,
                        onPreviousVariant = {
                            viewModel.selectPreviousVariant(message.id)
                        },
                        onNextVariant = {
                            viewModel.selectNextVariant(message.id)
                        },
                    )
                }
                if (uiState.streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        MessageRow(
                            message = MessageEntity(
                                id = "streaming",
                                conversationId = "",
                                role = MessageRole.ASSISTANT,
                                content = uiState.streamingText,
                            ),
                            botAvatar = character?.avatarUri,
                            userAvatar = activePersona?.avatarUri,
                            onLongClick = {},
                        )
                    }
                } else if (uiState.isGenerating) {
                    item(key = "typing") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Avatar(
                                uri = character?.avatarUri,
                                contentDescription = null,
                                size = 28.dp,
                                rounded = false,
                            )
                            TypingDots()
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = { showPersonaPicker = true },
                            onLongClick = { showPersonaPicker = true },
                        ),
                ) {
                    Avatar(
                        uri = activePersona?.avatarUri,
                        contentDescription = stringResource(R.string.persona_editor_title),
                        size = 36.dp,
                        rounded = false,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.field)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    BasicTextField(
                        value = uiState.draft,
                        onValueChange = viewModel::updateDraft,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        enabled = !uiState.isGenerating,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(colors.accentStart),
                        decorationBox = { inner ->
                            Box {
                                if (uiState.draft.isEmpty()) {
                                    Text("Message…", color = colors.muted)
                                }
                                inner()
                            }
                        },
                    )
                }
                if (uiState.isGenerating) {
                    IconButton(onClick = viewModel::stop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                } else {
                    val empty = uiState.draft.isBlank()
                    IconButton(onClick = viewModel::sendOrContinue) {
                        Icon(
                            painter = painterResource(
                                if (empty) R.drawable.ic_continue else R.drawable.ic_send,
                            ),
                            contentDescription = if (empty) {
                                stringResource(R.string.cd_continue)
                            } else {
                                stringResource(R.string.cd_send)
                            },
                            tint = colors.accentStart,
                        )
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showCharacterMenu) {
        ModalBottomSheet(
            onDismissRequest = { showCharacterMenu = false },
            sheetState = characterSheet,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            CharacterMenuSheet(
                character = character,
                onClose = { showCharacterMenu = false },
                onExport = {
                    viewModel.exportCharacter()
                },
                onViewProfile = {
                    showCharacterMenu = false
                    onEditCharacter()
                },
                onHistory = {
                    showCharacterMenu = false
                    showHistory = true
                },
                onNewChat = {
                    showCharacterMenu = false
                    viewModel.createNewChat()
                },
            )
        }
    }

    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = historySheet,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.chat_history_title), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = {
                    showHistory = false
                    viewModel.createNewChat()
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  ${stringResource(R.string.chat_new_chat)}")
                }
                conversations.forEach { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.card)
                            .combinedClickable(
                                onClick = {
                                    showHistory = false
                                    viewModel.openConversation(conversation.id)
                                },
                                onLongClick = { viewModel.deleteConversation(conversation.id) },
                            )
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                dateFormat.format(Date(conversation.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPersonaPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPersonaPicker = false },
            sheetState = personaSheet,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.chat_pick_persona), style = MaterialTheme.typography.titleLarge)
                personas.forEach { persona ->
                    PersonaPickRow(
                        persona = persona,
                        selected = activePersona?.id == persona.id,
                        onClick = {
                            viewModel.setPersona(persona.id)
                            showPersonaPicker = false
                        },
                    )
                }
                if (personas.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_pick_persona_empty),
                        color = colors.muted,
                    )
                }
            }
        }
    }

    menuMessage?.let { message ->
        val isLastAssistant = messages.lastOrNull()?.id == message.id &&
            message.role == MessageRole.ASSISTANT
        ModalBottomSheet(onDismissRequest = { menuMessage = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
                    menuMessage = null
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text("  Copy")
                }
                TextButton(onClick = {
                    viewModel.beginEdit(message)
                    menuMessage = null
                }) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text("  ${stringResource(R.string.action_edit)}")
                }
                TextButton(onClick = {
                    viewModel.deleteMessage(message.id)
                    menuMessage = null
                }) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text("  ${stringResource(R.string.action_delete)}")
                }
                if (isLastAssistant) {
                    TextButton(onClick = {
                        viewModel.beginRevisePrompt()
                        menuMessage = null
                    }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Text("  ${stringResource(R.string.chat_revise_prompt)}")
                    }
                    TextButton(onClick = {
                        viewModel.regenerate()
                        menuMessage = null
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_regenerate),
                            contentDescription = null,
                        )
                        Text("  ${stringResource(R.string.chat_regenerate)}")
                    }
                }
                TextButton(onClick = {
                    viewModel.branchFrom(message.id)
                    menuMessage = null
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_branch),
                        contentDescription = null,
                    )
                    Text("  New chat from here")
                }
            }
        }
    }

    if (uiState.editingMessageId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelEdit,
            title = { Text(stringResource(R.string.chat_edit_message)) },
            text = {
                OutlinedTextField(
                    value = uiState.editingText,
                    onValueChange = viewModel::updateEditingText,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::commitEdit) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelEdit) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (uiState.showRevisePrompt) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRevisePrompt,
            title = { Text(stringResource(R.string.chat_revise_prompt_title)) },
            text = {
                OutlinedTextField(
                    value = uiState.revisePromptDraft,
                    onValueChange = viewModel::updateRevisePromptDraft,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = {
                        Text(stringResource(R.string.chat_revise_prompt_hint))
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::commitRevisePrompt,
                    enabled = uiState.revisePromptDraft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.chat_revise_prompt_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRevisePrompt) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CharacterMenuSheet(
    character: CharacterEntity?,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onViewProfile: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
) {
    val colors = LocalCaramelColors.current
    val description = character?.description?.takeIf { it.isNotBlank() }
        ?: character?.personality?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_no_description)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.muted)
            }
        }

        Avatar(
            uri = character?.avatarUri,
            contentDescription = character?.name,
            size = 112.dp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = character?.name ?: stringResource(R.string.chat_character_fallback),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        IconButton(
            onClick = onExport,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.field),
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(R.string.cd_export_specs),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.chat_save_for_import),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        )

        CharacterMenuItem(
            icon = Icons.Default.Person,
            label = stringResource(R.string.chat_view_character),
            onClick = onViewProfile,
        )
        Spacer(Modifier.height(10.dp))
        CharacterMenuItem(
            icon = Icons.Default.History,
            label = stringResource(R.string.chat_history),
            onClick = onHistory,
        )
        Spacer(Modifier.height(10.dp))
        CharacterMenuItem(
            icon = Icons.Default.Add,
            label = stringResource(R.string.chat_new_chat),
            onClick = onNewChat,
            accent = true,
        )
    }
}

@Composable
private fun CharacterMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    val colors = LocalCaramelColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (accent) colors.accentStart else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (accent) colors.accentStart else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonaPickRow(
    persona: PersonaEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalCaramelColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.field else colors.card)
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(uri = persona.avatarUri, contentDescription = persona.name, size = 40.dp)
        Text(persona.name, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: MessageEntity,
    botAvatar: String?,
    userAvatar: String?,
    onLongClick: () -> Unit,
    variantIndex: Int? = null,
    variantCount: Int? = null,
    onPreviousVariant: (() -> Unit)? = null,
    onNextVariant: (() -> Unit)? = null,
) {
    val isUser = message.role == MessageRole.USER
    val colors = LocalCaramelColors.current
    val roleplayColors = RoleplayColors(
        dialogue = colors.dialogue,
        action = colors.action,
        bold = MaterialTheme.colorScheme.onSurface,
        normal = MaterialTheme.colorScheme.onSurface,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!isUser) {
                Avatar(
                    uri = botAvatar,
                    contentDescription = null,
                    size = 28.dp,
                    rounded = false,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isUser) colors.bubbleUser else colors.bubbleBot)
                    .combinedClickable(onClick = {}, onLongClick = onLongClick)
                    .padding(12.dp),
            ) {
                Text(
                    text = RoleplayFormatter.format(message.content, roleplayColors),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (isUser) {
                Avatar(
                    uri = userAvatar,
                    contentDescription = null,
                    size = 28.dp,
                    rounded = false,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (variantIndex != null && variantCount != null && variantCount > 1) {
            Row(
                modifier = Modifier
                    .padding(start = 36.dp, top = 4.dp)
                    .fillMaxWidth(0.78f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = { onPreviousVariant?.invoke() },
                    enabled = variantIndex > 0,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.cd_previous_variant),
                        tint = if (variantIndex > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            colors.muted
                        },
                    )
                }
                Text(
                    text = stringResource(
                        R.string.chat_variant_counter,
                        variantIndex + 1,
                        variantCount,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.muted,
                )
                IconButton(
                    onClick = { onNextVariant?.invoke() },
                    enabled = variantIndex < variantCount - 1,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.cd_next_variant),
                        tint = if (variantIndex < variantCount - 1) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            colors.muted
                        },
                    )
                }
            }
        }
    }
}
