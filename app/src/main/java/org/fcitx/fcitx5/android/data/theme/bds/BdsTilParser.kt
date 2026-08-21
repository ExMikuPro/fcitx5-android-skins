/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import java.io.File

data class BdsTileSheet(
    val declaredTileCount: Int,
    val tiles: Map<Int, BdsRect>,
    val useAlpha: Boolean
)

/** Parser for the generic BDS TIL sprite-sheet format. Invalid tiles are ignored. */
object BdsTilParser {
    const val MAX_TILES = 512
    const val MAX_TIL_BYTES = 1024L * 1024
    const val MAX_TOTAL_TILE_PIXELS = 16_777_216L

    fun parse(file: File, imageWidth: Int, imageHeight: Int): BdsTileSheet? {
        if (!file.isFile || file.length() !in 1..MAX_TIL_BYTES) return null
        return runCatching { parse(file.readBytes(), imageWidth, imageHeight) }.getOrNull()
    }

    fun parse(bytes: ByteArray, imageWidth: Int, imageHeight: Int): BdsTileSheet? {
        if (bytes.size.toLong() > MAX_TIL_BYTES || imageWidth <= 0 || imageHeight <= 0) return null
        val ini = runCatching { BdsIni.parse(bytes) }.getOrNull() ?: return null
        val global = ini.section("GLOBAL") ?: return null
        val declaredCount = global.valueIgnoreCase("TILE_NUM")?.toIntOrNull()
            ?.takeIf { it in 1..MAX_TILES } ?: return null
        val tiles = linkedMapOf<Int, BdsRect>()
        var totalTilePixels = 0L
        ini.sections.entries.forEach { (section, values) ->
            if (tiles.size >= declaredCount) return@forEach
            if (!section.startsWith("IMG", ignoreCase = true)) return@forEach
            val index = section.substring(3).toIntOrNull()
                ?.takeIf { it in 0 until MAX_TILES } ?: return@forEach
            val rect = parseRect(values.valueIgnoreCase("SOURCE_RECT")) ?: return@forEach
            if (!rect.isInside(imageWidth, imageHeight)) return@forEach
            val pixels = rect.width.toLong() * rect.height
            if (totalTilePixels + pixels > MAX_TOTAL_TILE_PIXELS) return@forEach
            tiles[index] = rect
            totalTilePixels += pixels
        }
        if (tiles.isEmpty()) return null
        return BdsTileSheet(
            declaredTileCount = declaredCount,
            tiles = tiles,
            useAlpha = global.valueIgnoreCase("USE_ALPHA") == "1"
        )
    }

    private fun parseRect(value: String?): BdsRect? {
        val parts = value?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
        if (parts.size != 4 || parts.any { it == null }) return null
        return BdsRect(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
    }

    private fun BdsRect.isInside(imageWidth: Int, imageHeight: Int): Boolean =
        x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x <= imageWidth - width && y <= imageHeight - height

    private fun Map<String, String>.valueIgnoreCase(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
