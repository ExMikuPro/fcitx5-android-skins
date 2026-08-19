/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.theme.bds.BdsArchiveReader
import org.fcitx.fcitx5.android.data.theme.bds.BdsException
import org.fcitx.fcitx5.android.data.theme.bds.BdsParser
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
        assertEquals("Test Skin", skin.metadata.name)
        assertEquals("Tester", skin.metadata.author)
        assertEquals(1080, skin.portraitPinyin26.designWidth)
        assertEquals("q", skin.portraitPinyin26.keys.single().actions.values.single().raw)
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
            File("local-testdata/target.bds"),
            File("../local-testdata/target.bds"),
            File("/home/linsiling/Desktop/8f1c13c12d35742fa0fd249e54412117.bds")
        ).firstOrNull { it.isFile }
        assumeTrue(archive != null)
        val root = File(temp, "golden")
        BdsArchiveReader.extract(requireNotNull(archive), root)
        val skin = BdsParser.parse(root, "golden")
        assertEquals("初音未来", skin.metadata.name)
        assertEquals(35, skin.portraitPinyin26.keys.size)
        assertEquals(2, skin.portraitPinyin26.decorations.size)
        assertEquals(1080, skin.selectResourceBucket(1080))
        assertEquals("btn", skin.image("btn", 1080)?.name)
        assertEquals(117, skin.portraitCandidate?.backgroundStyle)
        assertEquals("cand", skin.styles[117]?.normalImage?.atlas)
        assertEquals(158, skin.image("cand", 1080)?.tiles?.get(1)?.source?.height)
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
