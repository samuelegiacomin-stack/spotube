/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.ui.theming

import androidx.compose.ui.graphics.Color
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

private data class Surfaces(val background: Long, val card: Long, val raised: Long, val border: Long)

private fun AppearanceStyle.surfaces(dark: Boolean): Surfaces? = when (this) {
    AppearanceStyle.CLASSIC -> null
    AppearanceStyle.GLASS -> if (dark) Surfaces(0xFF11171F, 0xFF1B242F, 0xFF293646, 0xFF4A5A6B)
        else Surfaces(0xFFF2F4F6, 0xFFFFFFFF, 0xFFE7EBEF, 0xFFB7C2CF)
    AppearanceStyle.MINIMAL -> if (dark) Surfaces(0xFF1A1917, 0xFF24221F, 0xFF2D2B27, 0xFF56534D)
        else Surfaces(0xFFF7F5F0, 0xFFFFFEFB, 0xFFEEECE7, 0xFFC9C4B9)
    AppearanceStyle.PIXEL -> if (dark) Surfaces(0xFF141320, 0xFF24213A, 0xFF332E50, 0xFF73699D)
        else Surfaces(0xFFFFF8E8, 0xFFFFFDF2, 0xFFF0E0B5, 0xFFA69363)
    AppearanceStyle.MONO -> if (dark) Surfaces(0xFF111111, 0xFF1D1D1D, 0xFF303030, 0xFF595959)
        else Surfaces(0xFFF5F5F5, 0xFFFFFFFF, 0xFFEAEAEA, 0xFFBBBBBB)
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
        text = Color(if (dark) 0xFFF4F4F4 else 0xFF191919),
        muted = Color(if (dark) 0xFFB6B6B6 else 0xFF555555),
        border = Color(surface.border),
    )
    val accent = settings.appearanceAccent?.color(dark) ?: when (style) {
        AppearanceStyle.CLASSIC -> return base
        AppearanceStyle.GLASS -> AppearanceAccent.BLUE.color(dark)
        AppearanceStyle.MINIMAL -> AppearanceAccent.TEAL.color(dark)
        AppearanceStyle.PIXEL -> if (dark) AppearanceAccent.LIME.color(true) else AppearanceAccent.PLUM.color(false)
        AppearanceStyle.MONO -> Color(if (dark) 0xFFF0F0F0 else 0xFF222222)
    }
    return themed.copy(
        accent = accent,
        accentTop = accent,
        accentBottom = accent,
        onAccent = if (dark) Color(0xFF14171C) else Color.White,
    )
}
