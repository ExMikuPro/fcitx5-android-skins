/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.bds

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkin

/** Draws the BDS candidate background below Fcitx candidate data/chrome. */
internal class BdsCandidateBackgroundView(
    context: Context,
    private val skin: BdsSkin
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmapPath: String? = null
    private var bitmap: Bitmap? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val styleId = skin.portraitCandidate?.backgroundStyle ?: return
        val ref = skin.styles[styleId]?.normalImage ?: return
        val image = skin.image(ref.atlas, width) ?: return
        val tile = image.tiles[ref.tile] ?: return
        if (bitmapPath != image.pngPath) {
            bitmapPath = image.pngPath
            bitmap = BitmapFactory.decodeFile(image.pngPath)
        }
        val bitmap = bitmap ?: return
        BdsDrawing.drawTile(
            canvas = canvas,
            bitmap = bitmap,
            tile = tile,
            dest = RectF(0f, 0f, width.toFloat(), height.toFloat()),
            paint = paint,
            stretch = true,
            scaleX = width / skin.portraitPinyin26.designWidth.toFloat(),
            scaleY = width / skin.portraitPinyin26.designWidth.toFloat()
        )
    }
}
