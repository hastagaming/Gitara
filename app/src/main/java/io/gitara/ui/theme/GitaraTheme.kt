package io.gitara.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.gitara.hosting.GitHost

private data class Accent(val light: Color, val dark: Color)

private fun accentFor(host: GitHost): Accent = when (host) {
    GitHost.GITHUB -> Accent(Color(0xFF0969DA), Color(0xFF2F81F7))
    GitHost.GITLAB -> Accent(Color(0xFFE24329), Color(0xFFFC6D26))
    GitHost.BITBUCKET -> Accent(Color(0xFF0052CC), Color(0xFF2684FF))
    GitHost.GITEA -> Accent(Color(0xFF4F7F1D), Color(0xFF609926))
    GitHost.CODEBERG -> Accent(Color(0xFF2185D0), Color(0xFF4AA3E3))
    GitHost.SOURCEHUT -> Accent(Color(0xFF333333), Color(0xFFB0B0B0))
    GitHost.GENERIC -> Accent(Color(0xFF0D9488), Color(0xFF2DD4BF))
}

private fun readableOn(color: Color): Color =
    if (color.luminance() > 0.5f) Color.Black else Color.White

private fun lightScheme(accent: Color): ColorScheme {
    val white = Color.White
    return lightColorScheme(
        primary = accent,
        onPrimary = readableOn(accent),
        primaryContainer = accent.copy(alpha = 0.14f).compositeOver(white),
        onPrimaryContainer = accent,
        secondary = Color(0xFF59636E),
        onSecondary = white,
        background = white,
        onBackground = Color(0xFF1F2328),
        surface = white,
        onSurface = Color(0xFF1F2328),
        surfaceVariant = Color(0xFFF6F8FA),
        onSurfaceVariant = Color(0xFF59636E),
        surfaceContainerLowest = white,
        surfaceContainerLow = Color(0xFFF6F8FA),
        surfaceContainer = Color(0xFFF6F8FA),
        surfaceContainerHigh = Color(0xFFEAEEF2),
        surfaceContainerHighest = Color(0xFFD0D7DE),
        outline = Color(0xFFD0D7DE),
        outlineVariant = Color(0xFFD8DEE4),
        error = Color(0xFFCF222E),
        onError = white
    )
}

private fun darkScheme(accent: Color): ColorScheme {
    val page = Color(0xFF0D1117)
    return darkColorScheme(
        primary = accent,
        onPrimary = readableOn(accent),
        primaryContainer = accent.copy(alpha = 0.18f).compositeOver(page),
        onPrimaryContainer = accent,
        secondary = Color(0xFF8D96A0),
        onSecondary = page,
        background = page,
        onBackground = Color(0xFFE6EDF3),
        surface = page,
        onSurface = Color(0xFFE6EDF3),
        surfaceVariant = Color(0xFF161B22),
        onSurfaceVariant = Color(0xFF8D96A0),
        surfaceContainerLowest = Color(0xFF010409),
        surfaceContainerLow = page,
        surfaceContainer = Color(0xFF161B22),
        surfaceContainerHigh = Color(0xFF21262D),
        surfaceContainerHighest = Color(0xFF30363D),
        outline = Color(0xFF3D444D),
        outlineVariant = Color(0xFF30363D),
        error = Color(0xFFF85149),
        onError = Color.Black
    )
}

private val GitaraShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun GitaraTheme(
    host: GitHost = GitHost.GENERIC,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        remember(host, darkTheme) {
            val accent = accentFor(host)
            if (darkTheme) darkScheme(accent.dark) else lightScheme(accent.light)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = GitaraShapes,
        content = content
    )
}