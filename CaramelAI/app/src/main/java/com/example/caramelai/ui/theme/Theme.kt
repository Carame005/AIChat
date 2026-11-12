package com.example.caramelai.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalCaramelColors = staticCompositionLocalOf { DarkCaramelColors }
val LocalAppTheme = staticCompositionLocalOf { AppTheme.Dark }

private fun darkScheme(c: CaramelColors) = darkColorScheme(
    primary = c.accentStart,
    onPrimary = Color.White,
    secondary = c.accentEnd,
    onSecondary = Color.White,
    tertiary = c.action,
    background = Color(0xFF16141A),
    onBackground = Color(0xFFF2F0F5),
    surface = Color(0xFF16141A),
    onSurface = Color(0xFFF2F0F5),
    surfaceVariant = c.card,
    onSurfaceVariant = c.muted,
    outline = Color(0xFF3A3642),
    error = c.danger,
)

private fun lightScheme(c: CaramelColors) = lightColorScheme(
    primary = c.accentStart,
    onPrimary = Color.White,
    secondary = c.accentEnd,
    onSecondary = Color.White,
    tertiary = c.action,
    background = Color(0xFFF7F4FA),
    onBackground = Color(0xFF1C1820),
    surface = Color(0xFFF7F4FA),
    onSurface = Color(0xFF1C1820),
    surfaceVariant = c.card,
    onSurfaceVariant = c.muted,
    outline = Color(0xFFD8D2E0),
    error = c.danger,
)

private fun pastelScheme(c: CaramelColors) = lightColorScheme(
    primary = c.accentStart,
    onPrimary = Color.White,
    secondary = c.accentEnd,
    onSecondary = Color.White,
    tertiary = c.action,
    background = Color(0xFFFFF5FA),
    onBackground = Color(0xFF3A3050),
    surface = Color(0xFFFFF5FA),
    onSurface = Color(0xFF3A3050),
    surfaceVariant = c.card,
    onSurfaceVariant = c.muted,
    outline = Color(0xFFE8D8F0),
    error = c.danger,
)

private fun neonScheme(c: CaramelColors) = darkColorScheme(
    primary = c.accentStart,
    onPrimary = Color.Black,
    secondary = c.accentEnd,
    onSecondary = Color.White,
    tertiary = c.action,
    background = Color(0xFF0A0E16),
    onBackground = Color(0xFFE8F4FF),
    surface = Color(0xFF0A0E16),
    onSurface = Color(0xFFE8F4FF),
    surfaceVariant = c.card,
    onSurfaceVariant = c.muted,
    outline = Color(0xFF2A3548),
    error = c.danger,
)

private fun caramelScheme(c: CaramelColors) = darkColorScheme(
    primary = c.accentStart,
    onPrimary = Color.White,
    secondary = c.accentEnd,
    onSecondary = Color.White,
    tertiary = c.action,
    background = Color(0xFF1A1510),
    onBackground = Color(0xFFFFF3E6),
    surface = Color(0xFF1A1510),
    onSurface = Color(0xFFFFF3E6),
    surfaceVariant = c.card,
    onSurfaceVariant = c.muted,
    outline = Color(0xFF3A3028),
    error = c.danger,
)

fun colorSchemeFor(theme: AppTheme, caramel: CaramelColors): ColorScheme = when (theme) {
    AppTheme.Dark -> darkScheme(caramel)
    AppTheme.Light -> lightScheme(caramel)
    AppTheme.Pastel -> pastelScheme(caramel)
    AppTheme.Neon -> neonScheme(caramel)
    AppTheme.Caramel -> caramelScheme(caramel)
}

fun AppTheme.isDark(): Boolean = when (this) {
    AppTheme.Dark, AppTheme.Neon, AppTheme.Caramel -> true
    AppTheme.Light, AppTheme.Pastel -> false
}

@Composable
fun CaramelAITheme(
    theme: AppTheme = AppTheme.Dark,
    content: @Composable () -> Unit,
) {
    val caramel = caramelColorsFor(theme)
    val colorScheme = colorSchemeFor(theme, caramel)
    val view = LocalView.current

    SideEffect {
        val activity = view.context as? ComponentActivity ?: return@SideEffect
        val scrim = colorScheme.background.toArgb()
        if (theme.isDark()) {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(scrim),
                navigationBarStyle = SystemBarStyle.dark(scrim),
            )
        } else {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(scrim, scrim),
                navigationBarStyle = SystemBarStyle.light(scrim, scrim),
            )
        }
        WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars =
            !theme.isDark()
        WindowCompat.getInsetsController(activity.window, view).isAppearanceLightNavigationBars =
            !theme.isDark()
    }

    CompositionLocalProvider(
        LocalCaramelColors provides caramel,
        LocalAppTheme provides theme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}

/** Convenience when theme not yet loaded from settings. */
@Composable
fun CaramelAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CaramelAITheme(
        theme = if (darkTheme) AppTheme.Dark else AppTheme.Light,
        content = content,
    )
}
