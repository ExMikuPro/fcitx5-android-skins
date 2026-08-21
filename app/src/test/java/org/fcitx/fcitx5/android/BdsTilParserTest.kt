/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.theme.bds.BdsIconCache
import org.fcitx.fcitx5.android.data.theme.bds.BdsIconFamily
import org.fcitx.fcitx5.android.data.theme.bds.BdsLegacyMenuIcon
import org.fcitx.fcitx5.android.data.theme.bds.BdsTilParser
import org.fcitx.fcitx5.android.data.theme.bds.BdsToolbarAction
import org.fcitx.fcitx5.android.data.theme.bds.BdsToolbarIconMapping
import org.fcitx.fcitx5.android.data.theme.bds.findBdsIconFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class BdsTilParserTest {
    private val temp = File(System.getProperty("java.io.tmpdir"), "bds-til-${UUID.randomUUID()}")
        .also(File::mkdirs)

    @After
    fun cleanup() {
        temp.deleteRecursively()
    }

    @Test
    fun parsesBomAndZeroBasedTiles() {
        val sheet = BdsTilParser.parse(
            ("\uFEFF[GLOBAL]\nTILE_NUM=2\nUSE_ALPHA=1\n" +
                "[IMG0]\nSOURCE_RECT=0,0,72,72\n" +
                "[IMG1]\nSOURCE_RECT=72,0,72,72\n").encodeToByteArray(),
            144,
            72
        )

        assertEquals(2, sheet?.declaredTileCount)
        assertEquals(setOf(0, 1), sheet?.tiles?.keys)
        assertTrue(sheet?.useAlpha == true)
    }

    @Test
    fun parsesOneBasedTiles() {
        val sheet = BdsTilParser.parse(
            ("[GLOBAL]\nTILE_NUM=2\n" +
                "[IMG1]\nSOURCE_RECT=0,0,10,10\n" +
                "[IMG2]\nSOURCE_RECT=10,0,10,10\n").encodeToByteArray(),
            20,
            10
        )

        assertEquals(setOf(1, 2), sheet?.tiles?.keys)
    }

    @Test
    fun rejectsMissingAndCorruptTil() {
        assertNull(BdsTilParser.parse(File("does-not-exist.til"), 10, 10))
        assertNull(BdsTilParser.parse("[GLOBAL]\nTILE_NUM=nope".encodeToByteArray(), 10, 10))
    }

    @Test
    fun missingPngOrTilDoesNotCreateAnIconResource() {
        val logo = File(temp, "res/logo").also(File::mkdirs)
        assertNull(findBdsIconFiles(temp, BdsIconFamily.Toolbar))
        File(logo, "pop_menu_icons.png").writeBytes(byteArrayOf(1))
        assertNull(findBdsIconFiles(temp, BdsIconFamily.Toolbar))
        File(logo, "pop_menu_icons.til").writeText("[GLOBAL]\nTILE_NUM=1")
        assertTrue(findBdsIconFiles(temp, BdsIconFamily.Toolbar) != null)
    }

    @Test
    fun ignoresOutOfBoundsAndNonPositiveRects() {
        val sheet = BdsTilParser.parse(
            ("[GLOBAL]\nTILE_NUM=3\n" +
                "[IMG0]\nSOURCE_RECT=0,0,10,10\n" +
                "[IMG1]\nSOURCE_RECT=9,0,2,10\n" +
                "[IMG2]\nSOURCE_RECT=0,0,0,10\n").encodeToByteArray(),
            10,
            10
        )

        assertEquals(setOf(0), sheet?.tiles?.keys)
    }

    @Test
    fun unknownToolbarActionHasNoReplacement() {
        assertEquals(6, BdsToolbarIconMapping.iconId(BdsToolbarAction.Settings))
        assertNull(BdsToolbarIconMapping.iconId(BdsToolbarAction.Clipboard))
        assertEquals(18, BdsToolbarIconMapping.iconId(BdsToolbarAction.Handwriting))
        assertEquals(25, BdsToolbarIconMapping.iconId(BdsToolbarAction.TextEditing))
        assertEquals(26, BdsToolbarIconMapping.iconId(BdsToolbarAction.Emoji))
        assertEquals(29, BdsToolbarIconMapping.iconId(BdsToolbarAction.VoiceInput))
        assertEquals(36, BdsToolbarIconMapping.iconId(BdsToolbarAction.InputMethod))
        assertNull(BdsToolbarIconMapping.iconId(BdsToolbarAction.Undo))
        assertEquals(17, BdsToolbarIconMapping.iconId(BdsToolbarAction.MoreMenu))
    }

    @Test
    fun legacyToolbarAbiDefinesEvery41TileSlotExactlyOnce() {
        assertEquals((0..40).toSet(), BdsLegacyMenuIcon.entries.map { it.id }.toSet())
        assertEquals("CLICK_INDEX_FEEDBACK", BdsLegacyMenuIcon.fromId(19)?.baiduFunction)
        assertEquals("CLICK_INDEX_EMOJI", BdsLegacyMenuIcon.fromId(26)?.baiduFunction)
        assertNull(BdsLegacyMenuIcon.fromId(41))
    }

    @Test
    fun switchingSkinClearsCachedIcons() {
        var loads = 0
        val cache = BdsIconCache<String> { family, icon ->
            loads++
            "$family:$icon:$loads"
        }
        cache.activate("skin-a")
        assertEquals(cache.get(BdsIconFamily.Toolbar, 2), cache.get(BdsIconFamily.Toolbar, 2))
        assertEquals(1, loads)
        cache.activate("skin-b")
        cache.get(BdsIconFamily.Toolbar, 2)
        assertEquals(2, loads)
        assertEquals(1, cache.size())
        cache.activate(null)
        assertNull(cache.get(BdsIconFamily.Toolbar, 2))
        assertEquals(0, cache.size())
    }

    @Test
    fun missingIconResultIsAlsoCached() {
        var loads = 0
        val cache = BdsIconCache<String> { _, _ ->
            loads++
            null
        }
        cache.activate("skin-a")
        assertNull(cache.get(BdsIconFamily.Toolbar, 999))
        assertNull(cache.get(BdsIconFamily.Toolbar, 999))
        assertEquals(1, loads)
    }

    @Test
    fun parsesAll41ToolbarIconsFromProvidedSkinWhenAvailable() {
        val til = sequenceOf(
            File("aef13cdad73a5b480cb57076dd53a050/res/logo/pop_menu_icons.til"),
            File("../aef13cdad73a5b480cb57076dd53a050/res/logo/pop_menu_icons.til"),
            File("local-testdata/target/res/logo/pop_menu_icons.til")
        ).firstOrNull(File::isFile)
        assumeTrue(til != null)

        val sheet = BdsTilParser.parse(requireNotNull(til), 2948, 72)
        assertEquals(41, sheet?.declaredTileCount)
        assertEquals((0..40).toSet(), sheet?.tiles?.keys)
    }
}
