package com.k2fsa.sherpa.onnx.tts.engine.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE3D0B2),
    onPrimary = Color(0xFF292017),

    secondary = Color(0xFFC8B69B),
    onSecondary = Color(0xFF292017),

    tertiary = Color(0xFFB9A58B),
    onTertiary = Color(0xFF211A13),

    background = Color(0xFF19140F),
    onBackground = Color(0xFFE3D0B2),

    surface = Color(0xFF211A13),
    onSurface = Color(0xFFE3D0B2),

    surfaceVariant = Color(0xFF30271E),
    onSurfaceVariant = Color(0xFFD0C0A8),

    outline = Color(0xFF8D8275),
    outlineVariant = Color(0xFF51473C),

    error = Color(0xFFE7B8A8),
    onError = Color(0xFF351B14),

    inverseSurface = Color(0xFFE3D0B2),
    inverseOnSurface = Color(0xFF292017),
    inversePrimary = Color(0xFF65513A)
)

@Composable
fun SherpaOnnxTtsEngineTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkColorScheme.background.toArgb()
            window.navigationBarColor = DarkColorScheme.background.toArgb()

            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
