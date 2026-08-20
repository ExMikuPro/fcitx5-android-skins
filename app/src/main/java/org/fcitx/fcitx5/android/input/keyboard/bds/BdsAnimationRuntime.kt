/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimation
import org.fcitx.fcitx5.android.data.theme.bds.BdsCompositeMethod
import org.fcitx.fcitx5.android.data.theme.bds.BdsPrimitiveKind
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal data class BdsTransform(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 1f,
    val pivotXPercent: Float = 50f,
    val pivotYPercent: Float = 50f
) {
    operator fun times(other: BdsTransform) = BdsTransform(
        translationX = translationX + other.translationX,
        translationY = translationY + other.translationY,
        rotation = rotation + other.rotation,
        scaleX = scaleX * other.scaleX,
        scaleY = scaleY * other.scaleY,
        alpha = alpha * other.alpha,
        pivotXPercent = if (other.pivotXPercent != 50f) other.pivotXPercent else pivotXPercent,
        pivotYPercent = if (other.pivotYPercent != 50f) other.pivotYPercent else pivotYPercent
    )
}

internal data class BdsAnimationFrame(
    val transform: BdsTransform,
    val active: Boolean
)

internal fun interface BdsParticleRenderer {
    fun drawParticle(
        styleIndex: Int,
        x: Float,
        y: Float,
        scale: Float,
        rotation: Float,
        alpha: Float
    )
}

internal class BdsParticleEmitterInstance(
    private val emitter: BdsAnimation.ParticleEmitter,
    random: Random,
    private val startNanos: Long
) {
    /** Fixed-capacity primitive storage; no particle objects are created while rendering. */
    internal val capacity = emitter.totalNumber.coerceAtLeast(0)
    private val birthMillis = FloatArray(capacity)
    private val styleIndices = IntArray(capacity)
    private val normalizedX = FloatArray(capacity)
    private val normalizedY = FloatArray(capacity)
    private val velocity = FloatArray(capacity)
    private val velocityCos = FloatArray(capacity)
    private val velocitySin = FloatArray(capacity)
    private val acceleration = FloatArray(capacity)
    private val accelerationCos = FloatArray(capacity)
    private val accelerationSin = FloatArray(capacity)
    private val initialScale = FloatArray(capacity)
    private val scaleSpeed = FloatArray(capacity)
    private val initialRotation = FloatArray(capacity)
    private val rotationSpeed = FloatArray(capacity)
    private val initialAlpha = FloatArray(capacity)
    private val alphaSpeed = FloatArray(capacity)

    /** Active particles are always one contiguous birth-ordered window. */
    internal var firstActiveIndex = 0
        private set
    internal var nextBirthIndex = 0
        private set
    internal val activeCount: Int
        get() = nextBirthIndex - firstActiveIndex

    init {
        val region = emitter.emitRegion
        val left = region.getOrElse(0) { 0f }
        val top = region.getOrElse(1) { 0f }
        val right = region.getOrElse(2) { 1f }
        val bottom = region.getOrElse(3) { 1f }
        val birthsPerSecond = emitter.birthRate.coerceAtLeast(0.001f)
        var index = 0
        while (index < capacity) {
            birthMillis[index] = index * 1000f / birthsPerSecond
            styleIndices[index] = if (emitter.particleStyleIds.isEmpty()) {
                -1
            } else {
                random.nextInt(emitter.particleStyleIds.size)
            }
            normalizedX[index] = randomBetween(random, left, right)
            normalizedY[index] = randomBetween(random, top, bottom)
            velocity[index] = emitter.velocity?.sample(random) ?: 0f
            val velocityRadians = (emitter.velocityDirection?.sample(random) ?: 0f) *
                PI.toFloat() / 180f
            velocityCos[index] = cos(velocityRadians)
            velocitySin[index] = sin(velocityRadians)
            acceleration[index] = emitter.acceleration?.sample(random) ?: 0f
            val accelerationRadians =
                (emitter.accelerationDirection?.sample(random) ?: 0f) * PI.toFloat() / 180f
            accelerationCos[index] = cos(accelerationRadians)
            accelerationSin[index] = sin(accelerationRadians)
            initialScale[index] = emitter.initialScale?.sample(random) ?: 1f
            scaleSpeed[index] = emitter.scaleSpeed?.sample(random) ?: 0f
            initialRotation[index] = emitter.initialRotation?.sample(random) ?: 0f
            rotationSpeed[index] = emitter.rotationSpeed?.sample(random) ?: 0f
            initialAlpha[index] = emitter.initialAlpha?.sample(random) ?: 255f
            alphaSpeed[index] = emitter.alphaSpeed?.sample(random) ?: 0f
            index++
        }
    }

    private val finalBirthMillis = if (capacity == 0) 0f else birthMillis[capacity - 1]
    val durationMillis = finalBirthMillis + emitter.lifeMillis

    /**
     * Samples only the active birth-ordered window and writes primitives directly
     * to [renderer]. With a reused renderer this method allocates nothing per frame.
     */
    fun renderFrame(
        frameTimeNanos: Long,
        width: Float,
        height: Float,
        renderer: BdsParticleRenderer
    ): Boolean {
        val elapsedMillis = (frameTimeNanos - startNanos).coerceAtLeast(0L) / 1_000_000f

        while (nextBirthIndex < capacity && birthMillis[nextBirthIndex] <= elapsedMillis) {
            nextBirthIndex++
        }
        while (firstActiveIndex < nextBirthIndex &&
            elapsedMillis - birthMillis[firstActiveIndex] > emitter.lifeMillis
        ) {
            firstActiveIndex++
        }

        var index = firstActiveIndex
        while (index < nextBirthIndex) {
            val ageMillis = elapsedMillis - birthMillis[index]
            val ageSeconds = ageMillis / 1000f
            val velocityDistance = velocity[index] * ageSeconds
            val accelerationDistance =
                0.5f * acceleration[index] * ageSeconds * ageSeconds
            renderer.drawParticle(
                styleIndices[index],
                normalizedX[index] * width + velocityCos[index] * velocityDistance +
                    accelerationCos[index] * accelerationDistance,
                normalizedY[index] * height + velocitySin[index] * velocityDistance +
                    accelerationSin[index] * accelerationDistance,
                (initialScale[index] + scaleSpeed[index] * ageSeconds).coerceAtLeast(0f),
                initialRotation[index] + rotationSpeed[index] * ageSeconds,
                ((initialAlpha[index] + alphaSpeed[index] * ageSeconds) / 255f)
                    .coerceIn(0f, 1f)
            )
            index++
        }
        return elapsedMillis <= durationMillis
    }
}

