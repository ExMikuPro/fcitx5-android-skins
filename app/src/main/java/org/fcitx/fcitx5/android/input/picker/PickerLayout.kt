/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.view.View.MeasureSpec
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.bds.BdsLayout
import org.fcitx.fcitx5.android.data.theme.bds.BdsRect
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkin
import org.fcitx.fcitx5.android.input.keyboard.bds.BdsKeyboard
import org.fcitx.fcitx5.android.input.keyboard.*
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.view
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class PickerLayout(
    context: Context,
    theme: Theme,
    switchKey: KeyDef,
    bdsSkin: BdsSkin? = null,
    private val bdsLayout: BdsLayout? = null
) :
    ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme, switchKey: KeyDef) : BaseKeyboard(
        context, theme, listOf(
            listOf(
                LayoutSwitchKey("ABC", TextKeyboard.Name),
                PunctuationKey(","),
                switchKey,
                SpaceKey(),
                PunctuationKey("."),
                ReturnKey()
            )
        )
    ) {

        class PunctuationKey(val symbol: String) : KeyDef(
            Appearance.Text(
                displayText = symbol,
                textSize = 23f,
                percentWidth = 0.1f,
                variant = Appearance.Variant.Alternative
            ),
            setOf(
                Behavior.Press(KeyAction.FcitxKeyAction(symbol))
            )
        )

        val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

        override fun onReturnDrawableUpdate(returnDrawable: Int) {
            `return`.img.imageResource = returnDrawable
        }
    }

    var bdsFunctionActionListener: ((String) -> Boolean)? = null

    val embeddedKeyboard: BaseKeyboard = if (bdsSkin != null && bdsLayout != null) {
        BdsKeyboard(
            context, theme, bdsSkin, bdsLayout,
            renderBackdrop = true,
            functionActionHandler = { bdsFunctionActionListener?.invoke(it) == true }
        )
    } else {
        Keyboard(context, theme, switchKey)
    }

    val pager = view(::ViewPager2) { }

    private val bdsListRect = bdsLayout?.let(::bdsPickerListRect)

    val tabsUi = PickerTabsUi(
        context,
        theme,
        verticalVisibleCount = bdsLayout?.listProperties?.get("LIST_NUM")?.toIntOrNull()
    )

    val embedsTabs: Boolean = bdsListRect != null

    val paginationUi = PickerPaginationUi(context, theme)

    private val bdsPageRect = bdsLayout?.keys?.firstOrNull { key ->
        key.actions.values.any { it.raw.equals("F55", ignoreCase = true) }
    }?.viewRect

    init {
        if (bdsLayout != null) {
            add(embeddedKeyboard, lParams(matchConstraints, matchConstraints) {
                topOfParent()
                bottomOfParent()
                centerHorizontally()
            })
            // F55 is the legacy dynamic-list host. Fcitx keeps ownership of the
            // symbol pages; only its viewport is fitted into the BDS rectangle.
            addView(pager, LayoutParams(0, 0))
            bdsListRect?.let { addView(tabsUi.root, LayoutParams(0, 0)) }
            paginationUi.root.visibility = GONE
        } else {
            add(pager, lParams {
                topOfParent()
                centerHorizontally()
                above(embeddedKeyboard)
            })
            add(embeddedKeyboard, lParams {
                below(pager)
                centerHorizontally()
                bottomOfParent()
                matchConstraintPercentHeight = 0.25f
            })
            add(paginationUi.root, lParams(matchConstraints, dp(2)) {
                centerHorizontally()
                below(pager, dp(-1))
            })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val layout = bdsLayout ?: return
        val pageRect = bdsPageRect ?: return
        val width = measuredWidth
        val height = measuredHeight
        pager.measure(
            MeasureSpec.makeMeasureSpec(
                (pageRect.width * width / layout.designWidth.toFloat()).toInt(),
                MeasureSpec.EXACTLY
            ),
            MeasureSpec.makeMeasureSpec(
                (pageRect.height * height / layout.designHeight.toFloat()).toInt(),
                MeasureSpec.EXACTLY
            )
        )
        bdsListRect?.let { rect ->
            tabsUi.root.measure(
                MeasureSpec.makeMeasureSpec(
                    (rect.width * width / layout.designWidth.toFloat()).toInt(),
                    MeasureSpec.EXACTLY
                ),
                MeasureSpec.makeMeasureSpec(
                    (rect.height * height / layout.designHeight.toFloat()).toInt(),
                    MeasureSpec.EXACTLY
                )
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val layout = bdsLayout ?: return
        val pageRect = bdsPageRect ?: return
        val width = right - left
        val height = bottom - top
        val x = (pageRect.x * width / layout.designWidth.toFloat()).toInt()
        val y = (pageRect.y * height / layout.designHeight.toFloat()).toInt()
        pager.layout(x, y, x + pager.measuredWidth, y + pager.measuredHeight)
        bdsListRect?.let { rect ->
            val listX = (rect.x * width / layout.designWidth.toFloat()).toInt()
            val listY = (rect.y * height / layout.designHeight.toFloat()).toInt()
            tabsUi.root.layout(
                listX,
                listY,
                listX + tabsUi.root.measuredWidth,
                listY + tabsUi.root.measuredHeight
            )
        }
    }
}

internal fun bdsPickerListRect(layout: BdsLayout): BdsRect? {
    fun pair(name: String): Pair<Int, Int>? {
        val values = layout.listProperties[name]?.split(',')?.map { it.trim().toIntOrNull() }
            ?: return null
        return if (values.size >= 2 && values[0] != null && values[1] != null) {
            values[0]!! to values[1]!!
        } else null
    }
    val position = pair("POS") ?: return null
    val cellSize = pair("CELL_SIZE") ?: return null
    val count = layout.listProperties["LIST_NUM"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return BdsRect(position.first, position.second, cellSize.first, cellSize.second * count)
}
