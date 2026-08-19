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
import org.fcitx.fcitx5.android.data.theme.bds.BdsCandidateIcon
import org.fcitx.fcitx5.android.data.theme.bds.BdsImageRef
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
    private val skin: BdsSkin
) {
    private val layout = requireNotNull(skin.portraitCandidate)
    private val definition = layout.definition
    private val bitmaps = mutableMapOf<String, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun heightPx(viewportWidth: Int): Int =
        (layout.viewRect.height * scale(viewportWidth)).roundToInt()

    fun configureItem(ui: CandidateItemUi, position: Int, viewportWidth: Int) {
        val scale = scale(viewportWidth)
        val colorStyleId = if (position == 0) {
            definition.firstForegroundStyle ?: definition.foregroundStyle
        } else {
            definition.foregroundStyle
        }
        // FIRST_FORE is commonly a color-only override. Font metrics continue to
        // come from CAND.FORE_STYLE, exactly as observed in the Golden Sample.
        val baseStyle = definition.foregroundStyle?.let(skin.styles::get)
        val colorStyle = colorStyleId?.let(skin.styles::get)
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
        viewportWidth / skin.portraitPinyin26.designWidth.toFloat()

    private fun styleDrawable(styleId: Int, active: Boolean): Drawable =
        BdsStyleDrawable(styleId, active)

    private inner class CandidateBarView(
        private val candidates: RecyclerView,
        private val expandButton: View
    ) : ViewGroup(context) {
        private val activeIcon: BdsCandidateIcon? = definition.icons
            // Golden/reference evidence maps PERSIST=2 to the active candidate state.
            // Keep the raw value in the model so more state mappings can be added later.
            .lastOrNull { it.persist == 2 && it.stateStyle != null }
            ?: definition.icons.lastOrNull { it.persist == 2 }

        init {
            setWillNotDraw(false)
            candidates.clipToPadding = false
            addView(candidates)
            // The real BDS foreground is drawn by this surface. The transparent view
            // preserves Fcitx's existing expand/collapse callback and accessibility node.
            expandButton.alpha = 0f
            addView(expandButton)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(width, height)
            val s = scale(width)
            val padding = definition.padding
            val contentWidth = (width - ((padding.left + padding.right) * s).roundToInt())
                .coerceAtLeast(0)
            val contentHeight = (height - ((padding.top + padding.bottom) * s).roundToInt())
                .coerceAtLeast(0)
            candidates.setPadding((definition.firstGap * s).roundToInt(), 0, 0, 0)
            candidates.measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
            )
            val icon = activeIcon
            val iconRect = icon?.let { iconRect(it, width) }
            expandButton.measure(
                MeasureSpec.makeMeasureSpec(iconRect?.width()?.roundToInt() ?: 0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(iconRect?.height()?.roundToInt() ?: 0, MeasureSpec.EXACTLY)
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val s = scale(width)
            val padding = definition.padding
            val contentLeft = (padding.left * s).roundToInt()
            val contentTop = (padding.top * s).roundToInt()
            candidates.layout(
                contentLeft,
                contentTop,
                contentLeft + candidates.measuredWidth,
                contentTop + candidates.measuredHeight
            )
            val rect = activeIcon?.let { iconRect(it, width) }
            if (rect == null) {
                expandButton.layout(0, 0, 0, 0)
            } else {
                expandButton.layout(
                    rect.left.roundToInt(), rect.top.roundToInt(),
                    rect.right.roundToInt(), rect.bottom.roundToInt()
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // cand1.cnd uses the highlight image for the active candidate surface;
            // STYLE117 in the Golden Sample intentionally has no NM_IMG.
            definition.backgroundStyle?.let { drawStyle(canvas, it, fullBounds(), true, true) }
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            activeIcon?.let { icon ->
                val bounds = iconRect(icon, width)
                icon.backgroundStyle?.let { drawStyle(canvas, it, bounds, false, true) }
                icon.foregroundStyle?.let { drawStyle(canvas, it, bounds, false, false) }
                // Animation-only icons are deliberately omitted in static rendering.
                // Their parsed ANIM_STYLE chain is retained for the later animation mode.
            }
        }

        private fun fullBounds() = RectF(0f, 0f, width.toFloat(), height.toFloat())
    }

    private fun iconRect(icon: BdsCandidateIcon, viewportWidth: Int): RectF {
        val size = icon.size ?: return RectF()
        val position = icon.position
        val designWidth = skin.portraitPinyin26.designWidth.toFloat()
        val designHeight = layout.viewRect.height.toFloat()
        val anchorX = when (icon.anchorType) {
            4 -> 0f
            5 -> designWidth / 2f
            6 -> designWidth
            else -> 0f
        }
        val anchorY = when (icon.anchorType) {
            4, 5, 6 -> designHeight / 2f
            else -> 0f
        }
        val s = scale(viewportWidth)
        val left = (anchorX + (position?.x ?: 0)) * s
        val top = (anchorY + (position?.y ?: 0)) * s
        return RectF(left, top, left + size.width * s, top + size.height * s)
    }

    private fun drawStyle(
        canvas: Canvas,
        styleId: Int,
        bounds: RectF,
        pressed: Boolean,
        stretch: Boolean
    ) {
        val style = skin.styles[styleId] ?: return
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
        val image = skin.image(ref.atlas, viewportWidth) ?: return
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
