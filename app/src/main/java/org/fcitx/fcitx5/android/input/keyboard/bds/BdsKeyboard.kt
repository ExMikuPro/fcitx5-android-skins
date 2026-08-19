/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.bds.BdsAction
import org.fcitx.fcitx5.android.data.theme.bds.BdsDirection
import org.fcitx.fcitx5.android.data.theme.bds.BdsImage
import org.fcitx.fcitx5.android.data.theme.bds.BdsImageRef
import org.fcitx.fcitx5.android.data.theme.bds.BdsKey
import org.fcitx.fcitx5.android.data.theme.bds.BdsRect
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkin
import org.fcitx.fcitx5.android.data.theme.bds.BdsStyle
import org.fcitx.fcitx5.android.data.theme.bds.BdsTile
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import timber.log.Timber
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class BdsKeyboard(context: Context, theme: Theme, private val skin: BdsSkin) :
    BaseKeyboard(context, theme, emptyList()) {

    private enum class CapsState { None, Once, Lock }
    private var capsState = CapsState.None

    private val surface = BdsKeyboardSurface(context, skin) { action ->
        mapAction(action)?.let { onAction(it) }
    }

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttach() {
        capsState = CapsState.None
        surface.caps = false
    }

    override fun onReturnActionUpdate(action: ReturnKeyAction) {
        surface.returnAction = action
    }

    override fun onAction(action: KeyAction, source: org.fcitx.fcitx5.android.input.keyboard.KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.CapsAction -> {
                capsState = when {
                    action.lock && capsState == CapsState.Lock -> CapsState.None
                    action.lock -> CapsState.Lock
                    capsState == CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
                surface.caps = capsState != CapsState.None
                return
            }
            is KeyAction.FcitxKeyAction -> if (action.act.length == 1 && action.act[0].isLetter()) {
                transformed = when (capsState) {
                    CapsState.None -> action.copy(act = action.act.lowercase())
                    CapsState.Once -> action.copy(
                        act = action.act.uppercase(),
                        states = KeyStates(KeyState.Virtual, KeyState.Shift)
                    ).also {
                        capsState = CapsState.None
                        surface.caps = false
                    }
                    CapsState.Lock -> action.copy(
                        act = action.act.uppercase(),
                        states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                    )
                }
            }
            else -> Unit
        }
        super.onAction(transformed, source)
    }

    private fun mapAction(action: BdsAction): KeyAction? {
        val raw = action.raw
        if (!raw.startsWith('F', ignoreCase = true)) {
            return raw.takeIf { it.isNotEmpty() }?.let { KeyAction.FcitxKeyAction(it) }
        }
        return when (raw.uppercase()) {
            "F1" -> KeyAction.PickerSwitchAction(PickerWindow.Key.Symbol)
            "F6" -> KeyAction.LayoutSwitchAction(NumberKeyboard.Name)
            "F11" -> KeyAction.CapsAction(false)
            "F16" -> KeyAction.LangSwitchAction
            "F36" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
            "F38" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space))
            "F39" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))
            "F51" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Left))
            "F52" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Right))
            else -> null.also { Timber.d("BDS: unsupported action $raw") }
        }
    }
}

