package com.example.caramelai.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.caramelai.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.caramelai.data.db.PersonaEntity
import com.example.caramelai.ui.components.Avatar
import com.example.caramelai.ui.components.CaramelCard
import com.example.caramelai.ui.components.CaramelIconButton
import com.example.caramelai.ui.components.CaramelTopBar
import com.example.caramelai.ui.theme.AppTheme
import com.example.caramelai.ui.theme.LocalCaramelColors

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onOpenPersonaEditor: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = LocalCaramelColors.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileViewModel.ProfileEvent.OpenPersonaEditor ->
                    onOpenPersonaEditor(event.personaId)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        CaramelTopBar(
            title = stringResource(R.string.profile_title),
            actions = {
                CaramelIconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.profile_personas), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.profile_personas_hint),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(personas, key = { it.id }) { persona ->
                PersonaRow(
                    persona = persona,
                    isDefault = settings.defaultPersonaId == persona.id,
                    onClick = { onOpenPersonaEditor(persona.id) },
                    onSetDefault = { viewModel.setDefaultPersona(persona.id) },
                )
            }
            item {
                CaramelCard(onClick = viewModel::createPersona) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = colors.accentStart)
                        Text(
                            stringResource(R.string.profile_new_persona),
                            modifier = Modifier.padding(start = 12.dp),
                            color = colors.accentStart,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.profile_theme),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppTheme.entries.forEach { theme ->
                        val selected = settings.theme == theme
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
                                .clickable { viewModel.setTheme(theme) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                theme.name,
                                color = if (selected) Color.White else colors.muted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            item {
                CaramelCard(onClick = onOpenSettings) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Text(
                            stringResource(R.string.profile_connection_generation),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        )
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaRow(
    persona: PersonaEntity,
    isDefault: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val colors = LocalCaramelColors.current
    val defaultBadge = stringResource(R.string.profile_default_badge)
    CaramelCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(uri = persona.avatarUri, contentDescription = persona.name, size = 48.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(persona.name, style = MaterialTheme.typography.titleMedium)
                    if (isDefault) {
                        Text(
                            "  $defaultBadge",
                            color = colors.accentStart,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (persona.description.isNotBlank()) {
                    Text(
                        persona.description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!isDefault) {
                CaramelIconButton(onClick = onSetDefault) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_make_default),
                        tint = colors.muted,
                    )
                }
            }
        }
    }
}
