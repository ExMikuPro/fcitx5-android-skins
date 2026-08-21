/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object BdsArchiveReader {
    const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
    const val MAX_EXTRACTED_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    const val MAX_ENTRIES = 4096

    /**
     * Random-access read for small root resources such as Info.txt and demo.png.
     * The central directory is validated first and only requested entries are inflated.
     */
    fun readRootEntries(archive: File, requestedNames: Set<String>): Map<String, ByteArray> {
        if (archive.length() > MAX_ARCHIVE_BYTES) throw BdsException("BDS 文件过大")
        if (!hasZipMagic(archive)) throw BdsException("所选文件不是有效的 ZIP/BDS 文件")
        val wanted = requestedNames.associateBy { it.lowercase() }
        return try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size > MAX_ENTRIES) throw BdsException("BDS 包含过多文件")
                var declaredTotal = 0L
                entries.forEach { entry ->
                    validateEntryName(entry.name)
                    if (entry.size > MAX_ENTRY_BYTES) {
                        throw BdsException("BDS 单个资源解压后体积超过限制")
                    }
                    if (entry.size >= 0) {
                        declaredTotal += entry.size
                        if (declaredTotal > MAX_EXTRACTED_BYTES) {
                            throw BdsException("BDS 解压后体积超过限制")
                        }
                    }
                }
                val result = linkedMapOf<String, ByteArray>()
                entries.forEach { entry ->
                    if (entry.isDirectory || '/' in entry.name || '\\' in entry.name) return@forEach
                    val requested = wanted[entry.name.lowercase()] ?: return@forEach
                    zip.getInputStream(entry).buffered().use { input ->
                        result[requested] = input.readBytesLimited(MAX_ENTRY_BYTES)
                    }
                }
                result
            }
        } catch (e: BdsException) {
            throw e
        } catch (e: ZipException) {
            throw BdsException("BDS 压缩包已损坏", e)
        } catch (e: Exception) {
            throw BdsException("无法读取 BDS: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    fun hasZipMagic(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return FileInputStream(file).use {
            val header = ByteArray(4)
            it.read(header) == 4 && header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte() &&
                ((header[2] == 3.toByte() && header[3] == 4.toByte()) ||
                    (header[2] == 5.toByte() && header[3] == 6.toByte()) ||
                    (header[2] == 7.toByte() && header[3] == 8.toByte()))
        }
    }

    fun extract(archive: File, destination: File): List<File> {
        if (archive.length() > MAX_ARCHIVE_BYTES) throw BdsException("BDS 文件过大")
        if (!hasZipMagic(archive)) throw BdsException("所选文件不是有效的 ZIP/BDS 文件")
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        val extracted = mutableListOf<File>()
        var entries = 0
        var totalBytes = 0L
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) throw BdsException("BDS 包含过多文件")
                    val normalized = validateEntryName(entry.name)
                    val output = File(canonicalRoot, normalized).canonicalFile
                    if (output != canonicalRoot && !output.path.startsWith(canonicalRoot.path + File.separator)) {
                        throw BdsException("BDS 路径越界: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        var entryBytes = 0L
                        output.outputStream().buffered().use { stream ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                if (entryBytes > MAX_ENTRY_BYTES) {
                                    throw BdsException("BDS 单个资源解压后体积超过限制")
                                }
                                if (totalBytes > MAX_EXTRACTED_BYTES) {
                                    throw BdsException("BDS 解压后体积超过限制")
                                }
                                stream.write(buffer, 0, read)
                            }
                        }
                        extracted += output
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: BdsException) {
            throw e
        } catch (e: ZipException) {
            throw BdsException("BDS 压缩包已损坏", e)
        } catch (e: Exception) {
            throw BdsException("无法读取 BDS: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return extracted
    }

    private fun validateEntryName(name: String): String {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') ||
            normalized.matches(Regex("^[A-Za-z]:/.*")) ||
            normalized.split('/').any { it == ".." }
        ) throw BdsException("BDS 包含不安全路径: $name")
        return normalized
    }

    private fun InputStream.readBytesLimited(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw BdsException("BDS 单个资源解压后体积超过限制")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
