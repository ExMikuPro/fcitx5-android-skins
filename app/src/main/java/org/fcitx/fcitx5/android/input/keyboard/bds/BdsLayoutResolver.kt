/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import android.content.res.Configuration
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.bds.BdsLayout
import org.fcitx.fcitx5.android.data.theme.bds.BdsOrientation
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkin

/** Resolves a BDS layout by capability; callers fall back to Fcitx when it returns null. */
object BdsLayoutResolver {
    enum class Purpose { Text26, Number26, Symbol, ChineseSelection, EnglishSelection }

    fun orientation(configuration: Configuration): BdsOrientation =
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            BdsOrientation.Landscape
        } else {
            BdsOrientation.Portrait
        }

    fun resolve(
        skin: BdsSkin,
        orientation: BdsOrientation,
        purpose: Purpose,
        ime: InputMethodEntry? = null,
        tall: Boolean = false
    ): BdsLayout? {
        val baseNames = when (purpose) {
            Purpose.Text26 -> textLayoutNames(ime)
            Purpose.Number26 -> listOf("num_26")
            Purpose.Symbol -> listOf("symbol")
            Purpose.ChineseSelection -> listOf("sel_ch")
            Purpose.EnglishSelection -> listOf("sel_en")
        }
        val candidates = if (tall) {
            baseNames.flatMap { listOf("${it}_h", it) }
        } else {
            baseNames
        }
        return candidates.firstNotNullOfOrNull { skin.layout(orientation, it) }
            // A number of real BDS archives are portrait-only. Rendering their
            // portrait coordinates is safer and more useful than silently
            // dropping back to an unrelated non-BDS keyboard on rotation.
            ?: candidates.firstNotNullOfOrNull {
                skin.layout(
                    if (orientation == BdsOrientation.Portrait) {
                        BdsOrientation.Landscape
                    } else {
                        BdsOrientation.Portrait
                    },
                    it
                )
            }
    }

    private fun textLayoutNames(ime: InputMethodEntry?): List<String> {
        if (ime == null) return listOf("py_26", "def_26", "en_26")
        val language = ime.languageCode.lowercase()
        val identity = listOf(ime.uniqueName, ime.name, ime.nativeName)
            .joinToString(" ").lowercase()
        val chinese = language.startsWith("zh") ||
            identity.contains("pinyin") || identity.contains("拼音") || identity.contains("中文")
        return if (chinese) {
            listOf("py_26", "def_26", "chtmp", "en_26")
        } else {
            listOf("en_26", "def_26", "py_26")
        }
    }
}
