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
        val description: String?,
        val versionCode: Int?,
        val directory: File
    ) {
        val archive: File get() = File(directory, "original.bds")
    }

    private val rootDir: File by lazy {
        File(appContext.filesDir, "skins/bds").also { it.mkdirs() }
    }
    private val parsedCache = mutableMapOf<String, BdsSkin>()
    @Volatile
    private var installedCache: List<InstalledSkin>? = null

    @Synchronized
    fun listInstalled(): List<InstalledSkin> {
        installedCache?.let { return it }
        return rootDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.mapNotNull(::readRecord).orEmpty().also { installedCache = it }
    }

    fun recordForTheme(themeName: String): InstalledSkin? =
        listInstalled().firstOrNull { it.themeName == themeName }

    fun metadataForTheme(themeName: String): BdsMetadata? {
        val record = recordForTheme(themeName) ?: return null
        val bytes = BdsArchiveReader.readRootEntries(record.archive, setOf("Info.txt"))["Info.txt"]
            ?: return BdsMetadata(
                record.name,
                record.author,
                record.description,
                record.versionCode,
                null,
                record.id,
                record.archive.absolutePath,
                emptyMap()
            )
        return BdsSkinMetadataParser.parse(
            bytes,
            record.name.ifBlank { record.archive.nameWithoutExtension },
            "demo.png",
            record.id,
            record.archive.absolutePath
        )
    }

    fun skinForTheme(themeName: String): BdsSkin? {
        val record = recordForTheme(themeName) ?: return null
        return parsedCache.getOrPut(record.id) {
            BdsParser.parse(
                resolveBdsContentRoot(File(record.directory, "extracted")),
                record.id,
                record.name,
                record.archive.absolutePath
            ).also(::logUnsupported)
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
            val fallbackName = sourceName?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
                ?: archive.nameWithoutExtension
            val rootInfo = BdsArchiveReader.readRootEntries(archive, setOf("Info.txt"))["Info.txt"]
            val extracted = File(staging, "extracted")
            BdsArchiveReader.extract(archive, extracted)
            val parsed = BdsParser.parse(
                resolveBdsContentRoot(extracted), id, fallbackName, archive.absolutePath
            )
            // Wrapped archives remain supported; their Info.txt is parsed from the
            // safely extracted content root when it is not at ZIP root.
            val archiveMetadata = rootInfo?.let {
                BdsSkinMetadataParser.parse(
                    it, fallbackName, "demo.png", id, archive.absolutePath
                )
            } ?: parsed.metadata
            val baseThemeName = "${archiveMetadata.name} [BDS]"
            val occupied = ThemeManager.getAllThemes().map { it.name }.toSet() +
                listInstalled().map { it.themeName }
            val themeName = if (baseThemeName !in occupied) baseThemeName
            else "$baseThemeName (${id.take(8)})"
            val finalDir = File(rootDir, id)
            if (!staging.renameTo(finalDir)) throw BdsException("无法保存导入的 BDS")
            val record = InstalledSkin(
                id,
                themeName,
                archiveMetadata.name,
                archiveMetadata.author,
                archiveMetadata.description,
                archiveMetadata.versionCode,
                finalDir
            )
            writeRecord(record)
            installedCache = null
            val installed = BdsParser.parse(
                resolveBdsContentRoot(File(finalDir, "extracted")),
                id,
                record.name,
                record.archive.absolutePath
            )
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

    fun deleteForTheme(themeName: String): Boolean {
        val record = recordForTheme(themeName) ?: return false
        BdsPreviewImageLoader.invalidate(record.archive)
        parsedCache.remove(record.id)
        return record.directory.deleteRecursively().also { installedCache = null }
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
            description = properties.getProperty("description")?.takeIf { it.isNotBlank() },
            versionCode = properties.getProperty("versionCode")?.toIntOrNull(),
            directory = directory
        )
    }.getOrNull()

    private fun writeRecord(record: InstalledSkin) {
        Properties().apply {
            setProperty("id", record.id)
            setProperty("themeName", record.themeName)
            setProperty("name", record.name)
            setProperty("author", record.author.orEmpty())
            setProperty("description", record.description.orEmpty())
            setProperty("versionCode", record.versionCode?.toString().orEmpty())
        }.store(File(record.directory, "metadata.properties").outputStream(), "BDS skin index")
    }

    private fun logUnsupported(skin: BdsSkin) {
        skin.unsupportedProperties.forEach { Timber.d("BDS: $it") }
    }
}

/**
 * Old BDS exporters normally put Info.txt at the archive root, while some desktop
 * archive tools wrap the unchanged skin in one directory and add __MACOSX metadata.
 */
internal fun resolveBdsContentRoot(extractedRoot: File): File {
    if (extractedRoot.childIgnoreCase("Info.txt") != null) return extractedRoot
    val candidates = extractedRoot.listFiles().orEmpty().filter { child ->
        child.isDirectory &&
            !child.name.startsWith('.') &&
            !child.name.equals("__MACOSX", ignoreCase = true) &&
            child.childIgnoreCase("Info.txt") != null
    }
    return candidates.singleOrNull() ?: extractedRoot
}
