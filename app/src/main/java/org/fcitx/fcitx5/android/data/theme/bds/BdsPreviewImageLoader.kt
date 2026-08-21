/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme.bds

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface BdsPreviewState {
    data object Loading : BdsPreviewState
    data class Ready(val bitmap: Bitmap) : BdsPreviewState
    data object Missing : BdsPreviewState
    data object Error : BdsPreviewState
}

internal data class BdsPreviewCacheKey(val pathHash: String, val fingerprint: String) {
    val fileName get() = "$pathHash-$fingerprint.png"
}

internal fun bdsPreviewCacheKey(file: File): BdsPreviewCacheKey {
    val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    return BdsPreviewCacheKey(
        pathHash = sha256(path).take(24),
        fingerprint = sha256("$path\u0000${file.length()}\u0000${file.lastModified()}").take(32)
    )
}

internal class BdsPreviewMemoryCache<T>(
    private val maxCost: Int,
    private val costOf: (T) -> Int
) {
    private val values = LinkedHashMap<String, T>(16, 0.75f, true)
    private var cost = 0

    @Synchronized
    fun get(key: String): T? = values[key]

    @Synchronized
    fun put(key: String, value: T) {
        values.put(key, value)?.let { cost -= costOf(it) }
        cost += costOf(value)
        while (cost > maxCost && values.isNotEmpty()) {
            val oldest = values.entries.iterator().next()
            cost -= costOf(oldest.value)
            values.remove(oldest.key)
        }
    }

    @Synchronized
    fun removePrefix(prefix: String) {
        values.keys.filter { it.startsWith(prefix) }.forEach { key ->
            values.remove(key)?.let { cost -= costOf(it) }
        }
    }
}

internal object BdsPngInspector {
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
            ) || bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII) != "IHDR"
        ) return null
        fun intAt(offset: Int): Int = ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        val width = intAt(16)
        val height = intAt(20)
        return if (width > 0 && height > 0) width to height else null
    }
}

internal sealed interface BdsPreviewSource {
    data class Valid(val bytes: ByteArray, val dimensions: Pair<Int, Int>) : BdsPreviewSource
    data object Missing : BdsPreviewSource
    data object Error : BdsPreviewSource
}

internal fun readBdsPreviewSource(archive: File): BdsPreviewSource {
    val bytes = runCatching {
        BdsArchiveReader.readRootEntries(archive, setOf("demo.png"))["demo.png"]
    }.onFailure { Timber.w(it, "BDS: failed to read preview from %s", archive) }
        .getOrElse { return BdsPreviewSource.Error }
        ?: return BdsPreviewSource.Missing
    val dimensions = BdsPngInspector.dimensions(bytes) ?: return BdsPreviewSource.Error
    return BdsPreviewSource.Valid(bytes, dimensions)
}

/** Loads only root demo.png and keeps bounded memory plus app-private disk thumbnails. */
object BdsPreviewImageLoader {
    private const val MAX_PREVIEW_PIXELS = 32_000_000L
    private const val TARGET_SIZE = 512
    private const val MEMORY_BYTES = 8 * 1024 * 1024

    private val cacheDir by lazy {
        File(appContext.cacheDir, "bds-preview-thumbnails").also(File::mkdirs)
    }
    private val memory = BdsPreviewMemoryCache<Bitmap>(MEMORY_BYTES) { it.allocationByteCount }
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun load(archive: File): BdsPreviewState = withContext(Dispatchers.IO) {
        if (!archive.isFile) return@withContext BdsPreviewState.Missing
        val key = bdsPreviewCacheKey(archive)
        memory.get(key.fileName)?.let {
            return@withContext BdsPreviewState.Ready(it)
        }
        locks.getOrPut(key.fileName, ::Mutex).withLock {
            memory.get(key.fileName)?.let {
                return@withLock BdsPreviewState.Ready(it)
            }
            purgeStaleDiskEntries(key)
            val disk = File(cacheDir, key.fileName)
            decodeFile(disk)?.let {
                memory.put(key.fileName, it)
                return@withLock BdsPreviewState.Ready(it)
            }
            if (disk.exists()) disk.delete()
            val source = when (val value = readBdsPreviewSource(archive)) {
                is BdsPreviewSource.Valid -> value
                BdsPreviewSource.Missing -> return@withLock BdsPreviewState.Missing
                BdsPreviewSource.Error -> return@withLock BdsPreviewState.Error
            }
            if (source.dimensions.first.toLong() * source.dimensions.second > MAX_PREVIEW_PIXELS) {
                return@withLock BdsPreviewState.Error
            }
            val bitmap = decodeBytes(source.bytes) ?: return@withLock BdsPreviewState.Error
            runCatching {
                disk.outputStream().buffered().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IllegalStateException("Bitmap compression failed")
                    }
                }
            }.onFailure {
                disk.delete()
                Timber.w(it, "BDS: failed to cache preview thumbnail")
            }
            memory.put(key.fileName, bitmap)
            BdsPreviewState.Ready(bitmap)
        }
    }

    fun invalidate(archive: File) {
        val key = bdsPreviewCacheKey(archive)
        memory.removePrefix("${key.pathHash}-")
        cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("${key.pathHash}-") }
            .forEach(File::delete)
    }

    internal fun diskCacheDirectory(): File = cacheDir

    private fun purgeStaleDiskEntries(key: BdsPreviewCacheKey) {
        cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("${key.pathHash}-") && it.name != key.fileName }
            .forEach(File::delete)
    }

    private fun decodeFile(file: File): Bitmap? {
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? = runCatching {
        val pngSize = BdsPngInspector.dimensions(bytes) ?: return null
        if (pngSize.first.toLong() * pngSize.second > MAX_PREVIEW_PIXELS) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_PREVIEW_PIXELS
        ) return null
        var sample = 1
        while (bounds.outWidth / sample > TARGET_SIZE * 2 ||
            bounds.outHeight / sample > TARGET_SIZE * 2
        ) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scale = minOf(1f, TARGET_SIZE.toFloat() / maxOf(decoded.width, decoded.height))
        if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        ).also { if (it !== decoded) decoded.recycle() }
    }.onFailure { Timber.w(it, "BDS: failed to decode preview") }.getOrNull()
}
