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
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.bds.BdsAction
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimation
import org.fcitx.fcitx5.android.data.theme.bds.BdsDirection
import org.fcitx.fcitx5.android.data.theme.bds.BdsImage
import org.fcitx.fcitx5.android.data.theme.bds.BdsImageRef
import org.fcitx.fcitx5.android.data.theme.bds.BdsKey
import org.fcitx.fcitx5.android.data.theme.bds.BdsLayout
import org.fcitx.fcitx5.android.data.theme.bds.BdsOrientation
import org.fcitx.fcitx5.android.data.theme.bds.BdsRect
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkin
import org.fcitx.fcitx5.android.data.theme.bds.BdsStyle
import org.fcitx.fcitx5.android.data.theme.bds.BdsTile
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAction
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import timber.log.Timber
import kotlin.math.roundToInt

private fun MutableMap<String, Bitmap?>.cachedBitmap(path: String): Bitmap? {
    if (!containsKey(path)) this[path] = BitmapFactory.decodeFile(path)
    return this[path]
}

@SuppressLint("ViewConstructor")
class BdsKeyboard(
    context: Context,
    theme: Theme,
    private val skin: BdsSkin,
    private val layout: BdsLayout,
    renderBackdrop: Boolean = false,
    private val functionActionHandler: ((String) -> Boolean)? = null
) :
    BaseKeyboard(context, theme, emptyList()) {

    private enum class CapsState { None, Once, Lock }
    private var capsState = CapsState.None

    private val surface = BdsKeyboardSurface(
        context,
        skin,
        layout,
        onAction = { action ->
            if (functionActionHandler?.invoke(action.raw) != true) {
                mapAction(action)?.let { onAction(it) }
            }
        },
        onShowCharacterPopup = { viewId, label, bounds ->
            onPopupAction(
                PopupAction.ShowKeyboardAction(
                    viewId,
                    KeyDef.Popup.Keyboard.Preset(
                        label,
                        avoidTriggerOverlap = true
                    ),
                    bounds
                )
            )
        },
        onPopupChangeFocus = ::changePopupFocus,
        onPopupTrigger = ::triggerPopup,
        renderBackdrop = renderBackdrop,
        animationsEnabled = layout.id.orientation == BdsOrientation.Portrait &&
            layout.id.name == "py_26"
    )

    init {
        // Foreground PRESS_ANIM layers may leave the panel (for example, notes
        // emitted upward by the top row). Keep the BDS surface's overflow alive
        // until InputView can composite it over the candidate background.
        clipChildren = false
        clipToPadding = false
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

    private fun mapAction(action: BdsAction): KeyAction? = mapBdsAction(action)

    private fun changePopupFocus(viewId: Int, x: Float, y: Float): Boolean {
        val action = PopupAction.ChangeFocusAction(viewId, x, y)
        onPopupAction(action)
        return action.outResult
    }

    private fun triggerPopup(viewId: Int): Boolean {
        val action = PopupAction.TriggerAction(viewId)
        onPopupAction(action)
        val selected = action.outAction ?: return false
        onAction(selected, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }
}

/** BDS function actions use the case-sensitive form F<number>; lowercase f is text. */
internal fun mapBdsAction(action: BdsAction): KeyAction? {
    val raw = action.raw
    val isFunctionAction = raw.length > 1 && raw[0] == 'F' &&
        raw.substring(1).all(Char::isDigit)
    if (!isFunctionAction) {
        return raw.takeIf { it.isNotEmpty() }?.let { KeyAction.FcitxKeyAction(it) }
    }
    return when (raw) {
        "F1" -> KeyAction.PickerSwitchAction(PickerWindow.Key.Symbol)
        "F4" -> KeyAction.LayoutSwitchAction(TextKeyboard.Name)
        "F6" -> KeyAction.LayoutSwitchAction(NumberKeyboard.Name)
        "F7" -> KeyAction.PickerSwitchAction(PickerWindow.Key.Emoji)
        "F10" -> KeyAction.CapsAction(false)
        "F11" -> KeyAction.CapsAction(false)
        "F15" -> KeyAction.LangSwitchAction
        "F16" -> KeyAction.LangSwitchAction
        "F36" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
        "F38" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space))
        "F39" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))
        "F51" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Left))
        "F52" -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Right))
        "F72" -> KeyAction.SpaceLongPressAction
        else -> null.also { Timber.d("BDS: unsupported action $raw") }
    }
}

