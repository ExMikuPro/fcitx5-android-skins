/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import kotlin.random.Random

sealed interface BdsAnimatedNumber {
    fun sample(random: Random): Float

    data class Fixed(val value: Float) : BdsAnimatedNumber {
        override fun sample(random: Random) = value
    }

    data class RandomRange(val minimum: Float, val maximum: Float) : BdsAnimatedNumber {
        override fun sample(random: Random): Float = when {
            minimum == maximum -> minimum
            else -> random.nextFloat() * (maximum - minimum) + minimum
        }
    }
}

data class BdsAnimatedVector(val components: List<BdsAnimatedNumber>) {
    fun sample(random: Random): List<Float> = components.map { it.sample(random) }
}

data class BdsNumberRange(val minimum: Float, val maximum: Float) {
    fun sample(random: Random): Float = when {
        minimum == maximum -> minimum
        else -> random.nextFloat() * (maximum - minimum) + minimum
    }
}

data class BdsAnimationStyle(
    val styleId: Int,
    val pressAnimationId: Int?,
    val showAnimationId: Int?,
    val eventAnimationIds: Map<Int, Int>,
    val properties: Map<String, String>
)

sealed interface BdsAnimationTrigger {
    data object Press : BdsAnimationTrigger
    data object Show : BdsAnimationTrigger
    data class Event(val id: Int) : BdsAnimationTrigger
}

/** TYPE mapping inferred from this Golden Sample and legacy-renderer recordings. */
enum class BdsPrimitiveKind(val rawType: Int) {
    Alpha(0),
    Rotation(1),
    Translation(2),
    Scale(4);

    companion object {
        fun fromRaw(rawType: Int) = entries.firstOrNull { it.rawType == rawType }
    }
}

/** BUILD_METHOD mapping inferred from this Golden Sample; not an official format spec. */
enum class BdsCompositeMethod(val rawMethod: Int) {
    Parallel(0),
    Sequential(1);

    companion object {
        fun fromRaw(rawMethod: Int) = entries.firstOrNull { it.rawMethod == rawMethod }
    }
}

sealed interface BdsAnimation {
    val id: Int
    val properties: Map<String, String>

    data class Primitive(
        override val id: Int,
        val rawType: Int,
        val kind: BdsPrimitiveKind?,
        val from: BdsAnimatedVector?,
        val to: BdsAnimatedVector?,
        val durationMillis: Long,
        val delayMillis: Long,
        val repeatMode: Int?,
        val interpolator: Int?,
        val pivot: BdsAnimatedVector?,
        override val properties: Map<String, String>
    ) : BdsAnimation

    data class Composite(
        override val id: Int,
        val childAnimationIds: List<Int>,
        val rawBuildMethod: Int,
        val method: BdsCompositeMethod?,
        override val properties: Map<String, String>
    ) : BdsAnimation

    data class ParticleEmitter(
        override val id: Int,
        val category: Int?,
        val location: Int?,
        val lifeMillis: Long,
        val emitRegion: List<Float>,
        val totalNumber: Int,
        val birthRate: Float,
        val emitType: Int?,
        /** CSS STYLE ids, not atlas tile indices. */
        val particleStyleIds: List<Int>,
        val velocity: BdsNumberRange?,
        val velocityDirection: BdsNumberRange?,
        val acceleration: BdsNumberRange?,
        val accelerationDirection: BdsNumberRange?,
        val initialScale: BdsNumberRange?,
        val scaleSpeed: BdsNumberRange?,
        val initialRotation: BdsNumberRange?,
        val rotationSpeed: BdsNumberRange?,
        val initialAlpha: BdsNumberRange?,
        val alphaSpeed: BdsNumberRange?,
        override val properties: Map<String, String>
    ) : BdsAnimation

    data class Unknown(
        override val id: Int,
        val reason: String,
        override val properties: Map<String, String>
    ) : BdsAnimation
}
