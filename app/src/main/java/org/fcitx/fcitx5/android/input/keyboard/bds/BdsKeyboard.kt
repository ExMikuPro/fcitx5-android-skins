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
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.bds.BdsAction
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimation
import org.fcitx.fcitx5.android.data.theme.bds.BdsDirection
import org.fcitx.fcitx5.android.data.theme.bds.BdsImage
import org.fcitx.fcitx5.android.data.theme.bds.BdsImageRef
import org.fcitx.fcitx5.android.data.theme.bds.BdsKey
import org.fcitx.fcitx5.android.data.theme.bds.BdsLayout
import org.fcitx.fcitx5.android.data.theme.bds.BdsLayoutId
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

private class ResolvedParticleDrawable(
    val bitmap: Bitmap,
    val source: Rect,
    val sourceWidth: Int,
    val sourceHeight: Int
)

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

    private var capsState = BdsCapsState.None

    private val surface = BdsKeyboardSurface(
        context,
        skin,
        layout,
        onAction = { action ->
            if (functionActionHandler?.invoke(action.raw) != true) {
                mapAction(action)?.let { onAction(it) }
            }
        },
        onCapsLock = {
            onAction(KeyAction.CapsAction(true))
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
        animationsEnabled = bdsAnimationsEnabled(layout.id)
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
        capsState = BdsCapsState.None
        surface.capsState = capsState
    }

    override fun onReturnActionUpdate(action: ReturnKeyAction) {
        surface.returnAction = action
    }

    override fun onAction(action: KeyAction, source: org.fcitx.fcitx5.android.input.keyboard.KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.CapsAction -> {
                capsState = when {
                    action.lock && capsState == BdsCapsState.Lock -> BdsCapsState.None
                    action.lock -> BdsCapsState.Lock
                    capsState == BdsCapsState.None -> BdsCapsState.Once
                    else -> BdsCapsState.None
                }
                surface.capsState = capsState
                return
            }
            is KeyAction.FcitxKeyAction -> if (action.act.length == 1 && action.act[0].isLetter()) {
                transformed = when (capsState) {
                    BdsCapsState.None -> action.copy(act = action.act.lowercase())
                    BdsCapsState.Once -> action.copy(
                        act = action.act.uppercase(),
                        states = KeyStates(KeyState.Virtual, KeyState.Shift)
                    ).also {
                        capsState = BdsCapsState.None
                        surface.capsState = capsState
                    }
                    BdsCapsState.Lock -> action.copy(
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

internal fun isBdsBackspaceAction(action: BdsAction?): Boolean {
    val mapped = action?.let(::mapBdsAction) as? KeyAction.SymAction ?: return false
    return mapped.sym == KeySym(FcitxKeyMapping.FcitxKey_BackSpace)
}

internal fun isBdsCapsAction(action: BdsAction?): Boolean =
    action?.let(::mapBdsAction) is KeyAction.CapsAction

internal fun bdsAnimationsEnabled(layoutId: BdsLayoutId): Boolean =
    layoutId.orientation == BdsOrientation.Portrait

internal fun resolveBdsShiftedLayout(skin: BdsSkin, layout: BdsLayout): BdsLayout? {
    val configured = layout.moreProperties.entries
        .firstOrNull { it.key.equals("EN_26S_LAYOUT", ignoreCase = true) }
        ?.value
    val conventional = if (layout.id.name.endsWith("_h", ignoreCase = true)) {
        "en_26s_h"
    } else {
        "en_26s"
    }
    return listOfNotNull(configured, conventional)
        .distinctBy(String::lowercase)
        .firstNotNullOfOrNull { skin.layout(layout.id.orientation, it) }
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
        onCapsLock = {},
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
    private val onCapsLock: () -> Unit,
    private val onShowCharacterPopup: (Int, String, Rect) -> Unit,
    private val onPopupChangeFocus: (Int, Float, Float) -> Boolean,
    private val onPopupTrigger: (Int) -> Boolean,
    private val renderKeys: Boolean = true,
    private val renderBackdrop: Boolean = true,
    private val animationsEnabled: Boolean = true
) : ViewGroup(context), BdsParticleRenderer {
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
    private var panelEmitterModel: BdsAnimation.ParticleEmitter? = null
    private var resolvedParticleViewportWidth = -1
    private var particleDrawables: Array<ResolvedParticleDrawable?> = emptyArray()
    private var particleCanvas: Canvas? = null
    private val particleDestination = RectF()

    private val shiftedLayout = resolveBdsShiftedLayout(skin, layout)
    private val shiftedKeys = shiftedLayout?.keys.orEmpty().associateBy { it.section.uppercase() }
    private val shiftedDecorations = shiftedLayout?.decorations.orEmpty()
        .associateBy { it.section.uppercase() }

    var capsState: BdsCapsState = BdsCapsState.None
        set(value) {
            field = value
            decorations.plus(childrenViews).forEach {
                it.capsState = value
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
            context, key, shiftedDecorations[key.section.uppercase()], shiftedLayout,
            skin, layout, bitmaps, onAction, onCapsLock, onShowCharacterPopup,
            onPopupChangeFocus, onPopupTrigger, animationsEnabled,
            isDecoration = true
        )
    } else emptyList()
    private val childrenViews = layout.keys.map { key ->
        if (renderKeys) {
            BdsKeyView(
                context, key, shiftedKeys[key.section.uppercase()], shiftedLayout,
                skin, layout, bitmaps, onAction, onCapsLock, onShowCharacterPopup,
                onPopupChangeFocus, onPopupTrigger, animationsEnabled,
                isDecoration = false
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
        panelEmitterModel?.let(::resolveParticleDrawables)
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
        panelEmitterModel = emitter
        if (width > 0) resolveParticleDrawables(emitter)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        panelEmitter = null
        panelEmitterModel = null
        particleDrawables = emptyArray()
        resolvedParticleViewportWidth = -1
        particleCanvas = null
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
        particleCanvas = canvas
        val active = emitter.renderFrame(
            frameTimeNanos, width.toFloat(), height.toFloat(), this
        )
        particleCanvas = null
        paint.alpha = oldAlpha
        if (active) postInvalidateOnAnimation() else panelEmitter = null
    }

    override fun drawParticle(
        styleIndex: Int,
        x: Float,
        y: Float,
        scale: Float,
        rotation: Float,
        alpha: Float
    ) {
        if (alpha <= 0f || scale <= 0f || styleIndex !in particleDrawables.indices) return
        val drawable = particleDrawables[styleIndex] ?: return
        val canvas = particleCanvas ?: return
        val particleWidth = transformer.x(drawable.sourceWidth) * scale
        val particleHeight = transformer.y(drawable.sourceHeight) * scale
        particleDestination.set(
            x - particleWidth / 2f,
            y - particleHeight / 2f,
            x + particleWidth / 2f,
            y + particleHeight / 2f
        )
        val save = canvas.save()
        canvas.rotate(rotation, x, y)
        paint.alpha = (alpha * 255f).roundToInt()
        canvas.drawBitmap(drawable.bitmap, drawable.source, particleDestination, paint)
        canvas.restoreToCount(save)
    }

    private fun resolveParticleDrawables(emitter: BdsAnimation.ParticleEmitter) {
        if (resolvedParticleViewportWidth == transformer.viewportWidth &&
            particleDrawables.size == emitter.particleStyleIds.size
        ) return
        particleDrawables = arrayOfNulls(emitter.particleStyleIds.size)
        var index = 0
        while (index < emitter.particleStyleIds.size) {
            val style = bdsResources.styles[emitter.particleStyleIds[index]]
            val ref = style?.normalImage ?: style?.pressedImage
            val image = ref?.let { skin.image(layout, it.atlas, transformer.viewportWidth) }
            val tile = ref?.let { image?.tiles?.get(it.tile) }
            val bitmap = image?.let { bitmaps.cachedBitmap(it.pngPath) }
            if (tile != null && bitmap != null) {
                val source = tile.source
                particleDrawables[index] = ResolvedParticleDrawable(
                    bitmap,
                    Rect(source.x, source.y, source.x + source.width, source.y + source.height),
                    source.width,
                    source.height
                )
            }
            index++
        }
        resolvedParticleViewportWidth = transformer.viewportWidth
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
    private val shiftedKey: BdsKey?,
    private val shiftedLayout: BdsLayout?,
    private val skin: BdsSkin,
    private val layout: BdsLayout,
    private val bitmaps: MutableMap<String, Bitmap?>,
    private val onAction: (BdsAction) -> Unit,
    private val onCapsLock: () -> Unit,
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
    private val hapticOnRepeat by AppPrefs.getInstance().keyboard.hapticOnRepeat

    var returnAction: ReturnKeyAction = ReturnKeyAction.Enter
    var capsState: BdsCapsState = BdsCapsState.None

    init {
        setWillNotDraw(false)
        isClickable = !isDecoration
        if (!isDecoration) {
            id = View.generateViewId()
            val centerAction = key.actions[BdsDirection.Center]
            setOnClickListener {
                currentAction(BdsDirection.Center)?.let(onAction)
            }
            if (isBdsCapsAction(centerAction)) {
                doubleTapEnabled = true
                onDoubleTapListener = { onCapsLock() }
            }
            if (isBdsBackspaceAction(centerAction)) {
                soundEffect = InputFeedbacks.SoundEffect.Delete
                repeatEnabled = true
                onRepeatListener = { view ->
                    currentAction(BdsDirection.Center)?.let(onAction)
                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                }
            }
            val holdAction = key.actions[BdsDirection.Hold]
                ?: shiftedKey?.actions?.get(BdsDirection.Hold)
            val hasPopupPreset = bdsPopupPresetLabel(
                key.actions[BdsDirection.Center], false
            ) != null || bdsPopupPresetLabel(
                shiftedKey?.actions?.get(BdsDirection.Center)
                    ?: key.actions[BdsDirection.Center],
                true
            ) != null
            if (holdAction != null || hasPopupPreset) {
                setOnLongClickListener {
                    val label = bdsPopupPresetLabel(
                        currentAction(BdsDirection.Center), capsState != BdsCapsState.None
                    )
                    if (label != null) {
                        onShowCharacterPopup(id, label, popupBounds())
                        // Match BaseKeyboard: keep receiving move/up for selection.
                        false
                    } else {
                        currentAction(BdsDirection.Hold)?.let {
                            onAction(it)
                            true
                        } ?: false
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
                            direction?.let(::currentAction)?.let {
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

    private fun currentAction(direction: BdsDirection): BdsAction? =
        resolveBdsKeyAction(key, shiftedKey, capsState, direction)

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
        val visualKey = shiftedKey.takeIf { capsState != BdsCapsState.None } ?: key
        val visualVariants = shiftedLayout?.variants
            .takeIf { visualKey === shiftedKey }
            ?: layout.variants
        val visualOffsets = shiftedLayout?.offsets
            .takeIf { visualKey === shiftedKey }
            ?: layout.offsets
        val variant = BdsKeyStateResolver.resolve(
            visualKey, returnAction, visualVariants, capsState
        )
        val backgroundStyle = variant?.backgroundStyle ?: visualKey.backgroundStyle
        val foregroundStyles = variant?.foregroundStyles ?: visualKey.foregroundStyles
        val positionTypes = variant?.positionTypes ?: visualKey.positionTypes
        val hasAnimation = wholeAnimation != null || backgroundAnimation != null ||
            foregroundAnimations.any { it != null }
        // A child view may redraw from its own display list without executing the
        // parent surface's dispatchDraw(). Sample monotonic time locally while this
        // key is animated so a completed panel emitter cannot freeze key animations.
        val frameTimeNanos = if (hasAnimation) System.nanoTime() else 0L
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
            val offset = visualOffsets[positionTypes.getOrNull(index)]
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
        if (needsNextFrame) {
            postInvalidateOnAnimation()
        } else if (hasAnimation) {
            wholeAnimation = null
            backgroundAnimation = null
            foregroundAnimations.clear()
        }
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
        val visualKey = shiftedKey.takeIf { capsState != BdsCapsState.None } ?: key
        val visualVariants = shiftedLayout?.variants
            .takeIf { visualKey === shiftedKey }
            ?: layout.variants
        val variant = BdsKeyStateResolver.resolve(
            visualKey, returnAction, visualVariants, capsState
        )
        wholeAnimation = create(variant?.animationStyle ?: visualKey.animationStyle)
        backgroundAnimation = create(
            variant?.backgroundAnimationStyle ?: visualKey.backgroundAnimationStyle
        )
        val animationStyles = variant?.foregroundAnimationStyles
            ?.takeIf { it.isNotEmpty() }
            ?: visualKey.foregroundAnimationStyles
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

/** Resolve input from the same layout that supplies the visible key foreground. */
internal fun resolveBdsKeyAction(
    key: BdsKey,
    shiftedKey: BdsKey?,
    capsState: BdsCapsState,
    direction: BdsDirection
): BdsAction? = shiftedKey
    ?.takeIf { capsState != BdsCapsState.None }
    ?.actions
    ?.get(direction)
    ?: key.actions[direction]

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
