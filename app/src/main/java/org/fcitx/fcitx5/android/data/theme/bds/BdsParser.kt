/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import java.io.File

object BdsParser {
    private val directions = linkedMapOf(
        "CENTER" to BdsDirection.Center,
        "UP" to BdsDirection.Up,
        "DOWN" to BdsDirection.Down,
        "LEFT" to BdsDirection.Left,
        "RIGHT" to BdsDirection.Right,
        "HOLD" to BdsDirection.Hold,
        "HOLDSYM" to BdsDirection.Hold
    )

    fun parse(root: File, id: String = root.name): BdsSkin {
        val infoFile = root.childIgnoreCase("Info.txt")
            ?: throw BdsException("BDS 缺少 Info.txt")
        val info = parseFlatProperties(infoFile)
        val name = info.value("Name")?.takeIf { it.isNotBlank() }
            ?: throw BdsException("Info.txt 缺少皮肤名称")
        val metadata = BdsMetadata(
            name = name,
            author = info.value("Author")?.takeIf { it.isNotBlank() },
            description = info.value("Description")?.takeIf { it.isNotBlank() },
            versionCode = info.value("VersionCode")?.toIntOrNull(),
            properties = info
        )

        val port = root.childIgnoreCase("port")
            ?: throw BdsException("BDS 缺少 port/Port 目录")
        val layoutFile = port.childIgnoreCase("py_26.ini")
            ?: throw BdsException("BDS 缺少竖屏 py_26.ini")
        val genFile = port.childIgnoreCase("gen.ini")
            ?: throw BdsException("BDS 缺少竖屏 gen.ini")
        val portRes = port.childIgnoreCase("res")
            ?: throw BdsException("BDS 缺少 port/res 目录")
        val cssFile = portRes.childIgnoreCase("default.css")
            ?: throw BdsException("BDS 缺少 port/res/default.css")

        val gen = BdsIni.parse(genFile)
        val panel = gen.section("PANEL").orEmpty()
        val size = parsePair(panel.value("SIZE"))
            ?: throw BdsException("gen.ini 的 PANEL.SIZE 无效")
        val offsets = gen.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("OFFSET", ignoreCase = true)) return@mapNotNull null
            val index = section.substring(6).toIntOrNull() ?: return@mapNotNull null
            parsePair(values.value("POS"))?.let { index to BdsPoint(it.first, it.second) }
        }.toMap()
        val candidateSection = gen.section("CAND").orEmpty()
        val candidateViewRect = parseRect(candidateSection.value("VIEW_RECT"))
        val candidateConfig = candidateSection.value("LAYOUT_NAME")?.let { layoutName ->
            port.childIgnoreCase("$layoutName.cnd")?.let(BdsIni::parse)?.section("CAND").orEmpty()
        }.orEmpty()

        val css = BdsIni.parse(cssFile)
        val styles = css.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("STYLE", ignoreCase = true)) return@mapNotNull null
            val styleId = section.substring(5).toIntOrNull() ?: return@mapNotNull null
            styleId to parseStyle(styleId, values)
        }.toMap()

        val ini = BdsIni.parse(layoutFile)
        val unsupported = mutableListOf<String>()
        val parsedKeys = ini.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("KEY", ignoreCase = true)) return@mapNotNull null
            parseKey(section, values, unsupported)
        }
        val (keys, decorations) = parsedKeys.partition { it.actions.isNotEmpty() }
        val imageNames = styles.values.flatMap {
            listOfNotNull(it.normalImage?.atlas, it.pressedImage?.atlas)
        }.toSet()
        val rootRes = root.childIgnoreCase("res")
        val baseResourceDirectories = listOfNotNull(portRes, rootRes)
        val images = imageNames.mapNotNull { imageName ->
            parseImage(baseResourceDirectories, imageName)?.let { imageName.lowercase() to it }
        }.toMap()
        val resourceBuckets = root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.toIntOrNull() != null }
            .mapNotNull { bucketDirectory ->
                val width = bucketDirectory.name.toIntOrNull() ?: return@mapNotNull null
                val bucketRes = bucketDirectory.childIgnoreCase("res") ?: return@mapNotNull null
                val bucketImages = imageNames.mapNotNull { imageName ->
                    parseImage(listOf(bucketRes), imageName)?.let { imageName.lowercase() to it }
                }.toMap()
                width.takeIf { bucketImages.isNotEmpty() }?.let { it to bucketImages }
            }.toMap()
        styles.values.forEach { style ->
            listOfNotNull(style.normalImage, style.pressedImage).forEach { ref ->
                val key = ref.atlas.lowercase()
                val found = images[key]?.tiles?.containsKey(ref.tile) == true ||
                    resourceBuckets.values.any { it[key]?.tiles?.containsKey(ref.tile) == true }
                if (!found) {
                    unsupported += "STYLE${style.id}: missing image ${ref.atlas},${ref.tile}"
                }
            }
        }

        return BdsSkin(
            id = id,
            rootPath = root.absolutePath,
            metadata = metadata,
            portraitPinyin26 = BdsLayout(
                designWidth = size.first,
                designHeight = size.second,
                backgroundStyle = panel.value("BACK_STYLE")?.toIntOrNull(),
                decorations = decorations,
                keys = keys,
                offsets = offsets
            ),
            portraitCandidate = candidateViewRect?.let {
                BdsCandidateLayout(
                    viewRect = it,
                    backgroundStyle = candidateConfig.value("BACK_STYLE")?.toIntOrNull(),
                    foregroundStyle = candidateConfig.value("FORE_STYLE")?.toIntOrNull(),
                    properties = candidateSection + candidateConfig
                )
            },
            styles = styles,
            images = images,
            resourceBuckets = resourceBuckets,
            unsupportedProperties = unsupported
        )
    }

    private fun parseKey(
        section: String,
        values: Map<String, String>,
        unsupported: MutableList<String>
    ): BdsKey? {
        val viewRect = parseRect(values.value("VIEW_RECT")) ?: return null
        val actions = directions.mapNotNull { (name, direction) ->
            values.value(name)?.takeIf { it.isNotBlank() }?.let { direction to BdsAction(it) }
        }.toMap()
        val known = setOf(
            "BACK_STYLE", "FORE_STYLE", "POS_TYPE", "VIEW_RECT", "TOUCH_RECT",
            "CENTER", "UP", "DOWN", "LEFT", "RIGHT", "HOLD", "HOLDSYM",
            "ANIM_STYLE", "FORE_ANIM_STYLE", "BACK_ANIM_STYLE", "STAT_STYLE", "SOUND_STYLE"
        )
        values.forEach { (key, value) ->
            if (known.none { it.equals(key, ignoreCase = true) }) {
                unsupported += "$section: unsupported property $key=$value"
            }
        }
        return BdsKey(
            section = section,
            viewRect = viewRect,
            touchRect = parseRect(values.value("TOUCH_RECT")),
            backgroundStyle = values.value("BACK_STYLE")?.toIntOrNull(),
            foregroundStyles = parseIntList(values.value("FORE_STYLE")),
            positionTypes = parseIntList(values.value("POS_TYPE")),
            actions = actions,
            properties = values
        )
    }

    private fun parseStyle(id: Int, values: Map<String, String>) = BdsStyle(
        id = id,
        normalImage = parseImageRef(values.value("NM_IMG")),
        pressedImage = parseImageRef(values.value("HL_IMG")),
        normalColor = parseColor(values.value("NM_COLOR")),
        pressedColor = parseColor(values.value("HL_COLOR")),
        fontSize = values.value("FONT_SIZE")?.toFloatOrNull(),
        fontWeight = values.value("FONT_WEIGHT")?.toIntOrNull(),
        text = values.value("SHOW"),
        properties = values
    )

    private fun parseImage(resourceDirectories: List<File>, name: String): BdsImage? {
        val pair = resourceDirectories.firstNotNullOfOrNull { res ->
            val til = res.childIgnoreCase("$name.til") ?: return@firstNotNullOfOrNull null
            val image = res.childIgnoreCase("$name.png") ?: res.childIgnoreCase("$name.gif")
                ?: return@firstNotNullOfOrNull null
            til to image
        } ?: return null
        val (til, png) = pair
        val ini = BdsIni.parse(til)
        val global = ini.section("GLOBAL").orEmpty()
        val tiles = ini.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("IMG", ignoreCase = true)) return@mapNotNull null
            val index = section.substring(3).toIntOrNull() ?: return@mapNotNull null
            val source = parseRect(values.value("SOURCE_RECT")) ?: return@mapNotNull null
            index to BdsTile(source, parseRect(values.value("INNER_RECT")))
        }.toMap()
        return BdsImage(name, png.absolutePath, global.value("USE_ALPHA") == "1", tiles)
    }

    private fun parseFlatProperties(file: File): Map<String, String> =
        BdsIni.parse(file).sections[""].orEmpty()

    private fun parseImageRef(value: String?): BdsImageRef? {
        val parts = value?.split(',')?.map { it.trim() } ?: return null
        if (parts.size < 2) return null
        return BdsImageRef(parts[0], parts[1].toIntOrNull() ?: return null)
    }

    private fun parseRect(value: String?): BdsRect? {
        val p = value?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
        if (p.size != 4 || p.any { it == null }) return null
        return BdsRect(p[0]!!, p[1]!!, p[2]!!, p[3]!!)
    }

    private fun parsePair(value: String?): Pair<Int, Int>? {
        val p = value?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
        if (p.size != 2 || p.any { it == null }) return null
        return p[0]!! to p[1]!!
    }

    private fun parseIntList(value: String?): List<Int> =
        value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

    private fun parseColor(value: String?): Int? {
        val raw = value?.trim()?.removePrefix("#") ?: return null
        return runCatching {
            when (raw.length) {
                6 -> (0xff000000L or raw.toLong(16)).toInt()
                8 -> raw.toLong(16).toInt()
                else -> return null
            }
        }.getOrNull()
    }

    private fun Map<String, String>.value(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
