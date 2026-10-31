package com.helterfall.aura.core.archive

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Random-access, bounded view of an archive file.
 *
 * A new descriptor is opened for each range so independent streams never
 * interfere with each other's file positions.
 */
interface RandomAccessDataSource : Closeable {
    val size: Long

    fun readAt(offset: Long, length: Int): ByteArray

    fun openRange(offset: Long, length: Long): InputStream
}

/**
 * Android Storage Access Framework implementation. The source file itself is
 * never copied into memory; only requested index ranges and entry streams are
 * read.
 */
class ContentResolverRandomAccessDataSource(
    private val resolver: ContentResolver,
    private val uri: Uri
) : RandomAccessDataSource {

    override val size: Long = resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.statSize.takeIf { it >= 0L }
            ?: throw IOException("Хранилище не сообщает размер файла.")
    } ?: throw IOException("Не удалось открыть файл архива.")

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(length >= 0) { "length must not be negative" }
        val result = ByteArray(length)
        openRange(offset, length.toLong()).use { input ->
            var position = 0
            while (position < length) {
                val read = input.read(result, position, length - position)
                if (read < 0) throw EOFException("Неожиданный конец архива.")
                if (read == 0) continue
                position += read
            }
        }
        return result
    }

    override fun openRange(offset: Long, length: Long): InputStream {
        validateRange(offset, length, size)

        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Не удалось открыть файл архива.")
        try {
            val fileInput = FileInputStream(descriptor.fileDescriptor)
            fileInput.channel.position(offset)
            return BoundedDescriptorInputStream(descriptor, fileInput, length)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }

    override fun close() = Unit

    private class BoundedDescriptorInputStream(
        private val descriptor: ParcelFileDescriptor,
        source: FileInputStream,
        private var remaining: Long
    ) : FilterInputStream(source) {

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0L) return -1
            val allowed = minOf(length.toLong(), remaining).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) remaining -= count.toLong()
            return count
        }

        override fun close() {
            try {
                super.close()
            } finally {
                descriptor.close()
            }
        }
    }
}

internal fun validateRange(offset: Long, length: Long, fileSize: Long) {
    if (offset < 0L || length < 0L || offset > fileSize || length > fileSize - offset) {
        throw IOException("Диапазон данных выходит за границы файла.")
    }
}

/** Small in-memory source used by the parser unit tests. */
internal class ByteArrayRandomAccessDataSource(
    private val data: ByteArray
) : RandomAccessDataSource {
    override val size: Long get() = data.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        validateRange(offset, length.toLong(), size)
        return data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun openRange(offset: Long, length: Long): InputStream {
        validateRange(offset, length, size)
        return ByteArrayInputStream(data, offset.toInt(), length.toInt())
    }

    override fun close() = Unit
}
