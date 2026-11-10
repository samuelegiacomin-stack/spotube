/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.ui.theming

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import org.spotube.millennium.composables.MillenniumButtonThemeProperties
import org.spotube.millennium.composables.MillenniumIconButtonThemeProperties
import org.spotube.millennium.theme.MillenniumControlStateProperty
import org.spotube.millennium.theme.MillenniumThemeComponents
import org.spotube.millennium.theme.MillenniumColorSchemes
import org.spotube.millennium.theme.MillenniumColors
import org.spotube.millennium.theme.resolve
import org.spotube.spotube.modules.settings.AppearanceAccent
import org.spotube.spotube.modules.settings.AppearanceStyle
import org.spotube.spotube.modules.settings.UserSettings

private val defaultSchemes = MillenniumColorSchemes()

internal fun AppearanceAccent.color(dark: Boolean): Color = Color(when (this) {
    AppearanceAccent.BLUE -> if (dark) 0xFF8DB7FA else 0xFF245EB5
    AppearanceAccent.ORANGE -> if (dark) 0xFFFFB88B else 0xFF99441F
    AppearanceAccent.LIME -> if (dark) 0xFFCBDC74 else 0xFF4C6312
    AppearanceAccent.TEAL -> if (dark) 0xFF8AD8C9 else 0xFF17665B
    AppearanceAccent.PLUM -> if (dark) 0xFFE1ACFA else 0xFF754399
})

private data class Surfaces(
    val background: Long,
    val card: Long,
    val raised: Long,
    val border: Long,
    val text: Long,
    val muted: Long,
)

private fun neutralSurfaces(background: Long, card: Long, raised: Long, border: Long, dark: Boolean) =
    Surfaces(background, card, raised, border,
        if (dark) 0xFFF4F4F4 else 0xFF191919,
        if (dark) 0xFFB6B6B6 else 0xFF555555)

private fun AppearanceStyle.surfaces(dark: Boolean): Surfaces? = when (this) {
    AppearanceStyle.CLASSIC -> null
    AppearanceStyle.GLASS -> if (dark) neutralSurfaces(0xFF11171F, 0xFF1B242F, 0xFF293646, 0xFF4A5A6B, dark)
        else neutralSurfaces(0xFFF2F4F6, 0xFFFFFFFF, 0xFFE7EBEF, 0xFFB7C2CF, dark)
    AppearanceStyle.MINIMAL -> if (dark) neutralSurfaces(0xFF1A1917, 0xFF24221F, 0xFF2D2B27, 0xFF56534D, dark)
        else neutralSurfaces(0xFFF7F5F0, 0xFFFFFEFB, 0xFFEEECE7, 0xFFC9C4B9, dark)
    AppearanceStyle.PIXEL -> if (dark) neutralSurfaces(0xFF141320, 0xFF24213A, 0xFF332E50, 0xFF73699D, dark)
        else neutralSurfaces(0xFFFFF8E8, 0xFFFFFDF2, 0xFFF0E0B5, 0xFFA69363, dark)
    AppearanceStyle.MONO -> if (dark) neutralSurfaces(0xFF111111, 0xFF1D1D1D, 0xFF303030, 0xFF595959, dark)
        else neutralSurfaces(0xFFF5F5F5, 0xFFFFFFFF, 0xFFEAEAEA, 0xFFBBBBBB, dark)
    AppearanceStyle.TORMENTIL -> if (dark)
        Surfaces(0xFF1A211B, 0xFF232D24, 0xFF303B2E, 0xFF626B52, 0xFFF8EFDA, 0xFFD4CAAE)
        else Surfaces(0xFFF7EFD9, 0xFFFDF7E8, 0xFFEAE0C5, 0xFFB09B76, 0xFF292C21, 0xFF574C35)
    AppearanceStyle.BLUE_POND -> if (dark)
        Surfaces(0xFF11252D, 0xFF172F38, 0xFF213D47, 0xFF567984, 0xFFE6F6F2, 0xFFBBDFD8)
        else Surfaces(0xFFE2F1E7, 0xFFF2FAF6, 0xFFCCE6E0, 0xFF88ADA7, 0xFF153349, 0xFF365866)
    AppearanceStyle.STRAWBERRIES -> if (dark)
        Surfaces(0xFF281A22, 0xFF34232C, 0xFF432D37, 0xFF8C6171, 0xFFFFEFE9, 0xFFF1CDD4)
        else Surfaces(0xFFFFF4E9, 0xFFFFFBF6, 0xFFF9E1DF, 0xFFCF9D9E, 0xFF50232E, 0xFF713C49)
}

