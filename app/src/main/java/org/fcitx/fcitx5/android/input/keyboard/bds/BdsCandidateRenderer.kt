/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.bds.BdsImageRef
import org.fcitx.fcitx5.android.data.theme.bds.BdsOrientation
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkin
import org.fcitx.fcitx5.android.data.theme.bds.BdsStyle
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import kotlin.math.roundToInt

/**
 * Compatibility renderer for the visual contract described by portrait cand1.cnd.
 * Candidate data and selection remain owned by Fcitx; this class only maps BDS
 * geometry/styles onto the existing horizontal candidate views.
 */
class BdsCandidateRenderer(
    private val context: Context,
    private val skin: BdsSkin,
    private val orientation: BdsOrientation
) {
    private val layout = requireNotNull(skin.candidates[orientation])
    private val resources = requireNotNull(skin.resources[orientation])
    private val definition = layout.definition
    private val bitmaps = mutableMapOf<String, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun heightPx(viewportWidth: Int): Int =
        requireNotNull(skin.candidateSurfaceHeight(orientation, viewportWidth))

    /** Persistent chrome behind every KawaiiBar state, including an empty candidate list. */
    fun barBackgroundDrawable(): Drawable? =
        definition.backgroundStyle?.let { styleDrawable(it, true) }

    fun configureItem(ui: CandidateItemUi, position: Int, viewportWidth: Int) {
        val scale = scale(viewportWidth)
        val colorStyleId = if (position == 0) {
            definition.firstForegroundStyle ?: definition.foregroundStyle
        } else {
            definition.foregroundStyle
        }
        // FIRST_FORE is commonly a color-only override. Font metrics continue to
        // come from CAND.FORE_STYLE, exactly as observed in the Golden Sample.
        val baseStyle = definition.foregroundStyle?.let(resources.styles::get)
        val colorStyle = colorStyleId?.let(resources.styles::get)
        val foreground = colorStyle?.normalColor
            ?: baseStyle?.normalColor
            ?: ui.theme.candidateTextColor
        val textSize = (baseStyle?.fontSize ?: 20f) * scale
        val horizontalPadding = (definition.cellWidth * scale / 2f).roundToInt()
        ui.root.minimumWidth = (definition.cellWidth * scale).roundToInt()
        ui.root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
        ui.applyBdsAppearance(
            textSizePx = textSize,
            foregroundColor = foreground,
            commentColor = foreground,
            itemBackground = definition.cellStyle?.let { styleDrawable(it, false) }
        )
    }

    fun createBar(horizontalView: RecyclerView, expandButton: View): ViewGroup =
        CandidateBarView(horizontalView, expandButton)

    private fun scale(viewportWidth: Int): Float =
        viewportWidth /
            resources.designWidth.toFloat()

    private fun styleDrawable(styleId: Int, active: Boolean): Drawable =
        BdsStyleDrawable(styleId, active)

    private inner class CandidateBarView(
        private val candidates: RecyclerView,
        private val expandButton: View
    ) : ViewGroup(context) {
        init {
            candidates.clipToPadding = false
            addView(candidates)
            // BDS supplies only the candidate surface. Keep Fcitx's original
            // expand/collapse icon, state, click target and accessibility node.
            expandButton.alpha = 1f
            addView(expandButton)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(width, height)
            val s = scale(width)
            val padding = definition.padding
            val buttonSize = minOf(
                height,
                (40f * context.resources.displayMetrics.density).roundToInt()
            )
            val contentLeft = ((layout.viewRect.x + padding.left) * s).roundToInt()
            val contentWidth =
                ((layout.viewRect.width - padding.left - padding.right) * s).roundToInt()
                .coerceAtMost((width - buttonSize - contentLeft).coerceAtLeast(0))
                .coerceAtLeast(0)
            val contentHeight = ((layout.viewRect.height - padding.top - padding.bottom) * s)
                .roundToInt()
                .coerceAtLeast(0)
            candidates.setPadding((definition.firstGap * s).roundToInt(), 0, 0, 0)
            candidates.measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
            )
            expandButton.measure(
                MeasureSpec.makeMeasureSpec(buttonSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(buttonSize, MeasureSpec.EXACTLY)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val s = scale(width)
            val padding = definition.padding
            val contentLeft = ((layout.viewRect.x + padding.left) * s).roundToInt()
            val contentTop = ((layout.viewRect.y + padding.top) * s).roundToInt()
            candidates.layout(
                contentLeft,
                contentTop,
                contentLeft + candidates.measuredWidth,
                contentTop + candidates.measuredHeight
            )
            val buttonTop = (height - expandButton.measuredHeight) / 2
            expandButton.layout(
                width - expandButton.measuredWidth,
                buttonTop,
                width,
                buttonTop + expandButton.measuredHeight
            )
        }
    }

    private fun drawStyle(
        canvas: Canvas,
        styleId: Int,
        bounds: RectF,
        pressed: Boolean,
        stretch: Boolean
    ) {
        val style = resources.styles[styleId] ?: return
        val color = if (pressed) style.pressedColor ?: style.normalColor else style.normalColor
        color?.let {
            val old = paint.color
            paint.color = it
            canvas.drawRect(bounds, paint)
            paint.color = old
        }
        val ref = if (pressed) style.pressedImage ?: style.normalImage else style.normalImage
        ref?.let { drawImage(canvas, it, bounds, stretch) }
    }

    private fun drawImage(canvas: Canvas, ref: BdsImageRef, bounds: RectF, stretch: Boolean) {
        val viewportWidth = context.resources.displayMetrics.widthPixels
        val image = skin.image(orientation, ref.atlas, viewportWidth) ?: return
        val tile = image.tiles[ref.tile] ?: return
        val bitmap = bitmaps.getOrPut(image.pngPath) { BitmapFactory.decodeFile(image.pngPath) } ?: return
        val destination = if (stretch) bounds else {
            val s = scale(viewportWidth)
            val width = tile.source.width * s
            val height = tile.source.height * s
            RectF(
                bounds.centerX() - width / 2f,
                bounds.centerY() - height / 2f,
                bounds.centerX() + width / 2f,
                bounds.centerY() + height / 2f
            )
        }
        val s = scale(viewportWidth)
        BdsDrawing.drawTile(canvas, bitmap, tile, destination, paint, stretch, s, s)
    }

    private inner class BdsStyleDrawable(
        private val styleId: Int,
        private val active: Boolean
    ) : Drawable() {
        private var pressed = false

        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val value = state.contains(android.R.attr.state_pressed)
            if (value == pressed) return false
            pressed = value
            invalidateSelf()
            return true
        }

        override fun draw(canvas: Canvas) {
            drawStyle(canvas, styleId, RectF(bounds), active || pressed, true)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
