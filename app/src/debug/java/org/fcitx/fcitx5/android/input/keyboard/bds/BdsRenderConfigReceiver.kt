/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import timber.log.Timber

/** ADB-only control surface included in debug APKs for deterministic visual regression. */
class BdsRenderConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_MODE)?.lowercase()
        val seed = intent.getIntExtra(EXTRA_SEED, DEFAULT_SEED)
        when (mode) {
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

    private companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SEED = "seed"
        const val MODE_STATIC = "static"
        const val MODE_ANIMATION = "animation"
        const val DEFAULT_SEED = 0x0BD5
    }
}
