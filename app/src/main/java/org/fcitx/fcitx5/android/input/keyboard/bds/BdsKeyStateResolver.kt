/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.data.theme.bds.BdsKey
import org.fcitx.fcitx5.android.data.theme.bds.BdsKeyVariant
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAction

/** Maps runtime keyboard state to the legacy Baidu STAT_STYLE state contract. */
object BdsKeyStateResolver {
    // Confirmed by the Golden Sample's STAT_STYLE-to-TIP chain and the final
    // enter.png labels: 下一项, 前往, 发送, 确认, 搜索.
    private val editorState = mapOf(
        ReturnKeyAction.Done to 11,
        ReturnKeyAction.Next to 17,
        ReturnKeyAction.Search to 21,
        ReturnKeyAction.Go to 23,
        ReturnKeyAction.Send to 27
    )
    private val stateEntry = Regex("S(\\d+)_(\\d+)", RegexOption.IGNORE_CASE)

    fun resolve(
        key: BdsKey,
        action: ReturnKeyAction,
        variants: List<BdsKeyVariant>,
        caps: Boolean = false
    ): BdsKeyVariant? {
        val targetStates = buildSet {
            editorState[action]?.let(::add)
            // Golden Sample: KEY32 STAT_STYLE=S14_7 selects the active scallion.
            if (caps) add(14)
        }
        if (targetStates.isEmpty()) return null
        val raw = key.properties.entries
            .firstOrNull { it.key.equals("STAT_STYLE", ignoreCase = true) }
            ?.value ?: return null
        val variantIndex = raw.split('|').firstNotNullOfOrNull { entry ->
            val match = stateEntry.matchEntire(entry.trim()) ?: return@firstNotNullOfOrNull null
            val state = match.groupValues[1].toIntOrNull()
            val index = match.groupValues[2].toIntOrNull()
            index.takeIf { state in targetStates }
        } ?: return null
        return variants.firstOrNull {
            it.section.equals("TIP$variantIndex", ignoreCase = true)
        }
    }
}