private class BdsKeyboardSurface(
    context: Context,
    private val skin: BdsSkin,
    private val onAction: (BdsAction) -> Unit
) : ViewGroup(context) {
    private val layout = skin.portraitPinyin26
    private val bitmaps = mutableMapOf<String, Bitmap?>()
    private var transformer = BdsCoordinateTransformer(
        layout.designWidth, layout.designHeight, 1, 1
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var loggedResourceBucket: Int? = null

    var caps: Boolean = false
        set(value) {
            field = value
            childrenViews.forEach { it.invalidate() }
        }

    var returnAction: ReturnKeyAction = ReturnKeyAction.Enter
        set(value) {
            if (field == value) return
            field = value
            childrenViews.forEach { child ->
                child.returnAction = value
                child.invalidate()
            }
        }

    private val decorations = layout.decorations.map { key ->
        BdsKeyView(context, key, skin, bitmaps, onAction, isDecoration = true)
    }
    private val childrenViews = layout.keys.map { key ->
        BdsKeyView(context, key, skin, bitmaps, onAction, isDecoration = false)
    }

    init {
        setWillNotDraw(false)
        decorations.forEach(::addView)
        childrenViews.forEach(::addView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        decorations.plus(childrenViews).forEach {
            val source = it.layoutRect
            it.measure(
                MeasureSpec.makeMeasureSpec((source.width * width / layout.designWidth.toFloat()).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((source.height * height / layout.designHeight.toFloat()).roundToInt(), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        transformer = BdsCoordinateTransformer(layout.designWidth, layout.designHeight, width, height)
        val bucket = skin.selectResourceBucket(width)
        if (bucket != loggedResourceBucket) {
            loggedResourceBucket = bucket
            val metrics = resources.displayMetrics
            Timber.d(
                "BDS: device=${metrics.widthPixels}x${metrics.heightPixels}/${metrics.densityDpi}dpi " +
                    "panel=${layout.designWidth}x${layout.designHeight} viewport=${width}x${height} " +
                    "selected resource bucket=${bucket ?: "base"} " +
                    "scale=${transformer.scaleX},${transformer.scaleY}"
            )
        }
        decorations.plus(childrenViews).forEach {
            it.transformer = transformer
            val rect = transformer.rect(it.layoutRect)
            it.layout(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layout.backgroundStyle?.let { styleId ->
            skin.styles[styleId]?.let { style ->
                drawStyle(canvas, style, RectF(0f, 0f, width.toFloat(), height.toFloat()), false)
            }
        }
    }

    private fun drawStyle(canvas: Canvas, style: BdsStyle, dest: RectF, pressed: Boolean) {
        val color = if (pressed) style.pressedColor ?: style.normalColor else style.normalColor
        color?.let { canvas.drawColor(it) }
        val ref = if (pressed) style.pressedImage ?: style.normalImage else style.normalImage
        ref?.let { drawImage(canvas, it, dest, true) }
    }

    private fun drawImage(canvas: Canvas, ref: BdsImageRef, dest: RectF, stretch: Boolean) {
        val image = skin.image(ref.atlas, transformer.viewportWidth) ?: return
        val bitmap = bitmaps.getOrPut(image.pngPath) { BitmapFactory.decodeFile(image.pngPath) } ?: return
        val tile = image.tiles[ref.tile] ?: return
        BdsDrawing.drawTile(
            canvas, bitmap, tile, dest, paint, stretch,
            transformer.scaleX, transformer.scaleY
        )
    }
}

private class BdsKeyView(
    context: Context,
    private val key: BdsKey,
    private val skin: BdsSkin,
    private val bitmaps: MutableMap<String, Bitmap?>,
    private val onAction: (BdsAction) -> Unit,
    isDecoration: Boolean
) : View(context) {
    val layoutRect: BdsRect = if (isDecoration) key.viewRect else key.touchRect ?: key.viewRect
    lateinit var transformer: BdsCoordinateTransformer
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var downX = 0f
    private var downY = 0f

    var returnAction: ReturnKeyAction = ReturnKeyAction.Enter

    init {
        isClickable = !isDecoration
        if (!isDecoration) {
            setOnClickListener { key.actions[BdsDirection.Center]?.let(onAction) }
            setOnLongClickListener {
                key.actions[BdsDirection.Hold]?.let(onAction) != null
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            val dy = event.y - downY
            val threshold = minOf(width, height) * 0.22f
            val direction = when {
                kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx > threshold -> BdsDirection.Right
                kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx < -threshold -> BdsDirection.Left
                dy > threshold -> BdsDirection.Down
                dy < -threshold -> BdsDirection.Up
                else -> null
            }
            direction?.let { key.actions[it] }?.let {
                isPressed = false
                onAction(it)
                invalidate()
                return true
            }
        }
        val handled = super.onTouchEvent(event)
        invalidate()
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewRect = transformer.rect(key.viewRect)
        val layout = transformer.rect(layoutRect)
        val local = RectF(
            viewRect.left - layout.left,
            viewRect.top - layout.top,
            viewRect.right - layout.left,
            viewRect.bottom - layout.top
        )
        val variant = BdsKeyStateResolver.resolve(
            key, returnAction, skin.portraitPinyin26.variants
        )
        val backgroundStyle = variant?.backgroundStyle ?: key.backgroundStyle
        val foregroundStyles = variant?.foregroundStyles ?: key.foregroundStyles
        val positionTypes = variant?.positionTypes ?: key.positionTypes
        backgroundStyle?.let { drawStyle(canvas, it, local, isPressed, true, null) }
        foregroundStyles.forEachIndexed { index, styleId ->
            val offset = skin.portraitPinyin26.offsets[positionTypes.getOrNull(index)]
            drawStyle(canvas, styleId, local, isPressed, false, offset)
        }
    }

    private fun drawStyle(
        canvas: Canvas,
        styleId: Int,
        bounds: RectF,
        pressed: Boolean,
        stretch: Boolean,
        offset: org.fcitx.fcitx5.android.data.theme.bds.BdsPoint?
    ) {
        val style = skin.styles[styleId] ?: return
        val ref = if (pressed) style.pressedImage ?: style.normalImage else style.normalImage
        if (ref != null) {
            val image = skin.image(ref.atlas, transformer.viewportWidth) ?: return
            val tile = image.tiles[ref.tile] ?: return
            val bitmap = bitmaps.getOrPut(image.pngPath) { BitmapFactory.decodeFile(image.pngPath) } ?: return
            val dest = if (stretch) bounds else {
                val (dx, dy) = transformer.point(offset)
                val width = transformer.x(tile.source.width)
                val height = transformer.y(tile.source.height)
                RectF(
                    bounds.centerX() - width / 2 + dx,
                    bounds.centerY() - height / 2 + dy,
                    bounds.centerX() + width / 2 + dx,
                    bounds.centerY() + height / 2 + dy
                )
            }
            BdsDrawing.drawTile(
                canvas, bitmap, tile, dest, paint, stretch,
                transformer.scaleX, transformer.scaleY
            )
        } else {
            val text = style.text ?: return
            val (dx, dy) = transformer.point(offset)
            paint.color = (if (pressed) style.pressedColor else null) ?: style.normalColor ?: Color.BLACK
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = transformer.y((style.fontSize ?: 32f).roundToInt())
            paint.typeface = if ((style.fontWeight ?: 400) >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val baseline = bounds.centerY() - (paint.ascent() + paint.descent()) / 2
            canvas.drawText(text, bounds.centerX() + dx, baseline + dy, paint)
        }
    }
}

internal object BdsDrawing {
    fun drawTile(
        canvas: Canvas,
        bitmap: Bitmap,
        tile: BdsTile,
        dest: RectF,
        paint: Paint,
        stretch: Boolean,
        scaleX: Float,
        scaleY: Float
    ) {
        val source = tile.source
        val inner = tile.inner
        if (!stretch || inner == null) {
            canvas.drawBitmap(
                bitmap,
                Rect(source.x, source.y, source.x + source.width, source.y + source.height),
                dest,
                paint
            )
            return
        }
        val innerLeft = (inner.x - source.x).coerceIn(0, source.width)
        val innerTop = (inner.y - source.y).coerceIn(0, source.height)
        val innerRight = (innerLeft + inner.width).coerceIn(innerLeft, source.width)
        val innerBottom = (innerTop + inner.height).coerceIn(innerTop, source.height)
        val fixedLeft = innerLeft * scaleX
        val fixedRight = (source.width - innerRight) * scaleX
        val fixedTop = innerTop * scaleY
        val fixedBottom = (source.height - innerBottom) * scaleY
        val dx = floatArrayOf(dest.left, dest.left + fixedLeft, dest.right - fixedRight, dest.right)
        val dy = floatArrayOf(dest.top, dest.top + fixedTop, dest.bottom - fixedBottom, dest.bottom)
        val sx = intArrayOf(source.x, source.x + innerLeft, source.x + innerRight, source.x + source.width)
        val sy = intArrayOf(source.y, source.y + innerTop, source.y + innerBottom, source.y + source.height)
        for (y in 0..2) for (x in 0..2) {
            if (sx[x + 1] > sx[x] && sy[y + 1] > sy[y] && dx[x + 1] > dx[x] && dy[y + 1] > dy[y]) {
                canvas.drawBitmap(
                    bitmap,
                    Rect(sx[x], sy[y], sx[x + 1], sy[y + 1]),
                    RectF(dx[x], dy[y], dx[x + 1], dy[y + 1]),
                    paint
                )
            }
        }
    }
}
