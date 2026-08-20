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

internal data class BdsParticleFrame(
    val styleId: Int,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotation: Float,
    val alpha: Float
)

internal class BdsParticleEmitterInstance(
    private val emitter: BdsAnimation.ParticleEmitter,
    random: Random,
    private val startNanos: Long
) {
    private data class Particle(
        val birthMillis: Float,
        val styleId: Int,
        val normalizedX: Float,
        val normalizedY: Float,
        val velocity: Float,
        val velocityDirectionRadians: Float,
        val acceleration: Float,
        val accelerationDirectionRadians: Float,
        val initialScale: Float,
        val scaleSpeed: Float,
        val initialRotation: Float,
        val rotationSpeed: Float,
        val initialAlpha: Float,
        val alphaSpeed: Float
    )

    private val particles = List(emitter.totalNumber.coerceAtLeast(0)) { index ->
        val region = emitter.emitRegion
        val left = region.getOrElse(0) { 0f }
        val top = region.getOrElse(1) { 0f }
        val right = region.getOrElse(2) { 1f }
        val bottom = region.getOrElse(3) { 1f }
        val birthsPerSecond = emitter.birthRate.coerceAtLeast(0.001f)
        Particle(
            birthMillis = index * 1000f / birthsPerSecond,
            styleId = emitter.particleStyleIds.randomOrNull(random) ?: -1,
            normalizedX = randomBetween(random, left, right),
            normalizedY = randomBetween(random, top, bottom),
            velocity = emitter.velocity?.sample(random) ?: 0f,
            velocityDirectionRadians = (emitter.velocityDirection?.sample(random) ?: 0f) *
                PI.toFloat() / 180f,
            acceleration = emitter.acceleration?.sample(random) ?: 0f,
            accelerationDirectionRadians =
                (emitter.accelerationDirection?.sample(random) ?: 0f) * PI.toFloat() / 180f,
            initialScale = emitter.initialScale?.sample(random) ?: 1f,
            scaleSpeed = emitter.scaleSpeed?.sample(random) ?: 0f,
            initialRotation = emitter.initialRotation?.sample(random) ?: 0f,
            rotationSpeed = emitter.rotationSpeed?.sample(random) ?: 0f,
            initialAlpha = emitter.initialAlpha?.sample(random) ?: 255f,
            alphaSpeed = emitter.alphaSpeed?.sample(random) ?: 0f
        )
    }

    private val finalBirthMillis = particles.lastOrNull()?.birthMillis ?: 0f
    val durationMillis = finalBirthMillis + emitter.lifeMillis

    fun forEachFrame(
        frameTimeNanos: Long,
        width: Float,
        height: Float,
        draw: (BdsParticleFrame) -> Unit
    ): Boolean {
        val elapsedMillis = (frameTimeNanos - startNanos).coerceAtLeast(0L) / 1_000_000f
        particles.forEach { particle ->
            val ageMillis = elapsedMillis - particle.birthMillis
            if (ageMillis < 0f || ageMillis > emitter.lifeMillis) return@forEach
            val ageSeconds = ageMillis / 1000f
            val velocityDistance = particle.velocity * ageSeconds
            val accelerationDistance = 0.5f * particle.acceleration * ageSeconds * ageSeconds
            draw(
                BdsParticleFrame(
                    styleId = particle.styleId,
                    x = particle.normalizedX * width +
                        cos(particle.velocityDirectionRadians) * velocityDistance +
                        cos(particle.accelerationDirectionRadians) * accelerationDistance,
                    y = particle.normalizedY * height +
                        sin(particle.velocityDirectionRadians) * velocityDistance +
                        sin(particle.accelerationDirectionRadians) * accelerationDistance,
                    scale = (particle.initialScale + particle.scaleSpeed * ageSeconds)
                        .coerceAtLeast(0f),
                    rotation = particle.initialRotation + particle.rotationSpeed * ageSeconds,
                    alpha = ((particle.initialAlpha + particle.alphaSpeed * ageSeconds) / 255f)
                        .coerceIn(0f, 1f)
                )
            )
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
