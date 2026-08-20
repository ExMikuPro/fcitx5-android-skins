/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import kotlin.math.max
import kotlin.math.roundToInt

data class BdsSkin(
    val id: String,
    val rootPath: String,
    val metadata: BdsMetadata,
    val layouts: Map<BdsLayoutId, BdsLayout>,
    /** Every .ini in port/land, including gen/logo/res/event, retained losslessly by section. */
    val iniDocuments: Map<String, BdsIniDocument>,
    val candidates: Map<BdsOrientation, BdsCandidateLayout>,
    val resources: Map<BdsOrientation, BdsResources>,
    val unsupportedProperties: List<String>
) {
    val portraitPinyin26 get() = layout(BdsOrientation.Portrait, "py_26")
    val portraitNumber26 get() = layout(BdsOrientation.Portrait, "num_26")
    val portraitCandidate get() = candidates[BdsOrientation.Portrait]

    // Compatibility aliases for candidate rendering and theme derivation.
    val styles get() = resources[BdsOrientation.Portrait]?.styles.orEmpty()
    val animationStyles get() = resources[BdsOrientation.Portrait]?.animationStyles.orEmpty()
    val animations get() = resources[BdsOrientation.Portrait]?.animations.orEmpty()

    fun layout(orientation: BdsOrientation, name: String): BdsLayout? =
        layouts[BdsLayoutId(orientation, name.lowercase().removeSuffix(".ini"))]

    fun resources(layout: BdsLayout): BdsResources? = resources[layout.id.orientation]

    fun selectResourceBucket(orientation: BdsOrientation, viewportWidth: Int): Int? =
        resources[orientation]?.resourceBuckets?.keys
            ?.minWithOrNull(compareBy<Int> { kotlin.math.abs(it - viewportWidth) }.thenByDescending { it })

    fun image(orientation: BdsOrientation, name: String, viewportWidth: Int): BdsImage? {
        val resources = resources[orientation] ?: return null
        val key = name.lowercase()
        val bucket = selectResourceBucket(orientation, viewportWidth)
        return bucket?.let { resources.resourceBuckets[it]?.get(key) } ?: resources.images[key]
    }

    fun image(layout: BdsLayout, name: String, viewportWidth: Int): BdsImage? =
        image(layout.id.orientation, name, viewportWidth)

    fun image(name: String, viewportWidth: Int): BdsImage? =
        image(BdsOrientation.Portrait, name, viewportWidth)

    @Deprecated("Use selectResourceBucket(orientation, viewportWidth)")
    fun selectResourceBucket(viewportWidth: Int): Int? = resources[BdsOrientation.Portrait]
        ?.resourceBuckets?.keys
        ?.minWithOrNull(compareBy<Int> { kotlin.math.abs(it - viewportWidth) }.thenByDescending { it })

    /**
     * CAND.VIEW_RECT is the candidate content/interaction rectangle. The candidate
     * background tile may extend beyond it and defines the complete visual surface.
     */
    fun portraitCandidateSurfaceHeight(viewportWidth: Int): Int? {
        return candidateSurfaceHeight(BdsOrientation.Portrait, viewportWidth)
    }

    fun candidateSurfaceHeight(orientation: BdsOrientation, viewportWidth: Int): Int? {
        val candidate = candidates[orientation] ?: return null
        val resourceSet = resources[orientation] ?: return null
        val baseWidth = resourceSet.designWidth
        val contentBottom = candidate.viewRect.y + candidate.viewRect.height
        val backgroundHeight = candidate.backgroundStyle
            ?.let(resourceSet.styles::get)
            ?.let { it.normalImage ?: it.pressedImage }
            ?.let { ref -> image(orientation, ref.atlas, viewportWidth)?.tiles?.get(ref.tile) }
            ?.source?.height
            ?: 0
        val designHeight = max(contentBottom, backgroundHeight)
        return (designHeight * viewportWidth.toFloat() /
            baseWidth).roundToInt()
    }
}

data class BdsIniDocument(
    val path: String,
    val sections: Map<String, Map<String, String>>
)

enum class BdsOrientation { Portrait, Landscape }

data class BdsLayoutId(val orientation: BdsOrientation, val name: String)

data class BdsResources(
    val designWidth: Int,
    val designHeight: Int,
    val panelBackgroundStyle: Int?,
    val offsets: Map<Int, BdsPoint>,
    val styles: Map<Int, BdsStyle>,
    val animationStyles: Map<Int, BdsAnimationStyle>,
    val animations: Map<Int, BdsAnimation>,
    val images: Map<String, BdsImage>,
    val resourceBuckets: Map<Int, Map<String, BdsImage>>
)

data class BdsCandidateLayout(
    val viewRect: BdsRect,
    val definition: BdsCandidateDefinition,
    val properties: Map<String, String>
) {
    val backgroundStyle get() = definition.backgroundStyle
    val foregroundStyle get() = definition.foregroundStyle
}

data class BdsMetadata(
    val name: String,
    val author: String?,
    val description: String?,
    val versionCode: Int?,
    val properties: Map<String, String>
)

data class BdsLayout(
    val id: BdsLayoutId,
    val designWidth: Int,
    val designHeight: Int,
    val backgroundStyle: Int?,
    val decorations: List<BdsKey>,
    val keys: List<BdsKey>,
    val variants: List<BdsKeyVariant>,
    val offsets: Map<Int, BdsPoint>,
    val animationStyle: Int? = null,
    val animationLevel: Int? = null,
    val panelProperties: Map<String, String> = emptyMap(),
    val inputProperties: Map<String, String> = emptyMap(),
    val moreProperties: Map<String, String> = emptyMap(),
    val listProperties: Map<String, String> = emptyMap(),
    val hintProperties: Map<String, String> = emptyMap()
)

data class BdsKeyVariant(
    val section: String,
    val backgroundStyle: Int?,
    val foregroundStyles: List<Int>,
    val positionTypes: List<Int>,
    val actions: Map<BdsDirection, BdsAction>,
    val properties: Map<String, String>,
    val animationStyle: Int? = null,
    val backgroundAnimationStyle: Int? = null,
    val foregroundAnimationStyles: List<Int> = emptyList()
)

data class BdsKey(
    val section: String,
    val viewRect: BdsRect,
    val touchRect: BdsRect?,
    val backgroundStyle: Int?,
    val foregroundStyles: List<Int>,
    val positionTypes: List<Int>,
    val actions: Map<BdsDirection, BdsAction>,
    val properties: Map<String, String>,
    val animationStyle: Int? = null,
    val backgroundAnimationStyle: Int? = null,
    val foregroundAnimationStyles: List<Int> = emptyList()
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
