package com.example.caramelai.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.caramelai.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.ui.components.Avatar
import com.example.caramelai.ui.components.CaramelButton
import com.example.caramelai.ui.components.CaramelTextField
import com.example.caramelai.ui.components.CaramelTopBar
import com.example.caramelai.ui.components.ChipButton
import com.example.caramelai.ui.theme.LocalCaramelColors

@Composable
fun PersonaEditorScreen(
    viewModel: PersonaEditorViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val colors = LocalCaramelColors.current
    val genderPrefix = stringResource(R.string.persona_chip_gender_prefix)
    val appearancePrefix = stringResource(R.string.persona_chip_appearance_prefix)
    val speciesPrefix = stringResource(R.string.persona_chip_species_prefix)

    val avatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.updateAvatarUri(uri.toString())
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PersonaEditorViewModel.EditorEvent.Saved,
                PersonaEditorViewModel.EditorEvent.Deleted,
                -> onBack()
                is PersonaEditorViewModel.EditorEvent.Error ->
                    snackbar.showSnackbar(event.message)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CaramelTopBar(
                title = stringResource(R.string.persona_editor_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::delete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Info, contentDescription = "Info", tint = colors.muted)
                    }
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Avatar(
                        uri = state.avatarUri,
                        contentDescription = stringResource(R.string.cd_avatar),
                        size = 112.dp,
                    )
                    IconButton(
                        onClick = { avatarLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_change_photo))
                    }
                }

                CaramelTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    label = stringResource(R.string.editor_name),
                    placeholder = stringResource(R.string.persona_name_placeholder),
                    singleLine = true,
                    maxLength = 20,
                    modifier = Modifier.fillMaxWidth(),
                )

                CaramelTextField(
                    value = state.description,
                    onValueChange = viewModel::updateDescription,
                    label = stringResource(R.string.editor_description),
                    placeholder = stringResource(R.string.persona_description_placeholder),
                    minLines = 5,
                    maxLength = 750,
                    modifier = Modifier.fillMaxWidth(),
                    actions = {
                        ChipButton(
                            stringResource(R.string.persona_chip_gender),
                            onClick = { viewModel.appendChip(genderPrefix) },
                        )
                        ChipButton(
                            stringResource(R.string.persona_chip_appearance),
                            onClick = { viewModel.appendChip(appearancePrefix) },
                        )
                        ChipButton(
                            stringResource(R.string.persona_chip_species),
                            onClick = { viewModel.appendChip(speciesPrefix) },
                        )
                    },
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = state.isDefault,
                        onCheckedChange = viewModel::updateDefault,
                    )
                    Text(
                        stringResource(R.string.persona_default_for_chats),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            CaramelButton(
                text = stringResource(R.string.action_save),
                onClick = viewModel::save,
                enabled = state.name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
