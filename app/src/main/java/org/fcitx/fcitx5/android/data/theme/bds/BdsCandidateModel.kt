/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

data class BdsCandidateDefinition(
    val backgroundStyle: Int?,
    val foregroundStyle: Int?,
    val cellStyle: Int?,
    val padding: BdsInsets,
    val firstGap: Int,
    val firstForegroundStyle: Int?,
    val cellWidth: Int,
    val switch: BdsCandidateSwitch?,
    val icons: List<BdsCandidateIcon>,
    val tips: List<BdsCandidateTip>,
    /** Every parsed section and field, retained for forward-compatible semantics. */
    val sections: Map<String, Map<String, String>>
)

data class BdsCandidateSwitch(
    val normalBackgroundStyle: Int?,
    val selectedBackgroundStyle: Int?,
    val normalFontStyle: Int?,
    val selectedFontStyle: Int?,
    val padding: BdsInsets,
    val properties: Map<String, String>
)

data class BdsCandidateIcon(
    val section: String,
    val backgroundStyle: Int?,
    val foregroundStyle: Int?,
    val animationStyle: Int?,
    val size: BdsSize?,
    val anchorType: Int?,
    val position: BdsPoint?,
    val key: String?,
    val persist: Int?,
    val stateStyle: String?,
    val properties: Map<String, String>
)

data class BdsCandidateTip(
    val section: String,
    val backgroundStyle: Int?,
    val foregroundStyle: Int?,
    val key: String?,
    val properties: Map<String, String>
)

data class BdsInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val Zero = BdsInsets(0, 0, 0, 0)
    }
}

data class BdsSize(val width: Int, val height: Int)
