/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimation
import org.fcitx.fcitx5.android.data.theme.bds.BdsNumberRange
import timber.log.Timber
import kotlin.random.Random

/** ADB-only control surface included in debug APKs for deterministic visual regression. */
class BdsRenderConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_MODE)?.lowercase()
        val seed = intent.getIntExtra(EXTRA_SEED, DEFAULT_SEED)
        when (mode) {
            MODE_ALLOCATION_PROBE -> {
                val result = particleAllocationProbe(seed)
                resultData = result
                Timber.i("BDS: $result")
                return
            }
            MODE_STATIC -> BdsRenderEnvironment.configureVisualRegression(randomSeed = seed)
            MODE_ANIMATION -> BdsRenderEnvironment.configureAnimationPreview(
                timestampMillis = SystemClock.elapsedRealtime(),
                randomSeed = seed
            )
            else -> {
                Timber.w("BDS: ignored unknown debug render mode=$mode")
                return
            }
        }
        Timber.i("BDS: debug render mode=$mode randomSeed=$seed")
    }

    @Suppress("DEPRECATION")
    private fun particleAllocationProbe(seed: Int): String {
        val emitter = BdsAnimation.ParticleEmitter(
            id = 23,
            category = 3,
            location = 1,
            lifeMillis = 5_000,
            emitRegion = listOf(0f, 0f, 1f, 1f),
            totalNumber = 250,
            birthRate = 25f,
            emitType = 0,
            particleStyleIds = listOf(317, 318, 319, 320, 321, 322),
            velocity = BdsNumberRange(30f, 60f),
            velocityDirection = BdsNumberRange(45f, 135f),
            acceleration = BdsNumberRange(10f, 10f),
            accelerationDirection = BdsNumberRange(90f, 90f),
            initialScale = BdsNumberRange(0.6f, 0.8f),
            scaleSpeed = BdsNumberRange(-0.1f, 0.1f),
            initialRotation = BdsNumberRange(0f, 360f),
            rotationSpeed = BdsNumberRange(-45f, 45f),
            initialAlpha = BdsNumberRange(255f, 255f),
            alphaSpeed = BdsNumberRange(-80f, -10f),
            properties = emptyMap()
        )
        val renderer = BdsParticleRenderer { _, _, _, _, _, _ -> }
        repeat(8) { warmup -> sampleEmitter(emitter, renderer, warmup) }

        val measured = BdsParticleEmitterInstance(emitter, Random(seed), 0L)
        Debug.startAllocCounting()
        Debug.resetThreadAllocCount()
        sampleEmitter(measured, renderer)
        val allocations = Debug.getThreadAllocCount()
        val allocatedBytes = Debug.getThreadAllocSize()
        Debug.stopAllocCounting()
        return "ANIM23 allocation probe: frames=$PROBE_FRAME_COUNT " +
            "allocations=$allocations bytes=$allocatedBytes"
    }

    private fun sampleEmitter(
        emitter: BdsAnimation.ParticleEmitter,
        renderer: BdsParticleRenderer,
        seed: Int
    ) = sampleEmitter(BdsParticleEmitterInstance(emitter, Random(seed), 0L), renderer)

    private fun sampleEmitter(
        instance: BdsParticleEmitterInstance,
        renderer: BdsParticleRenderer
    ) {
        var frame = 0
        while (frame < PROBE_FRAME_COUNT) {
            instance.renderFrame(frame * PROBE_FRAME_NANOS, 1080f, 688f, renderer)
            frame++
        }
    }

    private companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SEED = "seed"
        const val MODE_STATIC = "static"
        const val MODE_ANIMATION = "animation"
        const val MODE_ALLOCATION_PROBE = "allocation_probe"
        const val DEFAULT_SEED = 0x0BD5
        const val PROBE_FRAME_COUNT = 900
        const val PROBE_FRAME_NANOS = 16_666_667L
    }
}
