/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

object BdsSkinMetadataParser {
    fun parse(
        bytes: ByteArray,
        fallbackName: String,
        previewPath: String?,
        sourceId: String,
        sourcePath: String
    ): BdsMetadata {
        val properties = linkedMapOf<String, String>()
        bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence().forEach { original ->
            val line = original.trim()
            if (line.isEmpty() || line.startsWith(';') || line.startsWith('#')) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            properties[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }
        fun value(name: String) = properties.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        return BdsMetadata(
            name = value("Name")?.takeIf(String::isNotBlank) ?: fallbackName,
            author = value("Author")?.takeIf(String::isNotBlank),
            description = value("Description")?.takeIf(String::isNotBlank),
            versionCode = value("VersionCode")?.toIntOrNull(),
            previewPath = previewPath,
            sourceId = sourceId,
            sourcePath = sourcePath,
            properties = properties
        )
    }
}
