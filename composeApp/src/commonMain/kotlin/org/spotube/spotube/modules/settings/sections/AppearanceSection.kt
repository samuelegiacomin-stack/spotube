/*
 * Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.spotube.spotube.modules.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.spotube.millennium.composables.MillenniumButton
import org.spotube.millennium.composables.MillenniumButtonStyle
import org.spotube.millennium.composables.MillenniumDialog
import org.spotube.millennium.composables.MillenniumDialogBody
import org.spotube.millennium.composables.MillenniumDialogClose
import org.spotube.millennium.composables.MillenniumDialogFooter
import org.spotube.millennium.composables.MillenniumDialogHeader
import org.spotube.millennium.composables.MillenniumDialogTitle
import org.spotube.millennium.composables.MillenniumIcon
import org.spotube.millennium.composables.MillenniumText
import org.spotube.spotube.core.ui.theming.AppearanceIllustration
import org.spotube.spotube.core.ui.theming.AppearanceMotif
import org.spotube.spotube.core.ui.theming.AppTheme
import org.spotube.spotube.core.ui.theming.isIllustrated
import org.spotube.spotube.core.ui.theming.appearanceColors
import org.spotube.spotube.core.ui.theming.color
import org.spotube.spotube.core.ui.theming.isLightAppearance
import org.spotube.spotube.modules.settings.AppearanceAccent
import org.spotube.spotube.modules.settings.AppearanceStyle
import org.spotube.spotube.modules.settings.SettingsViewModel
import org.spotube.spotube.modules.settings.Theme
import org.spotube.spotube.modules.settings.UserSettings
import org.spotube.spotube.modules.settings.components.SelectionSettingCard
import org.spotube.spotube.modules.settings.components.SettingCardItem
import org.spotube.spotube.resources.iconsax.Iconsax
import org.spotube.spotube.resources.iconsax.IconsaxColorSwatch
import org.spotube.spotube.resources.iconsax.IconsaxMagic
import org.spotube.spotube.resources.iconsax.IconsaxMusic
import org.spotube.spotube.resources.iconsax.IconsaxPlay
import spotube.composeapp.generated.resources.*

internal fun LazyListScope.appearanceSection(
    settings: UserSettings,
    settingsViewModel: SettingsViewModel,
    includeHeader: Boolean = true,
) {
    if (includeHeader) settingsSectionHeader(Res.string.settings_section_appearance)
    settingsSectionCard(
        items = listOf(
            {
                SelectionSettingCard(
                    title = stringResource(Res.string.settings_theme_title),
                    subtitle = stringResource(Res.string.settings_theme_subtitle_current, settings.theme.displayLabel()),
                    icon = {
                        SettingsItemIcon(Iconsax.IconsaxColorSwatch, stringResource(Res.string.settings_theme_title))
                    },
                    selectedOption = settings.theme,
                    options = Theme.entries,
                    optionLabel = { it.displayLabel() },
                    onOptionSelected = { theme -> settingsViewModel.updateSettings { copy(theme = theme) } },
                )
            },
            {
                AppearanceSettingCard(settings) { style, accent ->
                    settingsViewModel.updateSettings { copy(appearanceStyle = style, appearanceAccent = accent) }
                }
            },
        ),
    )
}

@Composable
private fun Theme.displayLabel(): String = stringResource(when (this) {
    Theme.LIGHT -> Res.string.settings_theme_light
    Theme.DARK -> Res.string.settings_theme_dark
    Theme.SYSTEM -> Res.string.settings_theme_system
})

@Composable
private fun AppearanceStyle.displayLabel(): String = stringResource(when (this) {
    AppearanceStyle.CLASSIC -> Res.string.settings_style_classic
    AppearanceStyle.GLASS -> Res.string.settings_style_glass
    AppearanceStyle.MINIMAL -> Res.string.settings_style_minimal
    AppearanceStyle.PIXEL -> Res.string.settings_style_pixel
    AppearanceStyle.MONO -> Res.string.settings_style_mono
    AppearanceStyle.TORMENTIL -> Res.string.settings_style_tormentil
    AppearanceStyle.BLUE_POND -> Res.string.settings_style_blue_pond
    AppearanceStyle.STRAWBERRIES -> Res.string.settings_style_strawberries
})

@Composable
private fun AppearanceAccent?.displayLabel(): String = stringResource(when (this) {
    null -> Res.string.settings_accent_automatic
    AppearanceAccent.BLUE -> Res.string.settings_palette_blue
    AppearanceAccent.ORANGE -> Res.string.settings_palette_orange
    AppearanceAccent.LIME -> Res.string.settings_palette_lime
    AppearanceAccent.TEAL -> Res.string.settings_accent_teal
    AppearanceAccent.PLUM -> Res.string.settings_accent_plum
})

@Composable
private fun AppearanceSettingCard(
    settings: UserSettings,
    onSaved: (AppearanceStyle, AppearanceAccent?) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val dark = !isLightAppearance()
    val currentColors = appearanceColors(settings, dark)
    SettingCardItem(
        title = stringResource(Res.string.settings_appearance_title),
        subtitle = stringResource(
            Res.string.settings_appearance_selection,
            settings.appearanceStyle.displayLabel(),
            settings.appearanceAccent.displayLabel(),
        ),
        icon = { SettingsItemIcon(Iconsax.IconsaxMagic, stringResource(Res.string.settings_appearance_title)) },
        trailingContent = { Box(Modifier.size(20.dp).background(currentColors.accent, CircleShape)) },
        onClick = { open = true },
    )
    if (open) {
        var style by rememberSaveable(settings.appearanceStyle) { mutableStateOf(settings.appearanceStyle) }
        var accent by rememberSaveable(settings.appearanceAccent) { mutableStateOf(settings.appearanceAccent) }
        MillenniumDialog(visible = true, onDismissRequest = { open = false }) {
            MillenniumDialogHeader {
                MillenniumDialogTitle(stringResource(Res.string.settings_appearance_dialog_title))
                MillenniumDialogClose()
            }
            MillenniumDialogBody {
                AppearanceEditor(
                    settings = settings.copy(appearanceStyle = style, appearanceAccent = accent),
                    dark = dark,
                    onStyleChange = { style = it },
                    onAccentChange = { accent = it },
                )
            }
            MillenniumDialogFooter {
                MillenniumButton(onClick = { open = false }, style = MillenniumButtonStyle.Secondary) {
                    MillenniumText(stringResource(Res.string.common_cancel))
                }
                MillenniumButton(onClick = {
                    onSaved(style, accent)
                    open = false
                }) {
                    MillenniumText(stringResource(Res.string.common_save))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceEditor(
    settings: UserSettings,
    dark: Boolean,
    onStyleChange: (AppearanceStyle) -> Unit,
    onAccentChange: (AppearanceAccent?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 410.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppearancePreview(settings, dark)
        MillenniumText(stringResource(Res.string.settings_style_illustrated), style = AppTheme.typography.titleMedium)
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppearanceStyle.entries.filter { it.isIllustrated }.forEach { option ->
                val selected = option == settings.appearanceStyle
                val colors = appearanceColors(settings.copy(appearanceStyle = option, appearanceAccent = null), dark)
                val shape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().clip(shape).background(colors.surface)
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.border, shape)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onStyleChange(option) })
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppearanceMotif(option, colors.accent, Modifier.size(22.dp))
                    MillenniumText(
                        option.displayLabel(),
                        color = colors.text,
                        style = AppTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
        MillenniumText(stringResource(Res.string.settings_visual_style), style = AppTheme.typography.titleMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            AppearanceStyle.entries.filterNot { it.isIllustrated }.forEach { option ->
                val chosen = settings.appearanceStyle == option
                val colors = appearanceColors(settings.copy(appearanceStyle = option, appearanceAccent = null), dark)
                val shape = RoundedCornerShape(if (option == AppearanceStyle.PIXEL) 3.dp else 12.dp)
                Column(
                    Modifier.weight(1f).clip(shape).background(colors.surface)
                        .border(if (chosen) 2.dp else 1.dp, if (chosen) colors.accent else colors.border, shape)
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { onStyleChange(option) })
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(width = 30.dp, height = 12.dp).background(colors.accent, shape))
                    MillenniumText(option.displayLabel(), color = colors.text, style = AppTheme.typography.labelMedium.copy(
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                    ))
                }
            }
        }
        MillenniumText(stringResource(Res.string.settings_appearance_accent_label), style = AppTheme.typography.titleMedium)
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            (listOf<AppearanceAccent?>(null) + AppearanceAccent.entries).forEach { option ->
                val selected = option == settings.appearanceAccent
                val label = option.displayLabel()
                val color = option?.color(dark) ?: appearanceColors(settings.copy(appearanceAccent = null), dark).accent
                val swatchShape = if (option == null) RoundedCornerShape(8.dp) else CircleShape
                Box(
                    modifier = Modifier.size(48.dp).selectable(
                        selected = selected, role = Role.RadioButton, onClick = { onAccentChange(option) },
                    ).semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(34.dp)
                            .border(if (selected) 2.dp else 1.dp,
                                if (selected) AppTheme.colors.onSurface else AppTheme.colors.outlineVariant,
                                swatchShape)
                            .padding(5.dp)
                            .background(color, swatchShape),
                    )
                }
            }
        }
        MillenniumText(settings.appearanceAccent.displayLabel(), color = AppTheme.colors.onSurfaceVariant,
            style = AppTheme.typography.labelMedium)
    }
}

@Composable
private fun AppearancePreview(settings: UserSettings, dark: Boolean) {
    val colors = appearanceColors(settings, dark)
    val shape = RoundedCornerShape(when (settings.appearanceStyle) {
        AppearanceStyle.CLASSIC -> 12.dp
        AppearanceStyle.GLASS -> 22.dp
        AppearanceStyle.MINIMAL -> 10.dp
        AppearanceStyle.PIXEL -> 2.dp
        AppearanceStyle.MONO -> 6.dp
        AppearanceStyle.TORMENTIL -> 14.dp
        AppearanceStyle.BLUE_POND -> 24.dp
        AppearanceStyle.STRAWBERRIES -> 20.dp
    })
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.background).border(1.dp, colors.border, shape)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp).background(colors.raised, shape), contentAlignment = Alignment.Center) {
                MillenniumIcon(Iconsax.IconsaxMusic, contentDescription = null, tint = colors.text)
            }
            Column(Modifier.weight(1f)) {
                MillenniumText(
                    stringResource(Res.string.settings_preview_player_title),
                    color = colors.text,
                    style = AppTheme.typography.titleMedium.copy(
                        fontFamily = when (settings.appearanceStyle) {
                            AppearanceStyle.PIXEL -> FontFamily.Monospace
                            AppearanceStyle.TORMENTIL -> FontFamily.Serif
                            else -> FontFamily.SansSerif
                        },
                    ),
                )
                MillenniumText(settings.appearanceStyle.displayLabel(), color = colors.muted, style = AppTheme.typography.bodySmall)
            }
            Box(Modifier.size(40.dp).background(colors.accent, shape), contentAlignment = Alignment.Center) {
                MillenniumIcon(Iconsax.IconsaxPlay, contentDescription = null, tint = colors.onAccent)
            }
        }
        if (settings.appearanceStyle.isIllustrated) {
            AppearanceIllustration(
                settings.appearanceStyle,
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 218.dp),
            )
        }
    }
}
