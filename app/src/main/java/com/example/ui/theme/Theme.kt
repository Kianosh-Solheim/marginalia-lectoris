package com.example.ui.theme

import android.view.WindowManager
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * E-Ink No-Op Indication: suppresses all Material touch ripples, gray focus rings,
 * and hover/press darkening overlays so e-ink panels never flash or ghost around focused items.
 */
object NoEInkRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
            }
        }
    }

    override fun hashCode(): Int = -1001
    override fun equals(other: Any?): Boolean = other === this
}

/**
 * Clears WindowManager.LayoutParams.FLAG_DIM_BEHIND on any Compose Dialog or AlertDialog
 * so opening a popup on an E-Ink display never darkens the background page around it.
 */
@Composable
fun DisableDialogDimming() {
    val view = LocalView.current
    SideEffect {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        dialogWindow?.let { win ->
            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = win.attributes
            if (params != null && params.dimAmount != 0f) {
                params.dimAmount = 0f
                win.attributes = params
            }
        }
    }
}

private val ColorEInkColorScheme = lightColorScheme(
    primary = MonoPrimary,
    onPrimary = MonoOnPrimary,
    primaryContainer = MonoPrimaryContainer,
    onPrimaryContainer = MonoOnPrimaryContainer,
    secondary = MonoSecondary,
    onSecondary = MonoOnSecondary,
    secondaryContainer = MonoSecondaryContainer,
    onSecondaryContainer = MonoOnSecondaryContainer,
    tertiary = KaleidoAmberInk,
    onTertiary = Color.White,
    tertiaryContainer = KaleidoAmberFill,
    onTertiaryContainer = KaleidoAmberInk,
    background = MonoBackground,
    surface = MonoSurface,
    surfaceVariant = MonoSurfaceVariant,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
    onBackground = MonoOnBackground,
    onSurface = MonoOnSurface,
    onSurfaceVariant = MonoOnSurfaceVariant,
    outline = MonoOutline,
    outlineVariant = PaperSubtleRule,
    scrim = Color.Transparent
)

private val EInkTextSelectionColors = TextSelectionColors(
    handleColor = InkBlack,
    backgroundColor = KaleidoAmberFill
)

@Composable
fun ReadMarkTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ColorEInkColorScheme,
        typography = Typography
    ) {
        CompositionLocalProvider(
            LocalIndication provides NoEInkRippleIndication,
            LocalTextSelectionColors provides EInkTextSelectionColors,
            content = content
        )
    }
}