internal fun appearanceColors(settings: UserSettings, dark: Boolean): MillenniumColors {
    val style = settings.appearanceStyle
    val base = defaultSchemes.resolve(settings.palette, dark = dark)
    val surface = style.surfaces(dark)
    val themed = if (surface == null) base else base.copy(
        background = Color(surface.background),
        surface = Color(surface.card),
        raised = Color(surface.raised),
        inset = Color(surface.background),
        text = Color(surface.text),
        muted = Color(surface.muted),
        border = Color(surface.border),
    )
    val accent = settings.appearanceAccent?.color(dark) ?: when (style) {
        AppearanceStyle.CLASSIC -> return base
        AppearanceStyle.GLASS -> AppearanceAccent.BLUE.color(dark)
        AppearanceStyle.MINIMAL -> AppearanceAccent.TEAL.color(dark)
        AppearanceStyle.PIXEL -> if (dark) AppearanceAccent.LIME.color(true) else AppearanceAccent.PLUM.color(false)
        AppearanceStyle.MONO -> Color(if (dark) 0xFFF0F0F0 else 0xFF222222)
        AppearanceStyle.TORMENTIL -> Color(if (dark) 0xFFF4CB64 else 0xFF785210)
        AppearanceStyle.BLUE_POND -> Color(if (dark) 0xFF9EC7FF else 0xFF2450A0)
        AppearanceStyle.STRAWBERRIES -> Color(if (dark) 0xFFFFA3B4 else 0xFFAA263E)
    }
    return themed.copy(
        accent = accent,
        accentTop = accent,
        accentBottom = accent,
        onAccent = if (dark) Color(0xFF14171C) else Color.White,
    )
}

internal fun MillenniumThemeComponents.illustratedButtons(
    colors: MillenniumColors,
    buttonShape: Shape,
): MillenniumThemeComponents {
    fun MillenniumControlStateProperty<MillenniumButtonThemeProperties>.matteButton(
        fill: Color,
        foreground: Color,
    ) = matteStates { props, tint ->
        props.copy(
            background = SolidColor(lerp(fill, foreground, tint)),
            foreground = foreground,
            shape = buttonShape,
            innerGlowColor = Color.Transparent,
            bottomRimColor = Color.Transparent,
            shadows = emptyList(),
            shadowElevation = 0.dp,
        )
    }

    fun MillenniumControlStateProperty<MillenniumIconButtonThemeProperties>.matteIconButton(
        fill: Color,
        foreground: Color,
    ) = matteStates { props, tint ->
        props.copy(
            background = SolidColor(lerp(fill, foreground, tint)),
            foreground = foreground,
            orb = false,
            innerGlowColor = Color.Transparent,
            bottomRimColor = Color.Transparent,
            shadows = emptyList(),
            shadowElevation = 0.dp,
        )
    }

    return copy(
        buttonTheme = buttonTheme.copy(
            primary = buttonTheme.primary.matteButton(colors.accent, colors.onAccent),
            secondary = buttonTheme.secondary.matteButton(colors.surface, colors.text),
        ),
        iconButtonTheme = iconButtonTheme.copy(
            primary = iconButtonTheme.primary.matteIconButton(colors.accent, colors.onAccent),
            secondary = iconButtonTheme.secondary.matteIconButton(colors.surface, colors.text),
        ),
    )
}

private fun <T> MillenniumControlStateProperty<T>.matteStates(transform: (T, Float) -> T) = copy(
    default = transform(default, 0f),
    hovered = transform(hovered, 0.04f),
    focused = transform(focused, 0.04f),
    pressed = transform(pressed, 0.08f),
    disabled = transform(disabled, 0f),
    selected = transform(selected, 0.04f),
    error = error,
)
