/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import timber.log.Timber
import java.io.File

enum class BdsIconFamily(
    internal val pngName: String,
    internal val tilName: String
) {
    Toolbar("pop_menu_icons.png", "pop_menu_icons.til"),
    InputMode("pop_input_icons.png", "pop_input_icons.til")
}

internal data class BdsIconFiles(val png: File, val til: File)

internal fun findBdsIconFiles(root: File, family: BdsIconFamily): BdsIconFiles? {
    val logo = root.childIgnoreCase("res")?.childIgnoreCase("logo") ?: return null
    val png = logo.childIgnoreCase(family.pngName)?.takeIf(File::isFile) ?: return null
    val til = logo.childIgnoreCase(family.tilName)?.takeIf(File::isFile) ?: return null
    return BdsIconFiles(png, til)
}

internal class BdsIconCache<T>(private val loader: (BdsIconFamily, Int) -> T?) {
    private var skinId: String? = null
    private val values = mutableMapOf<Pair<BdsIconFamily, Int>, T?>()

    fun activate(newSkinId: String?) {
        if (skinId == newSkinId) return
        skinId = newSkinId
        values.clear()
    }

    fun get(family: BdsIconFamily, iconId: Int): T? {
        if (skinId == null) return null
        val key = family to iconId
        if (values.containsKey(key)) return values[key]
        return loader(family, iconId).also { values[key] = it }
    }

    internal fun size(): Int = values.size
}

/** Decodes each atlas once per active skin and caches all requested cropped bitmaps. */
object BdsToolbarIconProvider {
    private const val MAX_PNG_BYTES = BdsArchiveReader.MAX_ENTRY_BYTES
    private const val MAX_BITMAP_PIXELS = 16_777_216L

    private data class Atlas(val bitmap: Bitmap, val sheet: BdsTileSheet)

    private var activeSkin: BdsSkin? = null
    private val atlases = mutableMapOf<BdsIconFamily, Atlas?>()
    private val bitmaps = BdsIconCache<Bitmap>(::crop)

    @Synchronized
    fun activate(skin: BdsSkin?) {
        if (activeSkin?.id == skin?.id) return
        activeSkin = skin
        atlases.clear()
        bitmaps.activate(skin?.id)
    }

    @Synchronized
    fun bitmap(action: BdsToolbarAction): Bitmap? =
        BdsToolbarIconMapping.iconId(action)?.let { bitmap(BdsIconFamily.Toolbar, it) }

    @Synchronized
    fun bitmap(family: BdsIconFamily, iconId: Int): Bitmap? = bitmaps.get(family, iconId)

    fun drawable(resources: Resources, action: BdsToolbarAction): Drawable? =
        bitmap(action)?.let { BitmapDrawable(resources, it) }

    fun drawable(resources: Resources, family: BdsIconFamily, iconId: Int): Drawable? =
        bitmap(family, iconId)?.let { BitmapDrawable(resources, it) }

    @Synchronized
    fun availableIconIds(family: BdsIconFamily): Set<Int> =
        atlas(family)?.sheet?.tiles?.keys.orEmpty()

    private fun crop(family: BdsIconFamily, iconId: Int): Bitmap? {
        val atlas = atlas(family) ?: return null
        val rect = atlas.sheet.tiles[iconId] ?: return null
        return runCatching {
            Bitmap.createBitmap(atlas.bitmap, rect.x, rect.y, rect.width, rect.height)
        }.onFailure { Timber.w(it, "BDS: failed to crop %s icon %d", family, iconId) }
            .getOrNull()
    }

    private fun atlas(family: BdsIconFamily): Atlas? {
        if (atlases.containsKey(family)) return atlases[family]
        return loadAtlas(family).also { atlases[family] = it }
    }

    private fun loadAtlas(family: BdsIconFamily): Atlas? {
        val root = activeSkin?.rootPath?.let(::File) ?: return null
        val files = findBdsIconFiles(root, family) ?: return null
        val png = files.png
        val til = files.til
        if (png.length() !in 1..MAX_PNG_BYTES) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(png.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_BITMAP_PIXELS) return null
            val sheet = BdsTilParser.parse(til, width, height) ?: return null
            val bitmap = BitmapFactory.decodeFile(png.absolutePath) ?: return null
            Atlas(bitmap, sheet)
        }.onFailure { Timber.w(it, "BDS: failed to decode %s", family) }.getOrNull()
    }
}
