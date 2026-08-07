package dev.op.deptor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Green = Color(0xFF2E7D53)
private val GreenLight = Color(0xFF7BD8A4)
private val Sand = Color(0xFF6D5D4B)

private val LightScheme = lightColorScheme(
    primary = Green,
    secondary = Sand,
)

private val DarkScheme = darkColorScheme(
    primary = GreenLight,
    secondary = Color(0xFFD7C3AA),
)

/**
 * Barvy pro salda.
 *
 * Barva nesmí zůstat jediným nositelem informace — u částky se vždy zobrazuje i znaménko
 * nebo slovní popis, aby to dávalo smysl i tomu, kdo zelenou od červené nerozliší.
 */
val androidx.compose.material3.ColorScheme.creditColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7BD8A4) else Color(0xFF1B6E43)

val androidx.compose.material3.ColorScheme.debtColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFB4A9) else Color(0xFFB3261E)

@Composable
fun DeptorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Dynamické barvy z tapety jsou od Androidu 12 to, co uživatel očekává.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = colorScheme) {
        // Surface je tu nutný, ne kosmetický: bez něj má obsah barvy z colorScheme,
        // ale pozadí prosvítá z tématu Activity. V tmavém režimu pak sedí světlý text
        // na světlém pozadí a obrazovky bez vlastního Scaffoldu jsou nečitelné.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
