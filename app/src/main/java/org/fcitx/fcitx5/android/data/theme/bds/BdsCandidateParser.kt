/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import java.io.File

object BdsCandidateParser {
    fun parse(file: File): BdsCandidateDefinition {
        val ini = BdsIni.parse(file)
        val candidate = ini.section("CAND").orEmpty()
        val switchValues = ini.section("SWITCH")
        return BdsCandidateDefinition(
            backgroundStyle = candidate.int("BACK_STYLE"),
            foregroundStyle = candidate.int("FORE_STYLE"),
            cellStyle = candidate.int("CELL_STYLE"),
            padding = candidate.insets("PADDING"),
            firstGap = candidate.int("FIRST_GAP") ?: 0,
            firstForegroundStyle = candidate.int("FIRST_FORE"),
            cellWidth = candidate.int("CELL_W") ?: 0,
            switch = switchValues?.let {
                BdsCandidateSwitch(
                    normalBackgroundStyle = it.int("NML_BACK_STYLE"),
                    selectedBackgroundStyle = it.int("SEL_BACK_STYLE"),
                    normalFontStyle = it.int("NML_FONT_STYLE"),
                    selectedFontStyle = it.int("SEL_FONT_STYLE"),
                    padding = it.insets("PADDING"),
                    properties = it
                )
            },
            icons = ini.sections.mapNotNull { (section, values) ->
                if (!section.startsWith("ICON", ignoreCase = true)) return@mapNotNull null
                BdsCandidateIcon(
                    section = section,
                    backgroundStyle = values.int("BACK_STYLE"),
                    foregroundStyle = values.int("FORE_STYLE"),
                    animationStyle = values.int("ANIM_STYLE"),
                    size = values.pair("SIZE")?.let { BdsSize(it.first, it.second) },
                    anchorType = values.int("ANCHOR_TYPE"),
                    position = values.pair("POS")?.let { BdsPoint(it.first, it.second) },
                    key = values.value("KEY"),
                    persist = values.int("PERSIST"),
                    stateStyle = values.value("STAT_STYLE"),
                    properties = values
                )
            },
            tips = ini.sections.mapNotNull { (section, values) ->
                if (!section.startsWith("TIP", ignoreCase = true)) return@mapNotNull null
                BdsCandidateTip(
                    section = section,
                    backgroundStyle = values.int("BACK_STYLE"),
                    foregroundStyle = values.int("FORE_STYLE"),
                    key = values.value("KEY"),
                    properties = values
                )
            },
            sections = ini.sections
        )
    }

    private fun Map<String, String>.value(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun Map<String, String>.int(name: String): Int? = value(name)?.toIntOrNull()

    private fun Map<String, String>.pair(name: String): Pair<Int, Int>? {
        val values = value(name)?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
        if (values.size != 2 || values.any { it == null }) return null
        return values[0]!! to values[1]!!
    }

    private fun Map<String, String>.insets(name: String): BdsInsets {
        val values = value(name)?.split(',')?.map { it.trim().toIntOrNull() } ?: return BdsInsets.Zero
        return when {
            values.size == 1 && values[0] != null -> BdsInsets(values[0]!!, values[0]!!, values[0]!!, values[0]!!)
            values.size == 4 && values.none { it == null } ->
                BdsInsets(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
            else -> BdsInsets.Zero
        }
    }
}
