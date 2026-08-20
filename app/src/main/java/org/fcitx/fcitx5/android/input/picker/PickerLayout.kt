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

    val tabsUi = PickerTabsUi(context, theme)

    val paginationUi = PickerPaginationUi(context, theme)

    private val bdsListRect = bdsLayout?.keys?.firstOrNull { key ->
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
        val rect = bdsListRect ?: return
        val width = measuredWidth
        val height = measuredHeight
        pager.measure(
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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val layout = bdsLayout ?: return
        val rect = bdsListRect ?: return
        val width = right - left
        val height = bottom - top
        val x = (rect.x * width / layout.designWidth.toFloat()).toInt()
        val y = (rect.y * height / layout.designHeight.toFloat()).toInt()
        pager.layout(x, y, x + pager.measuredWidth, y + pager.measuredHeight)
    }
}
