package com.example.caramelai.ui.settings

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
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsViewModel.SettingsEvent.ConnectionOk ->
                    snackbar.showSnackbar("Conectado: ${event.detail.take(80)}")
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
            CaramelTopBar(title = "Ajustes", onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Conexión", style = MaterialTheme.typography.titleMedium)
                CaramelCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CaramelTextField(
                            value = text.baseUrl,
                            onValueChange = viewModel::updateBaseUrl,
                            label = "Base URL",
                            placeholder = "http://192.168.x.x:8081",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "IP Wi‑Fi del PC + puerto del llama-server",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CaramelTextField(
                            value = text.apiKey,
                            onValueChange = viewModel::updateApiKey,
                            label = "API key",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CaramelTextField(
                            value = text.userName,
                            onValueChange = viewModel::updateUserName,
                            label = "Nombre de respaldo ({{user}})",
                            placeholder = "Si no hay persona activa",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CaramelButton(
                                text = "Probar conexión",
                                onClick = viewModel::testConnection,
                                enabled = !testing,
                            )
                            if (testing) {
                                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            }
                        }
                    }
                }

                Text("Comportamiento global", style = MaterialTheme.typography.titleMedium)
                CaramelCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Instrucciones que se aplican a todos los chats. Úsalo para orientar el tono general (romántico, NSFW, SFW, más corto, etc.).",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CaramelTextField(
                            value = text.globalBehavior,
                            onValueChange = viewModel::updateGlobalBehavior,
                            placeholder = "Ej.: Inclina la historia hacia lo romántico y acepta contenido NSFW si el usuario lo inicia. Mantén el personaje coherente.",
                            minLines = 4,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Text("Generación", style = MaterialTheme.typography.titleMedium)
                DescribedSlider(
                    label = "Temperature",
                    description = "Controla la creatividad. Más alto = respuestas más creativas e impredecibles; más bajo = más coherentes y repetitivas.",
                    valueLabel = "%.2f".format(settings.temperature),
                    value = settings.temperature,
                    range = 0f..2f,
                    onChange = { v -> viewModel.update { it.copy(temperature = v) } },
                )
                DescribedSlider(
                    label = "Top P",
                    description = "Núcleo de probabilidad. Valores altos permiten más variedad de palabras; valores bajos se centran en las opciones más probables.",
                    valueLabel = "%.2f".format(settings.topP),
                    value = settings.topP,
                    range = 0f..1f,
                    onChange = { v -> viewModel.update { it.copy(topP = v) } },
                )
                DescribedIntSlider(
                    label = "Top K",
                    description = "Limita el muestreo a las K palabras más probables. 0 o valores altos = más diversidad; valores bajos = más predecible.",
                    value = settings.topK,
                    range = 0f..100f,
                    onChange = { v -> viewModel.update { it.copy(topK = v) } },
                )
                DescribedSlider(
                    label = "Min P",
                    description = "Umbral mínimo relativo. Filtra tokens poco probables. Más alto = respuestas más seguras y menos raras.",
                    valueLabel = "%.2f".format(settings.minP),
                    value = settings.minP,
                    range = 0f..1f,
                    onChange = { v -> viewModel.update { it.copy(minP = v) } },
                )
                DescribedSlider(
                    label = "Repeat penalty",
                    description = "Penaliza repetir lo mismo. Más alto = menos bucles y repeticiones; demasiado alto puede sonar forzado.",
                    valueLabel = "%.2f".format(settings.repeatPenalty),
                    value = settings.repeatPenalty,
                    range = 1f..1.5f,
                    onChange = { v -> viewModel.update { it.copy(repeatPenalty = v) } },
                )
                DescribedIntSlider(
                    label = "Max tokens (por respuesta)",
                    description = "Tope de tokens que el bot puede generar en UN mensaje. No es el tamaño del chat completo. Sube esto si quieres respuestas más largas (hasta 4096). En los logs del servidor esto es n_gen / eval tokens, no n_tokens.",
                    value = settings.maxTokens,
                    range = 64f..4096f,
                    onChange = { v -> viewModel.update { it.copy(maxTokens = v) } },
                )
                DescribedIntSlider(
                    label = "Context size (memoria del chat)",
                    description = "Tokens totales del prompt + historial + respuesta que caben en el slot. Debe coincidir con llama-server -c. En los logs es n_tokens (p. ej. 517 o 1389). Más alto = más historial, más VRAM.",
                    value = settings.contextSize,
                    range = 2048f..32768f,
                    onChange = { v -> viewModel.update { it.copy(contextSize = v) } },
                )
                CaramelButton(
                    text = "Restablecer valores de generación",
                    onClick = viewModel::resetGenerationDefaults,
                    style = CaramelButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
