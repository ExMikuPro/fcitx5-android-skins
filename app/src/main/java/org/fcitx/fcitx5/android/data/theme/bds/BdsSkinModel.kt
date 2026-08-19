/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

data class BdsSkin(
    val id: String,
    val rootPath: String,
    val metadata: BdsMetadata,
    val portraitPinyin26: BdsLayout,
    val portraitCandidate: BdsCandidateLayout?,
    val styles: Map<Int, BdsStyle>,
    val images: Map<String, BdsImage>,
    val resourceBuckets: Map<Int, Map<String, BdsImage>>,
    val unsupportedProperties: List<String>
) {
    fun selectResourceBucket(viewportWidth: Int): Int? = resourceBuckets.keys
        .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - viewportWidth) }.thenByDescending { it })

    fun image(name: String, viewportWidth: Int): BdsImage? {
        val key = name.lowercase()
        val bucket = selectResourceBucket(viewportWidth)
        return bucket?.let { resourceBuckets[it]?.get(key) } ?: images[key]
    }
}

data class BdsCandidateLayout(
    val viewRect: BdsRect,
    val backgroundStyle: Int?,
    val foregroundStyle: Int?,
    val properties: Map<String, String>
)

data class BdsMetadata(
    val name: String,
    val author: String?,
    val description: String?,
    val versionCode: Int?,
    val properties: Map<String, String>
)

data class BdsLayout(
    val designWidth: Int,
    val designHeight: Int,
    val backgroundStyle: Int?,
    val decorations: List<BdsKey>,
    val keys: List<BdsKey>,
    val offsets: Map<Int, BdsPoint>
)

data class BdsKey(
    val section: String,
    val viewRect: BdsRect,
    val touchRect: BdsRect?,
    val backgroundStyle: Int?,
    val foregroundStyles: List<Int>,
    val positionTypes: List<Int>,
    val actions: Map<BdsDirection, BdsAction>,
    val properties: Map<String, String>
)

enum class BdsDirection { Center, Up, Down, Left, Right, Hold }

data class BdsAction(val raw: String)

data class BdsStyle(
    val id: Int,
    val normalImage: BdsImageRef?,
    val pressedImage: BdsImageRef?,
    val normalColor: Int?,
    val pressedColor: Int?,
    val fontSize: Float?,
    val fontWeight: Int?,
    val text: String?,
    val properties: Map<String, String>
)

data class BdsImageRef(val atlas: String, val tile: Int)

data class BdsImage(
    val name: String,
    val pngPath: String,
    val useAlpha: Boolean,
    val tiles: Map<Int, BdsTile>
)

data class BdsTile(val source: BdsRect, val inner: BdsRect?)

data class BdsPoint(val x: Int, val y: Int)

data class BdsRect(val x: Int, val y: Int, val width: Int, val height: Int)

class BdsException(message: String, cause: Throwable? = null) : Exception(message, cause)