private fun randomBetween(random: Random, minimum: Float, maximum: Float): Float = when {
    minimum == maximum -> minimum
    else -> random.nextFloat() * (maximum - minimum) + minimum
}

/** A trigger-scoped, fully sampled animation graph. No random work happens per frame. */
internal class BdsAnimationInstance private constructor(
    private val root: SampledAnimation,
    private val startNanos: Long
) {
    val durationMillis: Long get() = root.durationMillis

    fun frameAt(frameTimeNanos: Long): BdsAnimationFrame {
        val elapsedMillis = ((frameTimeNanos - startNanos).coerceAtLeast(0L)) / 1_000_000f
        if (elapsedMillis > root.durationMillis) {
            return BdsAnimationFrame(BdsTransform(), false)
        }
        return BdsAnimationFrame(root.sample(elapsedMillis), true)
    }

    companion object {
        fun create(
            animationId: Int,
            animations: Map<Int, BdsAnimation>,
            random: Random,
            startNanos: Long
        ): BdsAnimationInstance? {
            val root = sampleAnimation(animationId, animations, random, mutableSetOf()) ?: return null
            return BdsAnimationInstance(root, startNanos)
        }

        private fun sampleAnimation(
            animationId: Int,
            animations: Map<Int, BdsAnimation>,
            random: Random,
            visiting: MutableSet<Int>
        ): SampledAnimation? {
            if (!visiting.add(animationId)) {
                Timber.w("BDS: cyclic animation reference at ANIM$animationId")
                return null
            }
            val sampled = when (val animation = animations[animationId]) {
                is BdsAnimation.Primitive -> SampledPrimitive(animation, random)
                is BdsAnimation.Composite -> {
                    val children = animation.childAnimationIds.mapNotNull {
                        sampleAnimation(it, animations, random, visiting)
                    }
                    when (animation.method) {
                        BdsCompositeMethod.Parallel -> SampledParallel(children)
                        BdsCompositeMethod.Sequential -> SampledSequential(children)
                        null -> null.also {
                            Timber.w("BDS: unsupported BUILD_METHOD=${animation.rawBuildMethod} in ANIM$animationId")
                        }
                    }
                }
                is BdsAnimation.ParticleEmitter -> null
                is BdsAnimation.Unknown -> null.also {
                    Timber.w("BDS: unsupported ANIM$animationId: ${animation.reason}")
                }
                null -> null.also { Timber.w("BDS: missing ANIM$animationId") }
            }
            visiting.remove(animationId)
            return sampled
        }
    }
}

