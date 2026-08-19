/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import java.io.File

internal class BdsIni private constructor(
    val sections: LinkedHashMap<String, LinkedHashMap<String, String>>
) {
    fun section(name: String): Map<String, String>? =
        sections.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    companion object {
        fun parse(file: File): BdsIni = parse(file.readBytes())

        fun parse(bytes: ByteArray): BdsIni {
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            val result = linkedMapOf<String, LinkedHashMap<String, String>>()
            var current = result.getOrPut("") { linkedMapOf() }
            text.lineSequence().forEach { original ->
                val line = original.trim()
                if (line.isEmpty() || line.startsWith(';') || line.startsWith('#')) return@forEach
                if (line.startsWith('[') && line.endsWith(']')) {
                    current = result.getOrPut(line.substring(1, line.length - 1).trim()) {
                        linkedMapOf()
                    }
                } else {
                    val separator = line.indexOf('=')
                    if (separator > 0) {
                        current[line.substring(0, separator).trim()] =
                            line.substring(separator + 1).trim()
                    }
                }
            }
            return BdsIni(result)
        }
    }
}

internal fun File.childIgnoreCase(name: String): File? =
    listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
