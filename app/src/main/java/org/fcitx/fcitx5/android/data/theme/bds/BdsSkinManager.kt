/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

object BdsSkinManager {
    data class InstalledSkin(
        val id: String,
        val themeName: String,
        val name: String,
        val author: String?,
        val directory: File
    )

    private val rootDir: File by lazy {
        File(appContext.filesDir, "skins/bds").also { it.mkdirs() }
    }
    private val parsedCache = mutableMapOf<String, BdsSkin>()

    fun listInstalled(): List<InstalledSkin> =
        rootDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.mapNotNull(::readRecord).orEmpty()

    fun skinForTheme(themeName: String): BdsSkin? {
        val record = listInstalled().firstOrNull { it.themeName == themeName } ?: return null
        return parsedCache.getOrPut(record.id) {
            BdsParser.parse(File(record.directory, "extracted"), record.id).also(::logUnsupported)
        }
    }

    fun import(input: InputStream, sourceName: String?): Pair<InstalledSkin, Theme.Custom> {
        if (sourceName != null && !sourceName.endsWith(".bds", ignoreCase = true)) {
            throw BdsException("请选择 .bds 文件")
        }
        val staging = File(rootDir, ".import-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            val archive = File(staging, "original.bds")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            input.buffered().use { source ->
                archive.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > BdsArchiveReader.MAX_ARCHIVE_BYTES) {
                            throw BdsException("BDS 文件过大")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!BdsArchiveReader.hasZipMagic(archive)) {
                throw BdsException("所选文件不是有效的 ZIP/BDS 文件")
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = File(rootDir, id)
            if (existing.isDirectory) {
                val record = readRecord(existing)
                    ?: throw BdsException("已安装的 BDS 索引损坏")
                val skin = skinForTheme(record.themeName)
                    ?: throw BdsException("已安装的 BDS 无法解析")
                return record to themeFor(record, skin)
            }
            val extracted = File(staging, "extracted")
            BdsArchiveReader.extract(archive, extracted)
            val parsed = BdsParser.parse(extracted, id)
            val baseThemeName = "${parsed.metadata.name} [BDS]"
            val occupied = ThemeManager.getAllThemes().map { it.name }.toSet() +
                listInstalled().map { it.themeName }
            val themeName = if (baseThemeName !in occupied) baseThemeName
            else "$baseThemeName (${id.take(8)})"
            val finalDir = File(rootDir, id)
            if (!staging.renameTo(finalDir)) throw BdsException("无法保存导入的 BDS")
            val record = InstalledSkin(
                id, themeName, parsed.metadata.name, parsed.metadata.author, finalDir
            )
            writeRecord(record)
            val installed = BdsParser.parse(File(finalDir, "extracted"), id)
            parsedCache[id] = installed
            logUnsupported(installed)
            return record to themeFor(record, installed)
        } catch (e: BdsException) {
            throw e
        } catch (e: Exception) {
            throw BdsException("导入 BDS 失败: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun themeFor(record: InstalledSkin, skin: BdsSkin): Theme.Custom {
        // BDS owns the keyboard renderer. Existing theme colors remain useful for the
        // candidate bar, popup windows and unsupported-layout fallback.
        return ThemePreset.PixelLight.deriveCustomNoBackground(record.themeName).copy(
            keyboardColor = skin.styles[127]?.normalColor ?: ThemePreset.PixelLight.keyboardColor,
            keyTextColor = skin.styles[123]?.normalColor ?: ThemePreset.PixelLight.keyTextColor
        )
    }

    private fun readRecord(directory: File): InstalledSkin? = runCatching {
        val properties = Properties().apply {
            File(directory, "metadata.properties").inputStream().use(::load)
        }
        InstalledSkin(
            id = properties.getProperty("id") ?: directory.name,
            themeName = properties.getProperty("themeName") ?: return null,
            name = properties.getProperty("name") ?: return null,
            author = properties.getProperty("author")?.takeIf { it.isNotBlank() },
            directory = directory
        )
    }.getOrNull()

    private fun writeRecord(record: InstalledSkin) {
        Properties().apply {
            setProperty("id", record.id)
            setProperty("themeName", record.themeName)
            setProperty("name", record.name)
            setProperty("author", record.author.orEmpty())
        }.store(File(record.directory, "metadata.properties").outputStream(), "BDS skin index")
    }

    private fun logUnsupported(skin: BdsSkin) {
        skin.unsupportedProperties.forEach { Timber.d("BDS: $it") }
    }
}
