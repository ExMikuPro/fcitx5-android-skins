/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import org.fcitx.fcitx5.android.BuildConfig
import kotlin.random.Random

/** Shared clock/random policy for deterministic BDS visual regression. */
object BdsRenderEnvironment {
    enum class Mode { Static, Animation }

    data class Configuration(
        val mode: Mode,
        val timestampMillis: Long,
        val randomSeed: Int
    )

    private const val GOLDEN_RANDOM_SEED = 0x0BD5

    @Volatile
    var configuration: Configuration = if (BuildConfig.DEBUG) {
        Configuration(Mode.Static, timestampMillis = 0L, randomSeed = GOLDEN_RANDOM_SEED)
    } else {
        Configuration(
            Mode.Animation,
            timestampMillis = System.currentTimeMillis(),
            randomSeed = Random.Default.nextInt()
        )
    }
        private set

    fun configureVisualRegression(
        timestampMillis: Long = 0L,
        randomSeed: Int = GOLDEN_RANDOM_SEED
    ) {
        check(BuildConfig.DEBUG) { "Visual regression controls are debug-only" }
        configuration = Configuration(Mode.Static, timestampMillis, randomSeed)
    }

    fun configureAnimationPreview(timestampMillis: Long, randomSeed: Int) {
        check(BuildConfig.DEBUG) { "Animation preview controls are debug-only" }
        configuration = Configuration(Mode.Animation, timestampMillis, randomSeed)
    }

    fun randomFor(elementId: Int): Random =
        Random(configuration.randomSeed xor (elementId * -0x61c88647))
}
