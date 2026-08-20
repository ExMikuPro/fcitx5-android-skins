/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimatedNumber
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimatedVector
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimation
import org.fcitx.fcitx5.android.data.theme.bds.BdsCompositeMethod
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
