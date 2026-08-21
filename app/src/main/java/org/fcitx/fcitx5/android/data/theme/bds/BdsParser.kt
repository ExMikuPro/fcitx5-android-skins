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

    fun parse(
        root: File,
        id: String = root.name,
        fallbackName: String = root.name,
        sourcePath: String = root.absolutePath
    ): BdsSkin {
        val infoFile = root.childIgnoreCase("Info.txt")
            ?: throw BdsException("BDS 缺少 Info.txt")
        val demo = root.childIgnoreCase("demo.png")?.takeIf(File::isFile)
        val metadata = BdsSkinMetadataParser.parse(
            infoFile.readBytes(),
            fallbackName.substringBeforeLast('.'),
            demo?.absolutePath,
            id,
            sourcePath
        )

        val unsupported = mutableListOf<String>()
        val parsedOrientations = listOf(
            BdsOrientation.Portrait to "port",
            BdsOrientation.Landscape to "land"
        ).mapNotNull { (orientation, directoryName) ->
            val directory = root.childIgnoreCase(directoryName) ?: return@mapNotNull null
            orientation to parseOrientation(root, directory, orientation, unsupported)
        }.toMap()
        if (parsedOrientations.isEmpty()) {
            throw BdsException("BDS 缺少 port/land 布局目录")
        }
        val layouts = parsedOrientations.values.flatMap { it.layouts.entries }
            .associate { it.toPair() }
        if (layouts.isEmpty()) throw BdsException("BDS 中没有可解析的布局 INI")
        val iniDocuments = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("ini", ignoreCase = true) }
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                relative.startsWith("port/", true) || relative.startsWith("land/", true)
            }
            .associate { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath.lowercase()
                val sections = BdsIni.parse(file).sections.mapValues { it.value.toMap() }
                path to BdsIniDocument(path, sections)
            }

        return BdsSkin(
            id = id,
            rootPath = root.absolutePath,
            metadata = metadata,
            layouts = layouts,
            iniDocuments = iniDocuments,
            candidates = parsedOrientations.mapNotNull { (orientation, parsed) ->
                parsed.candidate?.let { orientation to it }
            }.toMap(),
            resources = parsedOrientations.mapValues { it.value.resources },
            unsupportedProperties = unsupported
        )
    }

    private data class ParsedOrientation(
        val layouts: Map<BdsLayoutId, BdsLayout>,
        val candidate: BdsCandidateLayout?,
        val resources: BdsResources
    )

    private fun parseOrientation(
        root: File,
        directory: File,
        orientation: BdsOrientation,
        unsupported: MutableList<String>
    ): ParsedOrientation {
        val label = if (orientation == BdsOrientation.Portrait) "port" else "land"
        val genFile = directory.childIgnoreCase("gen.ini")
            ?: throw BdsException("BDS 缺少 $label/gen.ini")
        val resourceDirectory = directory.childIgnoreCase("res")
            ?: throw BdsException("BDS 缺少 $label/res 目录")
        val cssFile = resourceDirectory.childIgnoreCase("default.css")
            ?: throw BdsException("BDS 缺少 $label/res/default.css")
        val gen = BdsIni.parse(genFile)
        val panel = gen.section("PANEL").orEmpty()
        val size = parsePair(panel.value("SIZE"))
            ?: throw BdsException("$label/gen.ini 的 PANEL.SIZE 无效")
        val panelBackgroundStyle = panel.value("BACK_STYLE")?.toIntOrNull()
        val offsets = gen.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("OFFSET", ignoreCase = true)) return@mapNotNull null
            val index = section.substring(6).toIntOrNull() ?: return@mapNotNull null
            parsePair(values.value("POS"))?.let { index to BdsPoint(it.first, it.second) }
        }.toMap()
        val candidateSection = gen.section("CAND").orEmpty()
        val candidateViewRect = parseRect(candidateSection.value("VIEW_RECT"))
        val candidateDefinition = candidateSection.value("LAYOUT_NAME")?.let { layoutName ->
            directory.childIgnoreCase("$layoutName.cnd")?.let(BdsCandidateParser::parse)
        }

        val css = BdsIni.parse(cssFile)
        val styles = css.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("STYLE", ignoreCase = true)) return@mapNotNull null
            val styleId = section.substring(5).toIntOrNull() ?: return@mapNotNull null
            styleId to parseStyle(styleId, values)
        }.toMap()
        val animationStyles = css.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("STYLE", ignoreCase = true)) return@mapNotNull null
            val styleId = section.substring(5).toIntOrNull() ?: return@mapNotNull null
            parseAnimationStyle(styleId, values)?.let { styleId to it }
        }.toMap()
        val animations = resourceDirectory.childIgnoreCase("anim.ini")?.let(BdsIni::parse)?.sections
            ?.mapNotNull { (section, values) ->
                if (!section.startsWith("ANIM", ignoreCase = true)) return@mapNotNull null
                val animationId = section.substring(4).toIntOrNull() ?: return@mapNotNull null
                animationId to parseAnimation(animationId, values, unsupported)
            }?.toMap().orEmpty()
        animationStyles.values.forEach { style ->
            listOfNotNull(style.pressAnimationId, style.showAnimationId)
                .plus(style.eventAnimationIds.values)
                .filterNot(animations::containsKey)
                .forEach { unsupported += "STYLE${style.styleId}: missing ANIM$it" }
        }

        val layouts = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("ini", ignoreCase = true) }
            .filterNot { it.nameWithoutExtension.equals("gen", true) || it.nameWithoutExtension.equals("logo", true) }
            .mapNotNull { file ->
                val ini = runCatching { BdsIni.parse(file) }.getOrElse {
                    unsupported += "$label/${file.name}: parse failed: ${it.message}"
                    return@mapNotNull null
                }
                if (ini.section("PANEL") == null) return@mapNotNull null
                val layoutName = file.nameWithoutExtension.lowercase()
                val id = BdsLayoutId(orientation, layoutName)
                id to parseLayout(
                    id, ini, size, offsets, panelBackgroundStyle, unsupported
                )
            }.toMap()
        val imageNames = styles.values.flatMap {
            listOfNotNull(it.normalImage?.atlas, it.pressedImage?.atlas)
        }.toSet()
        val rootRes = root.childIgnoreCase("res")
        val baseResourceDirectories = listOfNotNull(resourceDirectory, rootRes)
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

        return ParsedOrientation(
            layouts = layouts,
            candidate = candidateViewRect?.let { viewRect ->
                val definition = candidateDefinition ?: return@let null
                BdsCandidateLayout(
                    viewRect = viewRect,
                    definition = definition,
                    properties = candidateSection
                )
            },
            resources = BdsResources(
                designWidth = size.first,
                designHeight = size.second,
                panelBackgroundStyle = panelBackgroundStyle,
                offsets = offsets,
                styles = styles,
                animationStyles = animationStyles,
                animations = animations,
                images = images,
                resourceBuckets = resourceBuckets
            )
        )
    }

    private fun parseLayout(
        id: BdsLayoutId,
        ini: BdsIni,
        size: Pair<Int, Int>,
        offsets: Map<Int, BdsPoint>,
        defaultBackgroundStyle: Int?,
        unsupported: MutableList<String>
    ): BdsLayout {
        val panel = ini.section("PANEL").orEmpty()
        val parsedKeys = ini.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("KEY", ignoreCase = true)) return@mapNotNull null
            parseKey(section, values, unsupported)
        }
        val variants = ini.sections.mapNotNull { (section, values) ->
            if (!section.startsWith("TIP", ignoreCase = true)) return@mapNotNull null
            parseKeyVariant(section, values)
        }
        val (keys, decorations) = parsedKeys.partition { it.actions.isNotEmpty() }
        val layoutSize = parsePair(panel.value("SIZE")) ?: size
        return BdsLayout(
            id = id,
            designWidth = layoutSize.first,
            designHeight = layoutSize.second,
            backgroundStyle = panel.value("BACK_STYLE")?.toIntOrNull()
                ?: defaultBackgroundStyle,
            decorations = decorations,
            keys = keys,
            variants = variants,
            offsets = offsets,
            animationStyle = panel.value("ANIM_STYLE")?.toIntOrNull(),
            animationLevel = panel.value("ANIM_LEVEL")?.toIntOrNull(),
            panelProperties = panel,
            inputProperties = ini.section("INPUT").orEmpty(),
            moreProperties = ini.section("MORE").orEmpty(),
            listProperties = ini.section("LIST").orEmpty(),
            hintProperties = ini.section("HINT").orEmpty()
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
            properties = values,
            animationStyle = values.value("ANIM_STYLE")?.toIntOrNull(),
            backgroundAnimationStyle = values.value("BACK_ANIM_STYLE")?.toIntOrNull(),
            foregroundAnimationStyles = parseIntList(values.value("FORE_ANIM_STYLE"))
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

    private fun parseKeyVariant(section: String, values: Map<String, String>) = BdsKeyVariant(
        section = section,
        backgroundStyle = values.value("BACK_STYLE")?.toIntOrNull(),
        foregroundStyles = parseIntList(values.value("FORE_STYLE")),
        positionTypes = parseIntList(values.value("POS_TYPE")),
        actions = directions.mapNotNull { (name, direction) ->
            values.value(name)?.takeIf { it.isNotBlank() }?.let { direction to BdsAction(it) }
        }.toMap(),
        properties = values,
        animationStyle = values.value("ANIM_STYLE")?.toIntOrNull(),
        backgroundAnimationStyle = values.value("BACK_ANIM_STYLE")?.toIntOrNull(),
        foregroundAnimationStyles = parseIntList(values.value("FORE_ANIM_STYLE"))
    )

    private fun parseAnimationStyle(
        styleId: Int,
        values: Map<String, String>
    ): BdsAnimationStyle? {
        val press = values.value("PRESS_ANIM")?.toIntOrNull()
        val show = values.value("SHOW_ANIM")?.toIntOrNull()
        val events = values.mapNotNull { (name, value) ->
            if (!name.startsWith("EVENT", ignoreCase = true)) return@mapNotNull null
            val eventId = name.substring(5).toIntOrNull() ?: return@mapNotNull null
            value.toIntOrNull()?.let { eventId to it }
        }.toMap()
        if (press == null && show == null && events.isEmpty()) return null
        return BdsAnimationStyle(styleId, press, show, events, values)
    }

    private fun parseAnimation(
        id: Int,
        values: Map<String, String>,
        unsupported: MutableList<String>
    ): BdsAnimation {
        val buildList = parseIntList(values.value("BUILD_LIST"))
        if (values.value("BUILD_METHOD") != null || buildList.isNotEmpty()) {
            val rawMethod = values.value("BUILD_METHOD")?.toIntOrNull() ?: -1
            val declaredCount = values.value("BUILD_NUM")?.toIntOrNull()
            if (declaredCount != null && declaredCount != buildList.size) {
                unsupported += "ANIM$id: BUILD_NUM=$declaredCount but BUILD_LIST has ${buildList.size} entries"
            }
            val method = BdsCompositeMethod.fromRaw(rawMethod)
            if (method == null) unsupported += "ANIM$id: unknown BUILD_METHOD=$rawMethod"
            return BdsAnimation.Composite(id, buildList, rawMethod, method, values)
        }
        if (values.value("CATEGORY") != null || values.value("PARTICLE_IMAGE") != null) {
            return BdsAnimation.ParticleEmitter(
                id = id,
                category = values.value("CATEGORY")?.toIntOrNull(),
                location = values.value("LOCATION")?.toIntOrNull(),
                lifeMillis = values.value("LIFE")?.toLongOrNull() ?: 0L,
                emitRegion = parseFloatList(values.value("EMIT_REGION")),
                totalNumber = values.value("TOTAL_NUMBER")?.toIntOrNull() ?: 0,
                birthRate = values.value("BIRTH_RATE")?.toFloatOrNull() ?: 0f,
                emitType = values.value("EMIT_TYPE")?.toIntOrNull(),
                particleStyleIds = parseIntList(values.value("PARTICLE_IMAGE")),
                velocity = parseNumberRange(values.value("VELOCITY")),
                velocityDirection = parseNumberRange(values.value("VELOCITY_DIRECTION")),
                acceleration = parseNumberRange(values.value("ACCELERATION")),
                accelerationDirection = parseNumberRange(values.value("ACCELERATION_DIRECTION")),
                initialScale = parseNumberRange(values.value("INIT_SCALE")),
                scaleSpeed = parseNumberRange(values.value("SCALE_SPEED")),
                initialRotation = parseNumberRange(values.value("INIT_ROTATE")),
                rotationSpeed = parseNumberRange(values.value("ROTATE_SPEED")),
                initialAlpha = parseNumberRange(values.value("INIT_ALPHA")),
                alphaSpeed = parseNumberRange(values.value("ALPHA_SPEED")),
                properties = values
            )
        }
        val rawType = values.value("TYPE")?.toIntOrNull()
        if (rawType != null) {
            val kind = BdsPrimitiveKind.fromRaw(rawType)
            if (kind == null) unsupported += "ANIM$id: unknown TYPE=$rawType"
            val repeatMode = values.value("REPEAT_MODE")?.toIntOrNull()
            if (repeatMode != null && repeatMode !in 0..1) {
                unsupported += "ANIM$id: unknown REPEAT_MODE=$repeatMode"
            }
            val interpolator = values.value("INTPOL")?.toIntOrNull()
            if (interpolator != null && interpolator !in 0..2) {
                unsupported += "ANIM$id: unknown INTPOL=$interpolator"
            }
            return BdsAnimation.Primitive(
                id = id,
                rawType = rawType,
                kind = kind,
                from = parseAnimatedVector(values.value("FROM")),
                to = parseAnimatedVector(values.value("TO")),
                durationMillis = values.value("DURATION")?.toLongOrNull() ?: 0L,
                delayMillis = values.value("DELAY")?.toLongOrNull() ?: 0L,
                repeatMode = repeatMode,
                interpolator = interpolator,
                pivot = parseAnimatedVector(values.value("PIVOT")),
                properties = values
            )
        }
        val reason = if (values.isEmpty()) "empty section" else "unsupported animation fields"
        unsupported += "ANIM$id: $reason"
        return BdsAnimation.Unknown(id, reason, values)
    }

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

    private fun parseFloatList(value: String?): List<Float> =
        value?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()

    private fun parseNumberRange(value: String?): BdsNumberRange? {
        val values = parseFloatList(value)
        return when (values.size) {
            1 -> BdsNumberRange(values[0], values[0])
            2 -> BdsNumberRange(values[0], values[1])
            else -> null
        }
    }

    private fun parseAnimatedVector(value: String?): BdsAnimatedVector? {
        val components = splitAnimationComponents(value ?: return null)
            .mapNotNull(::parseAnimatedNumber)
        if (components.isEmpty()) return null
        return BdsAnimatedVector(components)
    }

    private fun parseAnimatedNumber(value: String): BdsAnimatedNumber? {
        val raw = value.trim()
        val random = Regex("rand\\(([^,]+),([^\\)]+)\\)", RegexOption.IGNORE_CASE)
            .matchEntire(raw)
        if (random != null) {
            val minimum = random.groupValues[1].trim().toFloatOrNull() ?: return null
            val maximum = random.groupValues[2].trim().toFloatOrNull() ?: return null
            return BdsAnimatedNumber.RandomRange(minimum, maximum)
        }
        return raw.toFloatOrNull()?.let(BdsAnimatedNumber::Fixed)
    }

    private fun splitAnimationComponents(value: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        value.forEachIndexed { index, character ->
            when (character) {
                '(' -> depth++
                ')' -> depth = (depth - 1).coerceAtLeast(0)
                ',' -> if (depth == 0) {
                    result += value.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += value.substring(start)
        return result
    }

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
