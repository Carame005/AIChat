package com.example.caramelai.ui.characters

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.data.db.CharacterWithActivity
import com.example.caramelai.data.settings.BotListLayout
import com.example.caramelai.ui.components.Avatar
import com.example.caramelai.ui.components.CaramelIconButton
import com.example.caramelai.ui.components.CaramelTopBar
import com.example.caramelai.ui.theme.LocalCaramelColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharacterListScreen(
    viewModel: CharacterListViewModel,
    onOpenCharacter: (characterId: String, conversationId: String) -> Unit,
    onEditCharacter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<CharacterWithActivity?>(null) }
    val context = LocalContext.current
    val colors = LocalCaramelColors.current
    val layout = settings.botListLayout

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read file")
        }.onSuccess { viewModel.importJson(it) }
            .onFailure { viewModel.importJson("") }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CharacterListViewModel.ListEvent.OpenEditor -> onEditCharacter(event.characterId)
                is CharacterListViewModel.ListEvent.OpenChat ->
                    onOpenCharacter(event.characterId, event.conversationId)
                is CharacterListViewModel.ListEvent.Imported ->
                    snackbar.showSnackbar("Imported ${event.name}")
                is CharacterListViewModel.ListEvent.Error ->
                    snackbar.showSnackbar(event.message)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CaramelTopBar(
                title = "CaramelAI",
                actions = {
                    CaramelIconButton(
                        onClick = {
                            viewModel.setLayout(
                                if (layout == BotListLayout.GRID) BotListLayout.LIST
                                else BotListLayout.GRID,
                            )
                        },
                    ) {
                        Icon(
                            if (layout == BotListLayout.GRID) Icons.Default.ViewList
                            else Icons.Default.GridView,
                            contentDescription = "Cambiar vista",
                        )
                    }
                    CaramelIconButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Importar")
                    }
                },
            )

            if (characters.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Crea un personaje para empezar a chatear",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.muted,
                    )
                }
            } else if (layout == BotListLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(characters, key = { it.id }) { character ->
                        BotGridCard(
                            character = character,
                            onClick = { viewModel.openLatestChat(character.id) },
                            onLongClick = { pendingDelete = character },
                            onEdit = { onEditCharacter(character.id) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(characters, key = { it.id }) { character ->
                        BotListRow(
                            character = character,
                            onClick = { viewModel.openLatestChat(character.id) },
                            onLongClick = { pendingDelete = character },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = viewModel::createCharacter,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = colors.accentStart,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Nuevo personaje", tint = MaterialTheme.colorScheme.onPrimary)
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    pendingDelete?.let { character ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("¿Borrar ${character.name}?") },
            text = { Text("Se eliminará el personaje y todos sus chats.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCharacter(character.id)
                    pendingDelete = null
                }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotGridCard(
    character: CharacterWithActivity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = LocalCaramelColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Avatar(
            uri = character.avatarUri,
            contentDescription = character.name,
            size = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            text = character.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (character.description.isNotBlank()) {
            Text(
                text = character.description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "Editar",
            color = colors.accentStart,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable(onClick = onEdit),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotListRow(
    character: CharacterWithActivity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LocalCaramelColors.current.card)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            uri = character.avatarUri,
            contentDescription = character.name,
            size = 52.dp,
        )
        Text(
            text = character.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
