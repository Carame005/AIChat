package com.example.caramelai.ui.startup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.R
import com.example.caramelai.ui.components.CaramelButton
import com.example.caramelai.ui.components.CaramelButtonStyle
import com.example.caramelai.ui.theme.LocalCaramelColors

@Composable
fun StartupScreen(
    viewModel: StartupViewModel,
    onConnected: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalCaramelColors.current

    LaunchedEffect(state) {
        if (state is StartupUiState.Connected) {
            onConnected()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            StartupUiState.Checking, StartupUiState.Connected -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.caramel_logo),
                        contentDescription = "CaramelAI",
                        modifier = Modifier.size(120.dp),
                    )
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.startup_connecting),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CircularProgressIndicator(color = colors.accentStart)
                }
            }

            is StartupUiState.Failed -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Image(
                        painter = painterResource(R.drawable.connection_error),
                        contentDescription = null,
                        modifier = Modifier.size(140.dp),
                    )
                    Text(
                        stringResource(R.string.startup_failed_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        current.message,
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    CaramelButton(
                        text = stringResource(R.string.action_retry),
                        onClick = viewModel::check,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CaramelButton(
                        text = stringResource(R.string.startup_configure),
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                        style = CaramelButtonStyle.Secondary,
                    )
                    CaramelButton(
                        text = stringResource(R.string.startup_continue_offline),
                        onClick = onContinueOffline,
                        modifier = Modifier.fillMaxWidth(),
                        style = CaramelButtonStyle.Ghost,
                    )
                }
            }
        }
    }
}