private sealed interface SampledAnimation {
    val durationMillis: Long
    /**
     * Legacy BDS files encode a pure wait as a constant, fully transparent alpha
     * primitive. It is active while its sequential slot is running, but must not
     * be carried into the following child.
     */
    val isTransientSequentialDelay: Boolean get() = false
    fun sample(elapsedMillis: Float): BdsTransform
}

private class SampledPrimitive(
    animation: BdsAnimation.Primitive,
    random: Random
) : SampledAnimation {
    private val kind = animation.kind
    private val from = animation.from?.sample(random).orEmpty()
    private val to = animation.to?.sample(random).orEmpty()
    private val delayMillis = animation.delayMillis
    private val primitiveDuration = animation.durationMillis.coerceAtLeast(0L)
    private val reverse = animation.repeatMode == 1
    private val interpolator = animation.interpolator
    private val pivot = animation.pivot?.sample(random).orEmpty()

    override val isTransientSequentialDelay =
        kind == BdsPrimitiveKind.Alpha &&
            from.isNotEmpty() && to.isNotEmpty() &&
            from.all { it == 0f } && to.all { it == 0f }

    override val durationMillis = delayMillis + primitiveDuration * if (reverse) 2 else 1

    override fun sample(elapsedMillis: Float): BdsTransform {
        val local = (elapsedMillis - delayMillis).coerceAtLeast(0f)
        val rawProgress = if (primitiveDuration <= 0L) 1f else local / primitiveDuration
        val repeatedProgress = if (reverse && rawProgress > 1f) 2f - rawProgress else rawProgress
        val progress = interpolate(repeatedProgress.coerceIn(0f, 1f), interpolator)
        val values = List(maxOf(from.size, to.size)) { index ->
            val start = from.getOrElse(index) { defaultValue(kind, index) }
            val end = to.getOrElse(index) { start }
            start + (end - start) * progress
        }
        val pivotX = pivot.getOrElse(0) { 50f }
        val pivotY = pivot.getOrElse(1) { 50f }
        return when (kind) {
            BdsPrimitiveKind.Alpha -> BdsTransform(
                alpha = values.getOrElse(0) { 255f }.div(255f).coerceIn(0f, 1f),
                pivotXPercent = pivotX,
                pivotYPercent = pivotY
            )
            BdsPrimitiveKind.Rotation -> BdsTransform(
                rotation = values.getOrElse(0) { 0f },
                pivotXPercent = pivotX,
                pivotYPercent = pivotY
            )
            BdsPrimitiveKind.Translation -> BdsTransform(
                translationX = values.getOrElse(0) { 0f },
                translationY = values.getOrElse(1) { 0f },
                pivotXPercent = pivotX,
                pivotYPercent = pivotY
            )
            BdsPrimitiveKind.Scale -> BdsTransform(
                scaleX = values.getOrElse(0) { 100f } / 100f,
                scaleY = values.getOrElse(1) { values.getOrElse(0) { 100f } } / 100f,
                pivotXPercent = pivotX,
                pivotYPercent = pivotY
            )
            null -> BdsTransform()
        }
    }

    private fun defaultValue(kind: BdsPrimitiveKind?, index: Int): Float = when (kind) {
        BdsPrimitiveKind.Alpha -> 255f
        BdsPrimitiveKind.Scale -> 100f
        else -> 0f
    }
}

private class SampledParallel(private val children: List<SampledAnimation>) : SampledAnimation {
    override val durationMillis = children.maxOfOrNull { it.durationMillis } ?: 0L

    override fun sample(elapsedMillis: Float): BdsTransform = children
        .fold(BdsTransform()) { transform, child ->
            // A completed parallel primitive holds its terminal value until the
            // longest sibling completes (ANIM38 in the Golden Sample relies on it).
            transform * child.sample(elapsedMillis.coerceAtMost(child.durationMillis.toFloat()))
        }
}

private class SampledSequential(private val children: List<SampledAnimation>) : SampledAnimation {
    override val durationMillis = children.sumOf { it.durationMillis }

    override fun sample(elapsedMillis: Float): BdsTransform {
        var offset = 0L
        var completed = BdsTransform()
        children.forEach { child ->
            val end = offset + child.durationMillis
            if (elapsedMillis <= end) {
                return completed * child.sample(elapsedMillis - offset.toFloat())
            }
            if (!child.isTransientSequentialDelay) {
                completed *= child.sample(child.durationMillis.toFloat())
            }
            offset = end
        }
        return completed
    }
}

private fun interpolate(progress: Float, rawInterpolator: Int?): Float = when (rawInterpolator) {
    // Mapping inferred from the Golden Sample and verified against the legacy renderer oracle.
    2 -> (cos((progress + 1f) * PI).toFloat() / 2f) + 0.5f
    else -> progress
}
