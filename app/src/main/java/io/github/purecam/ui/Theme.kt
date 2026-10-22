package io.github.purecam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFC340)
val Scrim = Color(0x99000000)

@Composable
fun PureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Color.Black,
            background = Color.Black,
            surface = Color(0xFF16181C),
            surfaceContainerLow = Color(0xFF16181C),
            onSurface = Color.White,
        ),
        content = content,
    )
}