internal fun bdsPopupPresetLabel(action: BdsAction?, caps: Boolean): String? {
    val raw = action?.raw ?: return null
    if (raw.length != 1) return null
    val label = if (caps && raw[0].isLetter()) raw.uppercase() else raw
    return label.takeIf(PopupPreset::containsKey)
}

internal fun createBdsPanelBackdrop(
    context: Context,
    skin: BdsSkin,
    layout: BdsLayout
): View =
    BdsKeyboardSurface(
        context = context,
        skin = skin,
        layout = layout,
        onAction = {},
        onShowCharacterPopup = { _, _, _ -> },
        onPopupChangeFocus = { _, _, _ -> false },
        onPopupTrigger = { _ -> false },
        renderKeys = false,
        animationsEnabled = false
    )

private class BdsKeyboardSurface(
    context: Context,
    private val skin: BdsSkin,
    private val layout: BdsLayout,
    private val onAction: (BdsAction) -> Unit,
    private val onShowCharacterPopup: (Int, String, Rect) -> Unit,
    private val onPopupChangeFocus: (Int, Float, Float) -> Boolean,
    private val onPopupTrigger: (Int) -> Boolean,
    private val renderKeys: Boolean = true,
    private val renderBackdrop: Boolean = true,
    private val animationsEnabled: Boolean = true
) : ViewGroup(context) {
    private val bdsResources = requireNotNull(skin.resources(layout)) {
        "Missing BDS resources for ${layout.id}"
    }
    private val bitmaps = mutableMapOf<String, Bitmap?>()
    private var transformer = BdsCoordinateTransformer(
        layout.designWidth, layout.designHeight, 1, 1
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var loggedResourceBucket: Int? = null
    private var panelEmitter: BdsParticleEmitterInstance? = null

    var caps: Boolean = false
        set(value) {
            field = value
            childrenViews.forEach {
                it.caps = value
                it.invalidate()
            }
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

    private val decorations = if (renderBackdrop) layout.decorations.map { key ->
        BdsKeyView(
            context, key, skin, layout, bitmaps, onAction, onShowCharacterPopup,
            onPopupChangeFocus, onPopupTrigger, animationsEnabled, isDecoration = true
        )
    } else emptyList()
    private val childrenViews = layout.keys.map { key ->
        if (renderKeys) {
            BdsKeyView(
                context, key, skin, layout, bitmaps, onAction, onShowCharacterPopup,
                onPopupChangeFocus, onPopupTrigger, animationsEnabled, isDecoration = false
            )
        } else null
    }.filterNotNull()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
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
        val bucket = skin.selectResourceBucket(layout.id.orientation, width)
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
        if (renderBackdrop) {
            layout.backgroundStyle?.let { styleId ->
                bdsResources.styles[styleId]?.let { style ->
                    drawStyle(canvas, style, RectF(0f, 0f, width.toFloat(), height.toFloat()), false)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animationsEnabled ||
            BdsRenderEnvironment.configuration.mode != BdsRenderEnvironment.Mode.Animation
        ) return
        val animationStyle = layout.animationStyle?.let(bdsResources.animationStyles::get) ?: return
        val animationId = animationStyle.showAnimationId ?: return
        val emitter = bdsResources.animations[animationId] as? BdsAnimation.ParticleEmitter ?: run {
            Timber.w("BDS: panel SHOW_ANIM=$animationId is not a particle emitter")
            return
        }
        if (emitter.category != 3 || emitter.location != 1 || emitter.emitType != 0) {
            Timber.w(
                "BDS: ANIM$animationId uses unverified particle semantics " +
                    "CATEGORY=${emitter.category} LOCATION=${emitter.location} " +
                    "EMIT_TYPE=${emitter.emitType}; using Golden-compatible fallback"
            )
        }
        if (layout.animationLevel != null &&
            layout.animationLevel != 0 && layout.animationLevel != 1
        ) {
            Timber.w(
                "BDS: unverified ANIM_LEVEL=${layout.animationLevel}; rendering particles below keys"
            )
        }
        panelEmitter = BdsParticleEmitterInstance(
            emitter,
            BdsRenderEnvironment.randomFor(animationStyle.styleId),
            System.nanoTime()
        )
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        panelEmitter = null
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (layout.animationLevel != 1) drawPanelParticles(canvas)
        super.dispatchDraw(canvas)
        if (layout.animationLevel == 1) drawPanelParticles(canvas)
    }

    private fun drawPanelParticles(canvas: Canvas) {
        val emitter = panelEmitter ?: return
        val frameTimeNanos = System.nanoTime()
        val oldAlpha = paint.alpha
        val active = emitter.forEachFrame(
            frameTimeNanos, width.toFloat(), height.toFloat()
        ) { particle ->
            if (particle.alpha <= 0f || particle.scale <= 0f) return@forEachFrame
            val style = bdsResources.styles[particle.styleId] ?: return@forEachFrame
            val ref = style.normalImage ?: style.pressedImage ?: return@forEachFrame
            val image = skin.image(layout, ref.atlas, transformer.viewportWidth) ?: return@forEachFrame
            val tile = image.tiles[ref.tile] ?: return@forEachFrame
            val bitmap = bitmaps.cachedBitmap(image.pngPath) ?: return@forEachFrame
            val particleWidth = transformer.x(tile.source.width) * particle.scale
            val particleHeight = transformer.y(tile.source.height) * particle.scale
            val destination = RectF(
                particle.x - particleWidth / 2f,
                particle.y - particleHeight / 2f,
                particle.x + particleWidth / 2f,
                particle.y + particleHeight / 2f
            )
            val save = canvas.save()
            canvas.rotate(particle.rotation, particle.x, particle.y)
            paint.alpha = (particle.alpha * 255f).roundToInt()
            BdsDrawing.drawTile(
                canvas, bitmap, tile, destination, paint, false,
                transformer.scaleX, transformer.scaleY
            )
            canvas.restoreToCount(save)
        }
        paint.alpha = oldAlpha
        if (active) postInvalidateOnAnimation() else panelEmitter = null
    }

    private fun drawStyle(canvas: Canvas, style: BdsStyle, dest: RectF, pressed: Boolean) {
        val color = if (pressed) style.pressedColor ?: style.normalColor else style.normalColor
        color?.let { canvas.drawColor(it) }
        val ref = if (pressed) style.pressedImage ?: style.normalImage else style.normalImage
        ref?.let { drawImage(canvas, it, dest, true) }
    }

    private fun drawImage(canvas: Canvas, ref: BdsImageRef, dest: RectF, stretch: Boolean) {
        val image = skin.image(layout, ref.atlas, transformer.viewportWidth) ?: return
        val bitmap = bitmaps.cachedBitmap(image.pngPath) ?: return
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
    private val layout: BdsLayout,
    private val bitmaps: MutableMap<String, Bitmap?>,
    private val onAction: (BdsAction) -> Unit,
    private val onShowCharacterPopup: (Int, String, Rect) -> Unit,
    private val onPopupChangeFocus: (Int, Float, Float) -> Boolean,
    private val onPopupTrigger: (Int) -> Boolean,
    private val animationsEnabled: Boolean,
    isDecoration: Boolean
) : CustomGestureView(context) {
    private val bdsResources = requireNotNull(skin.resources(layout)) {
        "Missing BDS resources for ${layout.id}"
    }
    val layoutRect: BdsRect = if (isDecoration) key.viewRect else key.touchRect ?: key.viewRect
    lateinit var transformer: BdsCoordinateTransformer
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var downX = 0f
    private var downY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var triggerCount = 0
    private var wholeAnimation: BdsAnimationInstance? = null
    private var backgroundAnimation: BdsAnimationInstance? = null
    private var foregroundAnimations: MutableList<BdsAnimationInstance?> = mutableListOf()
    private val swipeSymbolDirection by AppPrefs.getInstance().keyboard.swipeSymbolDirection

    var returnAction: ReturnKeyAction = ReturnKeyAction.Enter
    var caps: Boolean = false

    init {
        setWillNotDraw(false)
        isClickable = !isDecoration
        if (!isDecoration) {
            id = View.generateViewId()
            setOnClickListener { key.actions[BdsDirection.Center]?.let(onAction) }
            val holdAction = key.actions[BdsDirection.Hold]
            val hasPopupPreset = bdsPopupPresetLabel(
                key.actions[BdsDirection.Center], false
            ) != null || bdsPopupPresetLabel(key.actions[BdsDirection.Center], true) != null
            if (holdAction != null || hasPopupPreset) {
                setOnLongClickListener {
                    val label = bdsPopupPresetLabel(
                        key.actions[BdsDirection.Center], caps
                    )
                    if (label != null) {
                        onShowCharacterPopup(id, label, popupBounds())
                        // Match BaseKeyboard: keep receiving move/up for selection.
                        false
                    } else if (holdAction != null) {
                        onAction(holdAction)
                        true
                    } else {
                        false
                    }
                }
            }
            swipeEnabled = true
            onGestureListener = CustomGestureView.OnGestureListener { _, event ->
                when (event.type) {
                    CustomGestureView.GestureType.Move ->
                        onPopupChangeFocus(id, event.x, event.y)
                    CustomGestureView.GestureType.Up -> {
                        if (onPopupTrigger(id)) {
                            true
                        } else if (!event.consumed) {
                            val threshold = minOf(width, height) * 0.22f
                            val direction = resolveBdsSwipeDirection(
                                currentX - downX,
                                currentY - downY,
                                threshold,
                                swipeSymbolDirection
                            )
                            direction?.let(key.actions::get)?.let {
                                onAction(it)
                                true
                            } ?: false
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            currentX = event.x
            currentY = event.y
            triggerPressAnimations()
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_UP
        ) {
            currentX = event.x
            currentY = event.y
        }
        val handled = super.onTouchEvent(event)
        invalidate()
        return handled
    }

    private fun popupBounds(): Rect {
        val location = IntArray(2)
        getLocationInWindow(location)
        val viewRect = transformer.rect(key.viewRect)
        val layout = transformer.rect(layoutRect)
        val left = location[0] + viewRect.left - layout.left
        val top = location[1] + viewRect.top - layout.top
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            (left + viewRect.width()).roundToInt(),
            (top + viewRect.height()).roundToInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewRect = transformer.rect(key.viewRect)
        val layoutBounds = transformer.rect(layoutRect)
        val local = RectF(
            viewRect.left - layoutBounds.left,
            viewRect.top - layoutBounds.top,
            viewRect.right - layoutBounds.left,
            viewRect.bottom - layoutBounds.top
        )
        val variant = BdsKeyStateResolver.resolve(
            key, returnAction, layout.variants, caps
        )
        val backgroundStyle = variant?.backgroundStyle ?: key.backgroundStyle
        val foregroundStyles = variant?.foregroundStyles ?: key.foregroundStyles
        val positionTypes = variant?.positionTypes ?: key.positionTypes
        val frameTimeNanos = System.nanoTime()
        var needsNextFrame = false
        fun frame(instance: BdsAnimationInstance?): BdsAnimationFrame? = instance?.frameAt(frameTimeNanos)
            ?.also { needsNextFrame = needsNextFrame || it.active }
        val wholeFrame = frame(wholeAnimation)
        val wholeAlpha = wholeFrame?.transform?.alpha ?: 1f
        val animationPressed = wholeFrame?.active == true
        val wholeSave = canvas.save()
        wholeFrame?.let { applyTransform(canvas, local, it.transform) }
        val backgroundFrame = frame(backgroundAnimation)
        backgroundStyle?.let {
            val save = canvas.save()
            backgroundFrame?.let { value -> applyTransform(canvas, local, value.transform) }
            drawStyle(
                canvas, it, local,
                isPressed || animationPressed || backgroundFrame?.active == true,
                true, null, (backgroundFrame?.transform?.alpha ?: 1f) * wholeAlpha
            )
            canvas.restoreToCount(save)
        }
        foregroundStyles.forEachIndexed { index, styleId ->
            val offset = layout.offsets[positionTypes.getOrNull(index)]
            val foregroundFrame = frame(foregroundAnimations.getOrNull(index))
            val save = canvas.save()
            foregroundFrame?.let {
                applyTransform(canvas, styleBounds(styleId, local, offset), it.transform)
            }
            drawStyle(
                canvas, styleId, local,
                isPressed || animationPressed || foregroundFrame?.active == true,
                false, offset, (foregroundFrame?.transform?.alpha ?: 1f) * wholeAlpha
            )
            canvas.restoreToCount(save)
        }
        canvas.restoreToCount(wholeSave)
        if (needsNextFrame) postInvalidateOnAnimation()
    }

    private fun triggerPressAnimations() {
        if (!animationsEnabled ||
            BdsRenderEnvironment.configuration.mode != BdsRenderEnvironment.Mode.Animation
        ) return
        val now = System.nanoTime()
        val random = BdsRenderEnvironment.randomFor(key.section.hashCode() xor triggerCount++)
        fun create(styleId: Int?): BdsAnimationInstance? {
            val animationId = styleId?.let(bdsResources.animationStyles::get)?.pressAnimationId ?: return null
            return BdsAnimationInstance.create(animationId, bdsResources.animations, random, now)
        }
        val variant = BdsKeyStateResolver.resolve(
            key, returnAction, layout.variants, caps
        )
        wholeAnimation = create(variant?.animationStyle ?: key.animationStyle)
        backgroundAnimation = create(
            variant?.backgroundAnimationStyle ?: key.backgroundAnimationStyle
        )
        val animationStyles = variant?.foregroundAnimationStyles
            ?.takeIf { it.isNotEmpty() }
            ?: key.foregroundAnimationStyles
        foregroundAnimations = animationStyles.map(::create).toMutableList()
        postInvalidateOnAnimation()
    }

    private fun applyTransform(canvas: Canvas, bounds: RectF, transform: BdsTransform) {
        val pivotX = bounds.left + bounds.width() * transform.pivotXPercent / 100f
        val pivotY = bounds.top + bounds.height() * transform.pivotYPercent / 100f
        canvas.translate(
            transformer.x(transform.translationX),
            transformer.y(transform.translationY)
        )
        canvas.rotate(transform.rotation, pivotX, pivotY)
        canvas.scale(transform.scaleX, transform.scaleY, pivotX, pivotY)
    }

    private fun styleBounds(
        styleId: Int,
        bounds: RectF,
        offset: org.fcitx.fcitx5.android.data.theme.bds.BdsPoint?
    ): RectF {
        val style = bdsResources.styles[styleId] ?: return bounds
        val ref = style.pressedImage ?: style.normalImage ?: return bounds
        val image = skin.image(layout, ref.atlas, transformer.viewportWidth) ?: return bounds
        val tile = image.tiles[ref.tile] ?: return bounds
        val (dx, dy) = transformer.point(offset)
        val width = transformer.x(tile.source.width)
        val height = transformer.y(tile.source.height)
        return RectF(
            bounds.centerX() - width / 2 + dx,
            bounds.centerY() - height / 2 + dy,
            bounds.centerX() + width / 2 + dx,
            bounds.centerY() + height / 2 + dy
        )
    }

    private fun drawStyle(
        canvas: Canvas,
        styleId: Int,
        bounds: RectF,
        pressed: Boolean,
        stretch: Boolean,
        offset: org.fcitx.fcitx5.android.data.theme.bds.BdsPoint?,
        alpha: Float
    ) {
        val style = bdsResources.styles[styleId] ?: return
        val oldAlpha = paint.alpha
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val ref = if (pressed) style.pressedImage ?: style.normalImage else style.normalImage
        if (ref != null) {
            val image = skin.image(layout, ref.atlas, transformer.viewportWidth)
            val tile = image?.tiles?.get(ref.tile)
            val bitmap = image?.let { bitmaps.cachedBitmap(it.pngPath) }
            if (image == null || tile == null || bitmap == null) {
                paint.alpha = oldAlpha
                return
            }
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
            style.text?.let { text ->
                val (dx, dy) = transformer.point(offset)
                paint.color = (if (pressed) style.pressedColor else null)
                    ?: style.normalColor ?: Color.BLACK
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = transformer.y((style.fontSize ?: 32f).roundToInt())
                paint.typeface = if ((style.fontWeight ?: 400) >= 600) {
                    Typeface.DEFAULT_BOLD
                } else {
                    Typeface.DEFAULT
                }
                val baseline = bounds.centerY() - (paint.ascent() + paint.descent()) / 2
                canvas.drawText(text, bounds.centerX() + dx, baseline + dy, paint)
            }
        }
        paint.alpha = oldAlpha
    }
}

/**
 * BDS UP is the action displayed above a key. Its physical vertical gesture follows
 * Fcitx's swipe-symbol preference, while explicit horizontal BDS actions stay directional.
 */
internal fun resolveBdsSwipeDirection(
    dx: Float,
    dy: Float,
    threshold: Float,
    symbolDirection: SwipeSymbolDirection
): BdsDirection? {
    if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
        return when {
            dx > threshold -> BdsDirection.Right
            dx < -threshold -> BdsDirection.Left
            else -> null
        }
    }
    if (kotlin.math.abs(dy) <= threshold || symbolDirection == SwipeSymbolDirection.Disabled) {
        return null
    }
    val matchesPreference = when (symbolDirection) {
        SwipeSymbolDirection.Down -> dy > 0f
        SwipeSymbolDirection.Up -> dy < 0f
        SwipeSymbolDirection.Disabled -> false
    }
    return BdsDirection.Up.takeIf { matchesPreference }
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
