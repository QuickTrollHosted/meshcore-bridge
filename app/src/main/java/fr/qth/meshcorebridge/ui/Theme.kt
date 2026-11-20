package fr.qth.meshcorebridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Couleurs sémantiques d'état (indépendantes de la couleur dynamique, contrastées en clair/sombre). */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val error: Color, val idle: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Color(0xFF1B7F3B), Color(0xFF9A6700), Color(0xFFB3261E), Color(0xFF6B7280)) }

private val Brand = Color(0xFF0B3D5C)   // = fond de l'icône
private val LightScheme = lightColorScheme(primary = Brand, onPrimary = Color.White, primaryContainer = Color(0xFFCFE6F5), onPrimaryContainer = Color(0xFF001E30))
private val DarkScheme = darkColorScheme(primary = Color(0xFF8FCDF2), onPrimary = Color(0xFF00344F), primaryContainer = Color(0xFF004B70), onPrimaryContainer = Color(0xFFCFE6F5))

@Composable
fun MeshTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    val status = if (dark) StatusColors(Color(0xFF6DD58C), Color(0xFFF2B84B), Color(0xFFFFB4AB), Color(0xFF9CA3AF))
    else StatusColors(Color(0xFF1B7F3B), Color(0xFF8A5A00), Color(0xFFB3261E), Color(0xFF6B7280))
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
