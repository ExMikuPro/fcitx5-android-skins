/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimatedNumber
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimatedVector
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimation
import org.fcitx.fcitx5.android.data.theme.bds.BdsCompositeMethod
import org.fcitx.fcitx5.android.data.theme.bds.BdsNumberRange
import org.fcitx.fcitx5.android.data.theme.bds.BdsPrimitiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BdsAnimationRuntimeTest {
    @Test
    fun reverseScaleReturnsToItsStartingValue() {
        val animation = primitive(
            id = 5,
            kind = BdsPrimitiveKind.Scale,
            from = vector(100f, 100f),
            to = vector(125f, 125f),
            duration = 100,
            repeatMode = 1
        )
        val instance = BdsAnimationInstance.create(5, mapOf(5 to animation), Random(1), 0)!!
        assertEquals(1f, instance.frameAt(0).transform.scaleX, 0.001f)
        assertEquals(1.25f, instance.frameAt(100_000_000).transform.scaleX, 0.001f)
        assertEquals(1.125f, instance.frameAt(150_000_000).transform.scaleX, 0.001f)
        assertEquals(1f, instance.frameAt(200_000_000).transform.scaleX, 0.001f)
        assertFalse(instance.frameAt(201_000_000).active)
    }

    @Test
    fun sequentialDelayDoesNotHideTheFollowingAnimation() {
        val delay = primitive(
            id = 39,
            kind = BdsPrimitiveKind.Alpha,
            from = vector(0f),
            to = vector(0f),
            duration = 150
        )
        val translation = primitive(
            id = 35,
            kind = BdsPrimitiveKind.Translation,
            from = vector(50f, 20f),
            to = vector(150f, -150f),
            duration = 250
        )
        val composite = BdsAnimation.Composite(
            41, listOf(39, 35), 1, BdsCompositeMethod.Sequential, emptyMap()
        )
        val instance = BdsAnimationInstance.create(
            41, mapOf(39 to delay, 35 to translation, 41 to composite), Random(1), 0
        )!!
        assertEquals(0f, instance.frameAt(100_000_000).transform.alpha, 0.001f)
        val afterDelay = instance.frameAt(200_000_000).transform
        assertEquals(1f, afterDelay.alpha, 0.001f)
        assertEquals(70f, afterDelay.translationX, 0.001f)
    }

    @Test
    fun sequentialTransformsBuildOnCompletedChildren() {
        val rotateOut = primitive(
            id = 11,
            kind = BdsPrimitiveKind.Rotation,
            from = vector(0f),
            to = vector(30f),
            duration = 350
        )
        val visiblePause = primitive(
            id = 12,
            kind = BdsPrimitiveKind.Alpha,
            from = vector(255f),
            to = vector(255f),
            duration = 350
        )
        val rotateBack = primitive(
            id = 13,
            kind = BdsPrimitiveKind.Rotation,
            from = vector(0f),
            to = vector(-30f),
            duration = 350
        )
        val composite = BdsAnimation.Composite(
            9, listOf(11, 12, 13), 1, BdsCompositeMethod.Sequential, emptyMap()
        )
        val instance = BdsAnimationInstance.create(
            9,
            mapOf(9 to composite, 11 to rotateOut, 12 to visiblePause, 13 to rotateBack),
            Random(1),
            0
        )!!

        assertEquals(30f, instance.frameAt(525_000_000).transform.rotation, 0.001f)
        assertEquals(15f, instance.frameAt(875_000_000).transform.rotation, 0.001f)
        assertEquals(0f, instance.frameAt(1_050_000_000).transform.rotation, 0.001f)
    }

    @Test
    fun randomParametersAreStableForASeedAndSampledOnce() {
        val randomNumber = BdsAnimatedNumber.RandomRange(50f, 80f)
        val animation = primitive(
            id = 35,
            kind = BdsPrimitiveKind.Translation,
            from = BdsAnimatedVector(listOf(randomNumber, BdsAnimatedNumber.Fixed(20f))),
            to = vector(150f, -150f),
            duration = 250
        )
        val first = BdsAnimationInstance.create(35, mapOf(35 to animation), Random(123), 0)!!
        val second = BdsAnimationInstance.create(35, mapOf(35 to animation), Random(123), 0)!!
        val firstFrame = first.frameAt(0).transform
        assertEquals(firstFrame, first.frameAt(0).transform)
        assertEquals(firstFrame, second.frameAt(0).transform)
        assertTrue(firstFrame.translationX in 50f..80f)
    }

    @Test
    fun completedParallelChildHoldsItsTerminalValue() {
        val scale = primitive(
            id = 38,
            kind = BdsPrimitiveKind.Scale,
            from = vector(80f, 80f),
            to = vector(80f, 80f),
            duration = 200
        )
        val alpha = primitive(
            id = 37,
            kind = BdsPrimitiveKind.Alpha,
            from = vector(255f),
            to = vector(0f),
            duration = 250
        )
        val composite = BdsAnimation.Composite(
            34, listOf(38, 37), 0, BdsCompositeMethod.Parallel, emptyMap()
        )
        val instance = BdsAnimationInstance.create(
            34, mapOf(34 to composite, 37 to alpha, 38 to scale), Random(1), 0
        )!!

        val frame = instance.frameAt(225_000_000).transform

        assertEquals(0.8f, frame.scaleX, 0.001f)
        assertEquals(0.1f, frame.alpha, 0.001f)
    }

    @Test
    fun completedOneWayAnimationReturnsToStaticRendering() {
        val rotation = primitive(
            id = 17,
            kind = BdsPrimitiveKind.Rotation,
            from = vector(0f),
            to = vector(-90f),
            duration = 150
        )
        val instance = BdsAnimationInstance.create(17, mapOf(17 to rotation), Random(1), 0)!!

        assertEquals(-90f, instance.frameAt(150_000_000).transform.rotation, 0.001f)
        val completed = instance.frameAt(151_000_000)
        assertFalse(completed.active)
        assertEquals(BdsTransform(), completed.transform)
    }

    @Test
    fun particleActiveWindowAddsBirthsAndRemovesExpiredParticles() {
        val instance = BdsParticleEmitterInstance(
            particleEmitter(total = 3, birthRate = 2f, lifeMillis = 1_000),
            Random(1),
            0
        )
        val renderer = RecordingParticleRenderer()

        assertTrue(instance.renderFrame(0, 100f, 100f, renderer))
        assertEquals(3, instance.capacity)
        assertEquals(1, instance.activeCount)
        assertEquals(0, instance.firstActiveIndex)
        assertEquals(1, instance.nextBirthIndex)

        renderer.clear()
        instance.renderFrame(500_000_000, 100f, 100f, renderer)
        assertEquals(2, instance.activeCount)
        assertEquals(2, renderer.frames.size)

        renderer.clear()
        instance.renderFrame(1_001_000_000, 100f, 100f, renderer)
        assertEquals(2, instance.activeCount)
        assertEquals(1, instance.firstActiveIndex)
        assertEquals(3, instance.nextBirthIndex)

        renderer.clear()
        assertFalse(instance.renderFrame(2_001_000_000, 100f, 100f, renderer))
        assertEquals(0, instance.activeCount)
        assertTrue(renderer.frames.isEmpty())
    }

    @Test
    fun particleStorageNeverExceedsDeclaredTotalNumber() {
        val instance = BdsParticleEmitterInstance(
            particleEmitter(total = 4, birthRate = 10_000f, lifeMillis = 10_000),
            Random(1),
            0
        )
        val renderer = RecordingParticleRenderer()

        instance.renderFrame(10_000_000, 100f, 100f, renderer)

        assertEquals(4, instance.capacity)
        assertEquals(4, instance.activeCount)
        assertEquals(4, renderer.frames.size)
    }

    @Test
    fun particleSamplingMatchesBdsKinematics() {
        val emitter = particleEmitter(total = 1, birthRate = 1f, lifeMillis = 1_000).copy(
            emitRegion = listOf(0.5f, 0.5f, 0.5f, 0.5f),
            velocity = BdsNumberRange(10f, 10f),
            velocityDirection = BdsNumberRange(0f, 0f),
            acceleration = BdsNumberRange(2f, 2f),
            accelerationDirection = BdsNumberRange(0f, 0f),
            initialScale = BdsNumberRange(1f, 1f),
            scaleSpeed = BdsNumberRange(0.5f, 0.5f),
            initialRotation = BdsNumberRange(10f, 10f),
            rotationSpeed = BdsNumberRange(20f, 20f),
            initialAlpha = BdsNumberRange(255f, 255f),
            alphaSpeed = BdsNumberRange(-10f, -10f)
        )
        val renderer = RecordingParticleRenderer()
        val instance = BdsParticleEmitterInstance(emitter, Random(1), 0)

        instance.renderFrame(500_000_000, 200f, 100f, renderer)

        val frame = renderer.frames.single()
        assertEquals(105.25f, frame.x, 0.0001f)
        assertEquals(50f, frame.y, 0.0001f)
        assertEquals(1.25f, frame.scale, 0.0001f)
        assertEquals(20f, frame.rotation, 0.0001f)
        assertEquals(250f / 255f, frame.alpha, 0.0001f)
    }

    @Test
    fun particleRandomSamplingIsReproducibleAndDoesNotMutateModel() {
        val emitter = particleEmitter(total = 8, birthRate = 8f, lifeMillis = 2_000).copy(
            emitRegion = listOf(0f, 0f, 1f, 1f),
            velocity = BdsNumberRange(30f, 60f),
            velocityDirection = BdsNumberRange(45f, 135f),
            initialScale = BdsNumberRange(0.6f, 0.8f),
            initialRotation = BdsNumberRange(0f, 360f)
        )
        val original = emitter.copy()
        val first = BdsParticleEmitterInstance(emitter, Random(3029), 0)
        val second = BdsParticleEmitterInstance(emitter, Random(3029), 0)
        val firstFrames = RecordingParticleRenderer()
        val secondFrames = RecordingParticleRenderer()

        first.renderFrame(900_000_000, 1080f, 688f, firstFrames)
        second.renderFrame(900_000_000, 1080f, 688f, secondFrames)

        assertEquals(firstFrames.frames, secondFrames.frames)
        assertEquals(original, emitter)
    }

    private data class RecordedParticle(
        val styleIndex: Int,
        val x: Float,
        val y: Float,
        val scale: Float,
        val rotation: Float,
        val alpha: Float
    )

    private class RecordingParticleRenderer : BdsParticleRenderer {
        val frames = mutableListOf<RecordedParticle>()

        override fun drawParticle(
            styleIndex: Int,
            x: Float,
            y: Float,
            scale: Float,
            rotation: Float,
            alpha: Float
        ) {
            frames += RecordedParticle(styleIndex, x, y, scale, rotation, alpha)
        }

        fun clear() = frames.clear()
    }

    private fun particleEmitter(total: Int, birthRate: Float, lifeMillis: Long) =
        BdsAnimation.ParticleEmitter(
            id = 23,
            category = 3,
            location = 1,
            lifeMillis = lifeMillis,
            emitRegion = listOf(0f, 0f, 0f, 0f),
            totalNumber = total,
            birthRate = birthRate,
            emitType = 0,
            particleStyleIds = listOf(317, 318, 319),
            velocity = null,
            velocityDirection = null,
            acceleration = null,
            accelerationDirection = null,
            initialScale = null,
            scaleSpeed = null,
            initialRotation = null,
            rotationSpeed = null,
            initialAlpha = null,
            alphaSpeed = null,
            properties = emptyMap()
        )

    private fun primitive(
        id: Int,
        kind: BdsPrimitiveKind,
        from: BdsAnimatedVector,
        to: BdsAnimatedVector,
        duration: Long,
        repeatMode: Int? = null
    ) = BdsAnimation.Primitive(
        id = id,
        rawType = kind.rawType,
        kind = kind,
        from = from,
        to = to,
        durationMillis = duration,
        delayMillis = 0,
        repeatMode = repeatMode,
        interpolator = null,
        pivot = null,
        properties = emptyMap()
    )

    private fun vector(vararg values: Float) = BdsAnimatedVector(
        values.map(BdsAnimatedNumber::Fixed)
    )
}
