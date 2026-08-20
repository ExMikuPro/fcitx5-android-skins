/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.theme.bds.BdsArchiveReader
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimatedNumber
import org.fcitx.fcitx5.android.data.theme.bds.BdsAnimation
import org.fcitx.fcitx5.android.data.theme.bds.BdsCompositeMethod
import org.fcitx.fcitx5.android.data.theme.bds.BdsCandidateParser
import org.fcitx.fcitx5.android.data.theme.bds.BdsException
import org.fcitx.fcitx5.android.data.theme.bds.BdsParser
import org.fcitx.fcitx5.android.data.theme.bds.BdsOrientation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BdsParserTest {
    private val temp = File(System.getProperty("java.io.tmpdir"), "bds-test-${UUID.randomUUID()}")
        .also { it.mkdirs() }

    @After
    fun cleanup() {
        temp.deleteRecursively()
    }

    @Test
    fun parsesMinimalBdsAndCaseInsensitivePort() {
        val root = minimalSkin("Port", extraKeyProperty = "FUTURE_PROPERTY=ok")
        val skin = BdsParser.parse(root, "test")
        val pinyin = requireNotNull(skin.portraitPinyin26)
        assertEquals("Test Skin", skin.metadata.name)
        assertEquals("Tester", skin.metadata.author)
        assertEquals(1080, pinyin.designWidth)
        assertEquals("q", pinyin.keys.single().actions.values.single().raw)
        assertTrue(skin.unsupportedProperties.any { it.contains("FUTURE_PROPERTY") })
    }

    @Test(expected = BdsException::class)
    fun missingInfoIsRejected() {
        val root = minimalSkin("port")
        File(root, "Info.txt").delete()
        BdsParser.parse(root)
    }

    @Test
    fun missingResourceIsReportedWithoutRejectingSkin() {
        val root = minimalSkin("port", imageStyle = true)
        val skin = BdsParser.parse(root)
        assertTrue(skin.unsupportedProperties.any { it.contains("missing image") })
    }

    @Test
    fun parsesCandidateSectionsWithoutDiscardingUnknownFields() {
        val file = File(temp, "cand1.cnd").apply {
            writeText(
                """
                [CAND]
                BACK_STYLE=117
                FORE_STYLE=160
                CELL_STYLE=132
                PADDING=0,0,165,1
                FIRST_GAP=27
                FIRST_FORE=210
                CELL_W=66
                FUTURE_FIELD=kept
                [SWITCH]
                NML_FONT_STYLE=123
                SEL_FONT_STYLE=143
                PADDING=0
                [ICON4]
                FORE_STYLE=247
                SIZE=151,128
                ANCHOR_TYPE=6
                POS=-162,-54
                KEY=F9
                PERSIST=2
                STAT_STYLE=S9_1
                [TIP1]
                FORE_STYLE=107
                KEY=F8
                """.trimIndent()
            )
        }
        val candidate = BdsCandidateParser.parse(file)
        assertEquals(117, candidate.backgroundStyle)
        assertEquals(165, candidate.padding.right)
        assertEquals(27, candidate.firstGap)
        assertEquals(210, candidate.firstForegroundStyle)
        assertEquals(66, candidate.cellWidth)
        assertEquals(143, candidate.switch?.selectedFontStyle)
        assertEquals(6, candidate.icons.single().anchorType)
        assertEquals(-162, candidate.icons.single().position?.x)
        assertEquals("S9_1", candidate.icons.single().stateStyle)
        assertEquals("F8", candidate.tips.single().key)
        assertEquals("kept", candidate.sections["CAND"]?.get("FUTURE_FIELD"))
    }

    @Test
    fun parsesProceduralAnimationChainsAndRandomValues() {
        val root = minimalSkin("port")
        val port = File(root, "port")
        File(port, "py_26.ini").writeText(
            """
            [PANEL]
            KEY_NUM=1
            ANIM_STYLE=315
            ANIM_LEVEL=1
            [KEY1]
            VIEW_RECT=0,0,100,100
            CENTER=q
            BACK_ANIM_STYLE=263
            FORE_STYLE=1,2
            FORE_ANIM_STYLE=263,328
            """.trimIndent()
        )
        val res = File(port, "ReS")
        File(res, "default.css").writeText(
            """
            [STYLE263]
            PRESS_ANIM=5
            [STYLE315]
            SHOW_ANIM=23
            EVENT1=23
            [STYLE328]
            PRESS_ANIM=34
            """.trimIndent()
        )
        File(res, "anim.ini").writeText(
            """
            [GLOBAL]
            ANIM_NUM=60
            [ANIM5]
            TYPE=4
            FROM=100,100
            TO=125,125
            DURATION=125
            REPEAT_MODE=1
            INTPOL=2
            PIVOT=50,90
            [ANIM23]
            CATEGORY=3
            LOCATION=1
            LIFE=5000
            EMIT_REGION=0,0,1,1
            TOTAL_NUMBER=250
            BIRTH_RATE=25
            EMIT_TYPE=0
            PARTICLE_IMAGE=317,318
            VELOCITY=30,60
            [ANIM34]
            BUILD_NUM=2
            BUILD_LIST=35,36
            BUILD_METHOD=0
            [ANIM35]
            TYPE=2
            FROM=rand(50,80),20
            TO=rand(150,250),-150
            DURATION=250
            [ANIM36]
            TYPE=1
            FROM=rand(-45,45)
            TO=rand(-90,90)
            DURATION=250
            """.trimIndent()
        )

        val skin = BdsParser.parse(root)
        val pinyin = requireNotNull(skin.portraitPinyin26)
        assertEquals(315, pinyin.animationStyle)
        assertEquals(263, pinyin.keys.single().backgroundAnimationStyle)
        assertEquals(listOf(263, 328), pinyin.keys.single().foregroundAnimationStyles)
        assertEquals(5, skin.animationStyles.getValue(263).pressAnimationId)
        assertEquals(23, skin.animationStyles.getValue(315).eventAnimationIds[1])

        val scale = skin.animations.getValue(5) as BdsAnimation.Primitive
        assertEquals(4, scale.rawType)
        assertEquals(125L, scale.durationMillis)
        val translation = skin.animations.getValue(35) as BdsAnimation.Primitive
        assertTrue(translation.from!!.components.first() is BdsAnimatedNumber.RandomRange)
        val composite = skin.animations.getValue(34) as BdsAnimation.Composite
        assertEquals(BdsCompositeMethod.Parallel, composite.method)
        val emitter = skin.animations.getValue(23) as BdsAnimation.ParticleEmitter
        assertEquals(listOf(317, 318), emitter.particleStyleIds)
        assertEquals(5000L, emitter.lifeMillis)
    }

    @Test(expected = BdsException::class)
    fun corruptNonZipIsRejected() {
        val archive = File(temp, "bad.bds").apply { writeText("not a zip") }
        BdsArchiveReader.extract(archive, File(temp, "bad-out"))
    }

    @Test(expected = BdsException::class)
    fun zipSlipIsRejected() {
        val archive = File(temp, "slip.bds")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped.txt"))
            zip.write("bad".encodeToByteArray())
            zip.closeEntry()
        }
        BdsArchiveReader.extract(archive, File(temp, "slip-out"))
    }

    @Test
    fun parsesGoldenSampleMetadataWhenAvailable() {
        val archive = sequenceOf(
            File("local-testdata/golden-sample/aef13cdad73a5b480cb57076dd53a050.bds"),
            File("../local-testdata/golden-sample/aef13cdad73a5b480cb57076dd53a050.bds"),
            File("aef13cdad73a5b480cb57076dd53a050.bds"),
            File("local-testdata/target.bds"),
            File("../local-testdata/target.bds"),
            File("/home/linsiling/Desktop/8f1c13c12d35742fa0fd249e54412117.bds")
        ).firstOrNull { it.isFile }
        assumeTrue(archive != null)
        val root = File(temp, "golden")
        BdsArchiveReader.extract(requireNotNull(archive), root)
        val skin = BdsParser.parse(root, "golden")
        val pinyin = requireNotNull(skin.portraitPinyin26)
        assertEquals("初音未来", skin.metadata.name)
        assertEquals(35, pinyin.keys.size)
        assertEquals(2, pinyin.decorations.size)
        assertEquals(116, pinyin.backgroundStyle)
        assertEquals("1", skin.portraitNumber26?.keys?.first()?.actions?.values?.first()?.raw)
        assertEquals(116, skin.portraitNumber26?.backgroundStyle)
        assertEquals(2, skin.portraitNumber26?.decorations?.size)
        assertEquals(1080, skin.selectResourceBucket(BdsOrientation.Portrait, 1080))
        assertEquals("btn", skin.image("btn", 1080)?.name)
        assertEquals(117, skin.portraitCandidate?.backgroundStyle)
        assertEquals("cand", skin.styles[117]?.normalImage?.atlas)
        assertEquals(158, skin.image("cand", 1080)?.tiles?.get(1)?.source?.height)
        assertEquals(121, skin.portraitCandidate?.viewRect?.height)
        assertEquals(158, skin.portraitCandidateSurfaceHeight(1080))
        assertEquals(79, skin.portraitCandidateSurfaceHeight(540))
        assertEquals(42, skin.animations.size)
        assertEquals(23, skin.animationStyles.getValue(315).showAnimationId)
        val panelEmitter = skin.animations.getValue(23) as BdsAnimation.ParticleEmitter
        assertEquals(listOf(317, 318, 319, 320, 321, 322), panelEmitter.particleStyleIds)
        assertEquals(250, panelEmitter.totalNumber)
        assertEquals(5, skin.animationStyles.getValue(263).pressAnimationId)
        assertEquals(59, skin.layouts.size)
        assertEquals(26, skin.layouts.keys.count { it.orientation == BdsOrientation.Portrait })
        assertEquals(33, skin.layouts.keys.count { it.orientation == BdsOrientation.Landscape })
        assertEquals(67, skin.iniDocuments.size)
        assertTrue("port/gen.ini" in skin.iniDocuments)
        assertTrue("port/logo.ini" in skin.iniDocuments)
        assertTrue("port/res/event.ini" in skin.iniDocuments)
        assertTrue("land/res/anim.ini" in skin.iniDocuments)
        assertEquals(1920, skin.layout(BdsOrientation.Landscape, "py_26")?.designWidth)
        assertEquals(766, skin.layout(BdsOrientation.Portrait, "en_26_h")?.designHeight)
        assertEquals("symbol_h", skin.layout(BdsOrientation.Portrait, "en_26_h")
            ?.moreProperties?.get("SYM_LAYOUT"))
        assertTrue(skin.unsupportedProperties.none { it.contains("missing image btn") })
    }

    private fun minimalSkin(
        portName: String,
        extraKeyProperty: String = "",
        imageStyle: Boolean = false
    ): File {
        val root = File(temp, UUID.randomUUID().toString()).also { it.mkdirs() }
        File(root, "Info.txt").writeText("\uFEFFName=Test Skin\nAuthor=Tester\nVersionCode=1\n")
        val port = File(root, portName).also { it.mkdirs() }
        File(port, "gen.ini").writeText("[PANEL]\nSIZE=1080,655\nBACK_STYLE=1\n")
        File(port, "py_26.ini").writeText(
            "[PANEL]\nKEY_NUM=1\n[KEY1]\nVIEW_RECT=0,0,100,100\nCENTER=q\n" +
                extraKeyProperty + "\n"
        )
        val res = File(port, "ReS").also { it.mkdirs() }
        File(res, "default.css").writeText(
            if (imageStyle) "[STYLE1]\nNM_IMG=missing,1\n"
            else "[STYLE1]\nNM_COLOR=ffffff\n"
        )
        return root
    }
}
