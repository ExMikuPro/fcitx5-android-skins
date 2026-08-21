/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.bds.BdsIconFamily
import org.fcitx.fcitx5.android.data.theme.bds.BdsLegacyMenuIcon
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkinManager
import org.fcitx.fcitx5.android.data.theme.bds.BdsToolbarIconMapping
import org.fcitx.fcitx5.android.data.theme.bds.BdsToolbarIconProvider
import splitties.dimensions.dp

/** Debug-only visual index for manually verifying legacy BDS icon semantics. */
class BdsIconPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val skin = BdsSkinManager.skinForTheme(ThemeManager.activeTheme.name)
        BdsToolbarIconProvider.activate(skin)
        if (skin == null) {
            setContentView(TextView(this).apply {
                text = "The active theme is not a BDS skin."
                setPadding(dp(24), dp(24), dp(24), dp(24))
            })
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        content.addView(section("Toolbar", BdsIconFamily.Toolbar))
        content.addView(section("Input mode (reserved API)", BdsIconFamily.InputMode))
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun section(title: String, family: BdsIconFamily): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 20f
                setPadding(dp(8), dp(12), dp(8), dp(8))
            })
            addView(GridLayout(context).apply {
                columnCount = 4
                BdsToolbarIconProvider.availableIconIds(family).sorted().forEach { iconId ->
                    addView(iconCell(family, iconId))
                }
            })
        }

    private fun iconCell(family: BdsIconFamily, iconId: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setBackgroundColor(Color.argb(20, 127, 127, 127))
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(BdsToolbarIconProvider.drawable(resources, family, iconId))
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            val mapped = if (family == BdsIconFamily.Toolbar) {
                BdsToolbarIconMapping.actionsFor(iconId).joinToString { it.name }
            } else ""
            val baiduFunction = if (family == BdsIconFamily.Toolbar) {
                BdsLegacyMenuIcon.fromId(iconId)?.baiduFunction.orEmpty()
            } else ""
            addView(TextView(context).apply {
                text = listOf("#$iconId", baiduFunction, mapped)
                    .filter(String::isNotBlank)
                    .joinToString("\n")
                textSize = 11f
                gravity = Gravity.CENTER
            })
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
        }
}
