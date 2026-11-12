package com.example.caramelai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.caramelai.R
import com.example.caramelai.ui.theme.LocalCaramelColors
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(18.dp)
private val FieldShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(999.dp)

@Composable
fun CaramelCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalCaramelColors.current
    Box(
        modifier = modifier
            .clip(CardShape)
            .background(colors.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) {
        content()
    }
}

enum class CaramelButtonStyle {
    Primary,
    Secondary,
    Ghost,
}

@Composable
fun CaramelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: CaramelButtonStyle = CaramelButtonStyle.Primary,
) {
    val colors = LocalCaramelColors.current
    val shape = RoundedCornerShape(28.dp)
    val background = when {
        !enabled -> Modifier.background(colors.field, shape)
        style == CaramelButtonStyle.Primary -> Modifier.background(colors.accentBrush, shape)
        style == CaramelButtonStyle.Secondary -> Modifier.background(colors.field, shape)
        else -> Modifier
    }
    val textColor = when {
        !enabled -> colors.muted
        style == CaramelButtonStyle.Ghost -> MaterialTheme.colorScheme.primary
        style == CaramelButtonStyle.Primary -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .then(background)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun CaramelIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        content()
    }
}

@Composable
fun CaramelTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun Avatar(
    uri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp? = 40.dp,
    rounded: Boolean = true,
) {
    val shape = if (rounded) RoundedCornerShape(12.dp) else CircleShape
    val base = modifier
        .then(if (size != null) Modifier.size(size) else Modifier)
        .clip(shape)
        .background(LocalCaramelColors.current.field)
    if (!uri.isNullOrBlank()) {
        AsyncImage(
            model = uri,
            contentDescription = contentDescription,
            modifier = base,
            contentScale = ContentScale.Crop,
        )
    } else {
        Image(
            painter = painterResource(R.drawable.default_avatar),
            contentDescription = contentDescription,
            modifier = base,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
fun CaramelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    minLines: Int = 1,
    maxLines: Int = 8,
    singleLine: Boolean = false,
    maxLength: Int? = null,
    enabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalCaramelColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(FieldShape)
                .background(colors.field)
                .padding(14.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    onValueChange(
                        if (maxLength != null) next.take(maxLength) else next,
                    )
                },
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = if (singleLine) 1 else maxLines,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
                cursorBrush = SolidColor(colors.accentStart),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                color = colors.muted,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                actions()
                if (maxLength != null) {
                    Text(
                        "${value.length} / $maxLength",
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
fun ChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCaramelColors.current
    Text(
        text = text,
        color = colors.accentStart,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(PillShape)
            .background(colors.field)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
fun StepProgressBar(
    stepCount: Int,
    currentStep: Int,
    modifier: Modifier = Modifier,
    onStepClick: ((Int) -> Unit)? = null,
) {
    val colors = LocalCaramelColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(stepCount) { index ->
            val active = index <= currentStep
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(PillShape)
                    .background(
                        if (active) {
                            Brush.horizontalGradient(listOf(colors.accentStart, colors.accentEnd))
                        } else {
                            Brush.horizontalGradient(listOf(colors.field, colors.field))
                        },
                    )
                    .then(
                        if (onStepClick != null) {
                            Modifier.clickable { onStepClick(index) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

@Composable
fun DescribedSlider(
    label: String,
    description: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCaramelColors.current
    CaramelCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(valueLabel, color = colors.accentStart, style = MaterialTheme.typography.titleMedium)
            }
            Text(description, color = colors.muted, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                valueRange = range,
                colors = SliderDefaults.colors(
                    thumbColor = colors.accentStart,
                    activeTrackColor = colors.accentStart,
                    inactiveTrackColor = colors.field,
                ),
            )
        }
    }
}

@Composable
fun DescribedIntSlider(
    label: String,
    description: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    DescribedSlider(
        label = label,
        description = description,
        valueLabel = value.toString(),
        value = value.toFloat(),
        range = range,
        onChange = { onChange(it.roundToInt()) },
        modifier = modifier,
    )
}

@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCaramelColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FieldShape)
            .background(colors.danger.copy(alpha = 0.15f))
            .border(1.dp, colors.danger.copy(alpha = 0.4f), FieldShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = colors.danger, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
) {
    val colors = LocalCaramelColors.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.bubbleBot)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
fun CaramelBottomBar(
    selectedTab: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCaramelColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomTabItem(
            selected = selectedTab == MainTab.Chats,
            label = "Chats",
            iconRes = R.drawable.ic_chat,
            onClick = { onSelect(MainTab.Chats) },
            accent = colors.accentStart,
        )
        BottomTabItem(
            selected = selectedTab == MainTab.Profile,
            label = "Perfil",
            iconRes = R.drawable.ic_profile,
            onClick = { onSelect(MainTab.Profile) },
            accent = colors.accentStart,
        )
    }
}

enum class MainTab { Chats, Profile }

@Composable
private fun BottomTabItem(
    selected: Boolean,
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    accent: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = if (selected) accent else LocalCaramelColors.current.muted,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (selected) accent else LocalCaramelColors.current.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun FieldActionChip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalCaramelColors.current
    Text(
        text = text,
        color = if (enabled) colors.accentStart else colors.muted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
