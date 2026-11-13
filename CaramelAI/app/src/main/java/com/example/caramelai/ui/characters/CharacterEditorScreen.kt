package com.example.caramelai.ui.characters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.R
import com.example.caramelai.ui.components.Avatar
import com.example.caramelai.ui.components.CaramelButton
import com.example.caramelai.ui.components.CaramelButtonStyle
import com.example.caramelai.ui.components.CaramelTextField
import com.example.caramelai.ui.components.ErrorBanner
import com.example.caramelai.ui.components.FieldActionChip
import com.example.caramelai.ui.components.StepProgressBar
import com.example.caramelai.ui.theme.LocalCaramelColors

@Composable
fun CharacterEditorScreen(
    viewModel: CharacterEditorViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val colors = LocalCaramelColors.current
    val savedMessage = stringResource(R.string.editor_saved)
    val jsonCopiedMessage = stringResource(R.string.editor_json_copied)

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
                CharacterEditorViewModel.EditorEvent.Saved -> {
                    snackbar.showSnackbar(savedMessage)
                    onBack()
                }
                is CharacterEditorViewModel.EditorEvent.Exported -> {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("character.json", event.json))
                    snackbar.showSnackbar(jsonCopiedMessage)
                }
                is CharacterEditorViewModel.EditorEvent.Error ->
                    snackbar.showSnackbar(event.message)
            }
        }
    }

    val stepTitle = when (state.step) {
        0 -> stringResource(R.string.editor_step_identity)
        1 -> stringResource(R.string.editor_step_personality)
        else -> stringResource(R.string.editor_step_dialogue)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            StepProgressBar(
                stepCount = 3,
                currentStep = state.step,
                modifier = Modifier.padding(horizontal = 16.dp),
                onStepClick = viewModel::setStep,
            )
            Text(
                text = stepTitle,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp),
            )

            if (state.fieldError != null) {
                ErrorBanner(
                    message = state.fieldError!!,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state.step) {
                    0 -> {
                        Text(
                            stringResource(R.string.editor_image),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Avatar(
                                uri = state.avatarUri,
                                contentDescription = stringResource(R.string.cd_avatar),
                                size = 88.dp,
                            )
                            Column {
                                Text(
                                    stringResource(R.string.editor_upload_hint),
                                    color = colors.muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                CaramelButton(
                                    text = stringResource(R.string.editor_upload),
                                    onClick = { avatarLauncher.launch(arrayOf("image/*")) },
                                    style = CaramelButtonStyle.Secondary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        GenerateableField(
                            label = stringResource(R.string.editor_name),
                            value = state.name,
                            placeholder = stringResource(R.string.editor_name_placeholder),
                            singleLine = true,
                            generating = state.generatingField == CharacterField.Name,
                            onValueChange = viewModel::updateName,
                            onGenerate = { viewModel.generateField(CharacterField.Name) },
                        )
                    }

                    1 -> {
                        GenerateableField(
                            label = stringResource(R.string.editor_description),
                            value = state.description,
                            placeholder = stringResource(R.string.editor_description_placeholder),
                            minLines = 3,
                            generating = state.generatingField == CharacterField.Description,
                            onValueChange = viewModel::updateDescription,
                            onGenerate = { viewModel.generateField(CharacterField.Description) },
                        )
                        GenerateableField(
                            label = stringResource(R.string.editor_personality),
                            value = state.personality,
                            placeholder = stringResource(R.string.editor_personality_placeholder),
                            minLines = 3,
                            generating = state.generatingField == CharacterField.Personality,
                            onValueChange = viewModel::updatePersonality,
                            onGenerate = { viewModel.generateField(CharacterField.Personality) },
                        )
                        GenerateableField(
                            label = stringResource(R.string.editor_scenario),
                            value = state.scenario,
                            placeholder = stringResource(R.string.editor_scenario_placeholder),
                            minLines = 2,
                            generating = state.generatingField == CharacterField.Scenario,
                            onValueChange = viewModel::updateScenario,
                            onGenerate = { viewModel.generateField(CharacterField.Scenario) },
                        )
                    }

                    else -> {
                        GenerateableField(
                            label = stringResource(R.string.editor_greeting),
                            value = state.greeting,
                            placeholder = stringResource(R.string.editor_greeting_placeholder),
                            minLines = 3,
                            generating = state.generatingField == CharacterField.Greeting,
                            onValueChange = viewModel::updateGreeting,
                            onGenerate = { viewModel.generateField(CharacterField.Greeting) },
                        )
                        GenerateableField(
                            label = stringResource(R.string.editor_example),
                            value = state.exampleDialogue,
                            placeholder = stringResource(R.string.editor_example_placeholder),
                            minLines = 3,
                            generating = state.generatingField == CharacterField.ExampleDialogue,
                            onValueChange = viewModel::updateExampleDialogue,
                            onGenerate = { viewModel.generateField(CharacterField.ExampleDialogue) },
                        )
                        GenerateableField(
                            label = stringResource(R.string.editor_system_prompt),
                            value = state.systemPrompt,
                            placeholder = stringResource(R.string.editor_system_prompt_placeholder),
                            minLines = 2,
                            generating = state.generatingField == CharacterField.SystemPrompt,
                            onValueChange = viewModel::updateSystemPrompt,
                            onGenerate = { viewModel.generateField(CharacterField.SystemPrompt) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.step < 2) {
                    CaramelButton(
                        text = stringResource(R.string.action_continue),
                        onClick = viewModel::nextStep,
                        enabled = state.step > 0 || state.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CaramelButton(
                        text = stringResource(R.string.action_save),
                        onClick = viewModel::save,
                        enabled = state.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CaramelButton(
                        text = stringResource(R.string.editor_export_json),
                        onClick = viewModel::export,
                        style = CaramelButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.step > 0) {
                    CaramelButton(
                        text = stringResource(R.string.action_back),
                        onClick = viewModel::prevStep,
                        style = CaramelButtonStyle.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun GenerateableField(
    label: String,
    value: String,
    placeholder: String,
    generating: Boolean,
    onValueChange: (String) -> Unit,
    onGenerate: () -> Unit,
    minLines: Int = 1,
    singleLine: Boolean = false,
) {
    val generateLabel = stringResource(R.string.editor_generate)
    CaramelTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        minLines = minLines,
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        actions = {
            if (generating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                FieldActionChip(text = generateLabel, onClick = onGenerate)
            }
        },
    )
}
