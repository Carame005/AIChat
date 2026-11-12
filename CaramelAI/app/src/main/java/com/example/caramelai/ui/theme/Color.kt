package com.example.caramelai.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class AppTheme {
    Dark,
    Light,
    Pastel,
    Neon,
    Caramel,
}

data class CaramelColors(
    val bubbleUser: Color,
    val bubbleBot: Color,
    val dialogue: Color,
    val action: Color,
    val card: Color,
    val field: Color,
    val accentStart: Color,
    val accentEnd: Color,
    val muted: Color,
    val success: Color,
    val danger: Color,
) {
    val accentBrush: Brush
        get() = Brush.horizontalGradient(listOf(accentStart, accentEnd))
}

val DarkCaramelColors = CaramelColors(
    bubbleUser = Color(0xFF2A2433),
    bubbleBot = Color(0xFF1E1C24),
    dialogue = Color(0xFFE8E4F0),
    action = Color(0xFFB8A9D4),
    card = Color(0xFF1E1C24),
    field = Color(0xFF25222C),
    accentStart = Color(0xFFFF6B7A),
    accentEnd = Color(0xFFFFB347),
    muted = Color(0xFF8A8494),
    success = Color(0xFF6BCB8A),
    danger = Color(0xFFFF6B7A),
)

val LightCaramelColors = CaramelColors(
    bubbleUser = Color(0xFFE8DFF5),
    bubbleBot = Color(0xFFF2F0F5),
    dialogue = Color(0xFF2A2433),
    action = Color(0xFF6B5B8A),
    card = Color(0xFFFFFFFF),
    field = Color(0xFFF5F3F8),
    accentStart = Color(0xFFE85A6B),
    accentEnd = Color(0xFFF0A04B),
    muted = Color(0xFF7A7484),
    success = Color(0xFF3DAA68),
    danger = Color(0xFFE85A6B),
)

val PastelCaramelColors = CaramelColors(
    bubbleUser = Color(0xFFE8D5F2),
    bubbleBot = Color(0xFFD5E8F2),
    dialogue = Color(0xFF4A3F5C),
    action = Color(0xFF7B6B9A),
    card = Color(0xFFFFF8FC),
    field = Color(0xFFF5EBF8),
    accentStart = Color(0xFFFF9EB5),
    accentEnd = Color(0xFFB5A8FF),
    muted = Color(0xFF9A90A8),
    success = Color(0xFF7BC9A6),
    danger = Color(0xFFFF8FA3),
)

val NeonCaramelColors = CaramelColors(
    bubbleUser = Color(0xFF1A1030),
    bubbleBot = Color(0xFF0F1420),
    dialogue = Color(0xFFE0F7FF),
    action = Color(0xFFB388FF),
    card = Color(0xFF121826),
    field = Color(0xFF1A2233),
    accentStart = Color(0xFF00F5D4),
    accentEnd = Color(0xFFFF2E97),
    muted = Color(0xFF7A88A0),
    success = Color(0xFF00F5D4),
    danger = Color(0xFFFF2E97),
)

val CaramelBrandColors = CaramelColors(
    bubbleUser = Color(0xFF3A2A1F),
    bubbleBot = Color(0xFF2A2118),
    dialogue = Color(0xFFFFF3E6),
    action = Color(0xFFD4A574),
    card = Color(0xFF2A2118),
    field = Color(0xFF34281E),
    accentStart = Color(0xFFE8A87C),
    accentEnd = Color(0xFFC38D5F),
    muted = Color(0xFFA89078),
    success = Color(0xFF8FBF7A),
    danger = Color(0xFFE07A5F),
)

fun caramelColorsFor(theme: AppTheme): CaramelColors = when (theme) {
    AppTheme.Dark -> DarkCaramelColors
    AppTheme.Light -> LightCaramelColors
    AppTheme.Pastel -> PastelCaramelColors
    AppTheme.Neon -> NeonCaramelColors
    AppTheme.Caramel -> CaramelBrandColors
}
