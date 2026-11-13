package com.example.caramelai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.R
import com.example.caramelai.data.settings.AppLanguage
import com.example.caramelai.ui.components.CaramelButton
import com.example.caramelai.ui.components.CaramelButtonStyle
import com.example.caramelai.ui.components.CaramelCard
import com.example.caramelai.ui.components.CaramelTextField
import com.example.caramelai.ui.components.CaramelTopBar
import com.example.caramelai.ui.components.DescribedIntSlider
import com.example.caramelai.ui.components.DescribedSlider
import com.example.caramelai.ui.theme.LocalCaramelColors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val text by viewModel.text.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val colors = LocalCaramelColors.current
    val connectedFmt = stringResource(R.string.settings_connected)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsViewModel.SettingsEvent.ConnectionOk ->
                    snackbar.showSnackbar(connectedFmt.format(event.detail.take(80)))
                is SettingsViewModel.SettingsEvent.ConnectionFailed ->
                    snackbar.showSnackbar(event.message)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CaramelTopBar(title = stringResource(R.string.settings_title), onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.settings_connection), style = MaterialTheme.typography.titleMedium)
                CaramelCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CaramelTextField(
                            value = text.baseUrl,
                            onValueChange = viewModel::updateBaseUrl,
                            label = stringResource(R.string.settings_base_url),
                            placeholder = "http://192.168.x.x:8081",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.settings_base_url_hint),
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CaramelTextField(
                            value = text.apiKey,
                            onValueChange = viewModel::updateApiKey,
                            label = stringResource(R.string.settings_api_key),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CaramelTextField(
                            value = text.userName,
                            onValueChange = viewModel::updateUserName,
                            label = stringResource(R.string.settings_fallback_name),
                            placeholder = stringResource(R.string.settings_fallback_name_placeholder),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CaramelButton(
                                text = stringResource(R.string.settings_test_connection),
                                onClick = viewModel::testConnection,
                                enabled = !testing,
                            )
                            if (testing) {
                                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            }
                        }
                    }
                }

                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                CaramelCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.entries.forEach { language ->
                            val selected = settings.language == language
                            val label = when (language) {
                                AppLanguage.English -> stringResource(R.string.settings_language_en)
                                AppLanguage.Spanish -> stringResource(R.string.settings_language_es)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .then(
                                        if (selected) {
                                            Modifier.background(colors.accentBrush)
                                        } else {
                                            Modifier.background(colors.field)
                                        },
                                    )
                                    .clickable { viewModel.setLanguage(language) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Color.White else colors.muted,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }

                Text(stringResource(R.string.settings_model), style = MaterialTheme.typography.titleMedium)
                ModelPickerSection(viewModel = viewModel, selectedModelId = settings.modelId)

                Text(stringResource(R.string.settings_global_behavior), style = MaterialTheme.typography.titleMedium)
                CaramelCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.settings_global_behavior_hint),
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CaramelTextField(
                            value = text.globalBehavior,
                            onValueChange = viewModel::updateGlobalBehavior,
                            placeholder = stringResource(R.string.settings_global_behavior_placeholder),
                            minLines = 4,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Text(stringResource(R.string.settings_generation), style = MaterialTheme.typography.titleMedium)
                DescribedSlider(
                    label = stringResource(R.string.settings_temperature),
                    description = stringResource(R.string.settings_temperature_desc),
                    valueLabel = "%.2f".format(settings.temperature),
                    value = settings.temperature,
                    range = 0f..2f,
                    onChange = { v -> viewModel.update { it.copy(temperature = v) } },
                )
                DescribedSlider(
                    label = stringResource(R.string.settings_top_p),
                    description = stringResource(R.string.settings_top_p_desc),
                    valueLabel = "%.2f".format(settings.topP),
                    value = settings.topP,
                    range = 0f..1f,
                    onChange = { v -> viewModel.update { it.copy(topP = v) } },
                )
                DescribedIntSlider(
                    label = stringResource(R.string.settings_top_k),
                    description = stringResource(R.string.settings_top_k_desc),
                    value = settings.topK,
                    range = 0f..100f,
                    onChange = { v -> viewModel.update { it.copy(topK = v) } },
                )
                DescribedSlider(
                    label = stringResource(R.string.settings_min_p),
                    description = stringResource(R.string.settings_min_p_desc),
                    valueLabel = "%.2f".format(settings.minP),
                    value = settings.minP,
                    range = 0f..1f,
                    onChange = { v -> viewModel.update { it.copy(minP = v) } },
                )
                DescribedSlider(
                    label = stringResource(R.string.settings_repeat_penalty),
                    description = stringResource(R.string.settings_repeat_penalty_desc),
                    valueLabel = "%.2f".format(settings.repeatPenalty),
                    value = settings.repeatPenalty,
                    range = 1f..1.5f,
                    onChange = { v -> viewModel.update { it.copy(repeatPenalty = v) } },
                )
                DescribedIntSlider(
                    label = stringResource(R.string.settings_max_tokens),
                    description = stringResource(R.string.settings_max_tokens_desc),
                    value = settings.maxTokens,
                    range = 64f..4096f,
                    onChange = { v -> viewModel.update { it.copy(maxTokens = v) } },
                )
                DescribedIntSlider(
                    label = stringResource(R.string.settings_context_size),
                    description = stringResource(R.string.settings_context_size_desc),
                    value = settings.contextSize,
                    range = 2048f..32768f,
                    onChange = { v -> viewModel.update { it.copy(contextSize = v) } },
                )
                CaramelButton(
                    text = stringResource(R.string.settings_reset_generation),
                    onClick = viewModel::resetGenerationDefaults,
                    style = CaramelButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun knownModelName(modelId: String): String = when (modelId) {
    "Chaos-Unknown-12b.Q4_K_M.gguf" -> stringResource(R.string.settings_model_q4_name)
    "Chaos-Unknown-12b.Q5_K_M.gguf" -> stringResource(R.string.settings_model_q5_name)
    else -> modelId
}

@Composable
private fun knownModelDescription(modelId: String): String = when (modelId) {
    "Chaos-Unknown-12b.Q4_K_M.gguf" -> stringResource(R.string.settings_model_q4_desc)
    "Chaos-Unknown-12b.Q5_K_M.gguf" -> stringResource(R.string.settings_model_q5_desc)
    else -> ""
}

@Composable
private fun ModelPickerSection(
    viewModel: SettingsViewModel,
    selectedModelId: String,
) {
    val options by viewModel.modelOptions.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshingModels.collectAsStateWithLifecycle()
    val colors = LocalCaramelColors.current

    CaramelCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.settings_model_hint),
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            options.forEach { option ->
                val selected = option.model.id == selectedModelId
                val enabled = option.availableOnServer
                val modelName = knownModelName(option.model.id)
                val modelDesc = knownModelDescription(option.model.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = when {
                                selected -> colors.accentStart
                                else -> colors.field
                            },
                            shape = MaterialTheme.shapes.medium,
                        )
                        .clickable(enabled = enabled) {
                            viewModel.selectModel(option.model.id)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .border(
                                width = 2.dp,
                                color = if (selected) colors.accentStart else colors.muted,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(colors.accentStart),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            modelName,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                colors.muted
                            },
                        )
                        if (modelDesc.isNotBlank()) {
                            Text(
                                modelDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                        Text(
                            stringResource(
                                if (enabled) {
                                    R.string.settings_model_available
                                } else {
                                    R.string.settings_model_unavailable
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enabled) colors.success else colors.danger,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CaramelButton(
                    text = stringResource(R.string.settings_refresh_models),
                    onClick = viewModel::refreshAvailableModels,
                    enabled = !refreshing,
                    style = CaramelButtonStyle.Secondary,
                )
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}
