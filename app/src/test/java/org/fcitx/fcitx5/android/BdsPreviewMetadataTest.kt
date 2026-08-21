/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.theme.bds.BdsArchiveReader
import org.fcitx.fcitx5.android.data.theme.bds.BdsException
import org.fcitx.fcitx5.android.data.theme.bds.BdsPngInspector
import org.fcitx.fcitx5.android.data.theme.bds.BdsPreviewMemoryCache
import org.fcitx.fcitx5.android.data.theme.bds.BdsPreviewSource
import org.fcitx.fcitx5.android.data.theme.bds.BdsSkinMetadataParser
import org.fcitx.fcitx5.android.data.theme.bds.bdsPreviewCacheKey
import org.fcitx.fcitx5.android.data.theme.bds.readBdsPreviewSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BdsPreviewMetadataTest {
    private val temp = File(
        System.getProperty("java.io.tmpdir"), "bds-preview-${UUID.randomUUID()}"
    ).also(File::mkdirs)

    @After
    fun cleanup() {
        temp.deleteRecursively()
    }

    @Test
    fun readsProvidedSkinNameAnd480By385PreviewWhenAvailable() {
        val archive = sampleArchive()
        assumeTrue(archive != null)
        val entries = BdsArchiveReader.readRootEntries(
            requireNotNull(archive), setOf("Info.txt", "demo.png")
        )
        val metadata = BdsSkinMetadataParser.parse(
            requireNotNull(entries["Info.txt"]), "fallback", "demo.png", "sample", archive.path
        )

        assertEquals("初音未来", metadata.name)
        assertEquals(480 to 385, BdsPngInspector.dimensions(requireNotNull(entries["demo.png"])))
        assertEquals("demo.png", metadata.previewPath)
        assertEquals(archive.path, metadata.sourcePath)
    }

    @Test
    fun parsesBomAndIgnoresEmptyOptionalFields() {
        val metadata = BdsSkinMetadataParser.parse(
            "\uFEFFDescription=\nName=初音未来\nUnknown=x\nAuthor=\nVersionCode=1"
                .encodeToByteArray(),
            "fallback", null, "id", "/skin.bds"
        )

        assertEquals("初音未来", metadata.name)
        assertNull(metadata.author)
        assertNull(metadata.description)
        assertEquals(1, metadata.versionCode)
        assertEquals("x", metadata.properties["Unknown"])
    }

    @Test
    fun emptyNameFallsBackToArchiveFileName() {
        val metadata = BdsSkinMetadataParser.parse(
            "Name=  \nVersionCode=broken".encodeToByteArray(),
            "my-skin", null, "id", "/my-skin.bds"
        )

        assertEquals("my-skin", metadata.name)
        assertNull(metadata.versionCode)
    }

    @Test
    fun missingAndDamagedPreviewAreNonFatal() {
        val archive = zipOf("Info.txt" to "Name=No Preview".encodeToByteArray())
        val entries = BdsArchiveReader.readRootEntries(
            archive, setOf("Info.txt", "demo.png")
        )
        assertTrue("demo.png" !in entries)
        assertEquals(BdsPreviewSource.Missing, readBdsPreviewSource(archive))
        assertNull(BdsPngInspector.dimensions("not a png".encodeToByteArray()))
        val corrupt = zipOf(
            "Info.txt" to "Name=Broken".encodeToByteArray(),
            "demo.png" to "not a png".encodeToByteArray()
        )
        assertEquals(BdsPreviewSource.Error, readBdsPreviewSource(corrupt))
    }

    @Test
    fun fileReplacementChangesPreviewCacheKey() {
        val archive = zipOf("Info.txt" to "Name=A".encodeToByteArray())
        val first = bdsPreviewCacheKey(archive)
        archive.appendBytes(byteArrayOf(0))
        archive.setLastModified(archive.lastModified() + 2_000)
        val second = bdsPreviewCacheKey(archive)

        assertEquals(first.pathHash, second.pathHash)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(first.fileName, second.fileName)
    }

    @Test
    fun repeatedFastBindingsReuseMemoryResult() {
        val cache = BdsPreviewMemoryCache<String>(16) { 1 }
        var decodes = 0
        repeat(500) {
            if (cache.get("same-file-fingerprint") == null) {
                decodes++
                cache.put("same-file-fingerprint", "thumbnail")
            }
        }

        assertEquals(1, decodes)
    }

    @Test(expected = BdsException::class)
    fun selectiveRootReaderStillRejectsZipTraversal() {
        val archive = zipOf(
            "Info.txt" to "Name=Safe".encodeToByteArray(),
            "../demo.png" to byteArrayOf(1)
        )
        BdsArchiveReader.readRootEntries(archive, setOf("Info.txt", "demo.png"))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File =
        File(temp, "${UUID.randomUUID()}.bds").also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private fun sampleArchive(): File? = sequenceOf(
        File("aef13cdad73a5b480cb57076dd53a050.zip"),
        File("aef13cdad73a5b480cb57076dd53a050.bds"),
        File("local-testdata/golden-sample/aef13cdad73a5b480cb57076dd53a050.bds"),
        File("../aef13cdad73a5b480cb57076dd53a050.zip")
    ).firstOrNull(File::isFile)
}
