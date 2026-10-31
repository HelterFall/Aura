package com.helterfall.aura.core.archive

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

data class ArchiveEntryInfo(
    val name: String,
    val offset: Long,
    val storedSize: Long,
    val unpackedSize: Long,
    val isCompressed: Boolean,
    val xorKey: Long = 0L
)

data class ArchiveIndex(
    val format: String,
    val version: Int,
    val entries: List<ArchiveEntryInfo>
)

class UnsupportedArchiveException(message: String) : IOException(message)

/**
 * Reader for Nitro+ legacy PAK v2 and v3 containers.
 *
 * Both variants use a zlib-compressed index. V2 optionally stores each payload
 * in zlib form. V3 additionally XOR-transforms the first 16 bytes of
 * uncompressed payloads. All offsets are validated before an entry is exposed.
 */
class NitroPakReader {

    fun open(source: RandomAccessDataSource): ArchiveIndex {
        if (source.size < 4L) {
            throw UnsupportedArchiveException("Файл слишком короткий для PAK.")
        }

        val version = littleEndianInt(source.readAt(0L, 4))
        return when (version) {
            2 -> openV2(source)
            3 -> openV3(source)
            else -> throw UnsupportedArchiveException(
                "Неизвестная версия PAK: $version. Поддерживаются Nitro+ PAK v2 и v3."
            )
        }
    }

    fun openEntry(
        source: RandomAccessDataSource,
        archive: ArchiveIndex,
        entry: ArchiveEntryInfo
    ): java.io.InputStream {
        val raw = source.openRange(entry.offset, entry.storedSize)
        return if (entry.isCompressed) {
            InflaterInputStream(raw, Inflater(false), BUFFER_SIZE)
        } else if (archive.version == 3 && entry.xorKey != 0L && entry.storedSize > 0L) {
            PrefixXorInputStream(raw, entry.xorKey)
        } else {
            raw
        }
    }

    private fun openV2(source: RandomAccessDataSource): ArchiveIndex {
        if (source.size < HEADER_SIZE.toLong()) {
            throw UnsupportedArchiveException("Заголовок PAK v2 повреждён.")
        }

        val header = ByteBuffer.wrap(source.readAt(0L, HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
        val count = header.getInt(4)
        val indexUnpackedSize = header.getInt(8).toLong()
        val indexPackedSize = header.getInt(12).toLong() and UINT_MASK

        validateCount(count)
        validateIndexSizes(indexPackedSize, indexUnpackedSize, source.size)

        val dataBase = HEADER_SIZE.toLong() + indexPackedSize
        val entries = readV2Index(source, indexPackedSize, count, dataBase)

        return ArchiveIndex(
            format = "Nitro+ PAK",
            version = 2,
            entries = entries
        )
    }

    private fun readV2Index(
        source: RandomAccessDataSource,
        packedIndexSize: Long,
        count: Int,
        dataBase: Long
    ): List<ArchiveEntryInfo> {
        val result = ArrayList<ArchiveEntryInfo>(count)
        val stream = source.openRange(HEADER_SIZE.toLong(), packedIndexSize)
        DataInputStream(
            BufferedInputStream(
                ExpansionLimitInputStream(
                    InflaterInputStream(stream, Inflater(false), BUFFER_SIZE),
                    MAX_INDEX_UNPACKED
                )
            )
        ).use { index ->
            repeat(count) {
                val nameLength = index.readIntLe()
                if (nameLength !in 1..MAX_NAME_BYTES) {
                    throw UnsupportedArchiveException("Некорректная длина имени в PAK v2.")
                }

                val nameBytes = ByteArray(nameLength)
                index.readFully(nameBytes)
                val name = decodeName(nameBytes)

                val relativeOffset = index.readUIntLe()
                val unpackedSize = index.readUIntLe()
                val storedSizeField = index.readUIntLe()
                val isCompressed = index.readIntLe() != 0
                val compressedSize = index.readUIntLe()
                val storedSize = if (isCompressed) compressedSize else storedSizeField
                val absoluteOffset = checkedAdd(dataBase, relativeOffset)

                validateEntryRange(source.size, absoluteOffset, storedSize)
                if (unpackedSize > MAX_ENTRY_UNPACKED) {
                    throw UnsupportedArchiveException("Слишком большой файл в архиве: $name")
                }

                result += ArchiveEntryInfo(
                    name = name,
                    offset = absoluteOffset,
                    storedSize = storedSize,
                    unpackedSize = unpackedSize,
                    isCompressed = isCompressed
                )
            }
        }
        return result
    }

    private fun openV3(source: RandomAccessDataSource): ArchiveIndex {
        if (source.size < HEADER_SIZE.toLong()) {
            throw UnsupportedArchiveException("Заголовок PAK v3 повреждён.")
        }

        val header = ByteBuffer.wrap(source.readAt(0L, HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
        val sizeXor = header.getInt(0x104).toLong() and UINT_MASK
        if (sizeXor != 0x64L) {
            throw UnsupportedArchiveException("Сигнатура Nitro+ PAK v3 не совпадает.")
        }

        val gameNameBuffer = ByteArray(0x100)
        header.position(4)
        header.get(gameNameBuffer)
        val gameNameLength = gameNameBuffer.indexOf(0).let { if (it < 0) gameNameBuffer.size else it }
        if (gameNameLength !in 1..0x10 ||
            gameNameBuffer.take(gameNameLength).any { (it.toInt() and 0xff) !in 0x20..0x7e }
        ) {
            throw UnsupportedArchiveException("Некорректное имя игры в PAK v3.")
        }

        val headerKey = calculateNameKey(gameNameBuffer, gameNameLength)
        val entryCountValue = (header.getInt(0x10c).toLong() and UINT_MASK) xor headerKey
        if (entryCountValue > MAX_ENTRIES.toLong()) {
            throw UnsupportedArchiveException("Некорректное количество файлов в PAK v3.")
        }
        val count = entryCountValue.toInt()
        validateCount(count)

        val packedIndexSize = (header.getInt(0x110).toLong() and UINT_MASK) xor sizeXor
        validateIndexSizes(packedIndexSize, MAX_INDEX_UNPACKED.toLong(), source.size)
        val dataBase = checkedAdd(HEADER_SIZE.toLong(), packedIndexSize)

        val entries = readV3Index(source, packedIndexSize, count, dataBase)
        return ArchiveIndex(
            format = "Nitro+ PAK",
            version = 3,
            entries = entries
        )
    }

    private fun readV3Index(
        source: RandomAccessDataSource,
        packedIndexSize: Long,
        count: Int,
        dataBase: Long
    ): List<ArchiveEntryInfo> {
        val result = ArrayList<ArchiveEntryInfo>(count)
        val stream = source.openRange(HEADER_SIZE.toLong(), packedIndexSize)
        DataInputStream(
            BufferedInputStream(
                ExpansionLimitInputStream(
                    InflaterInputStream(stream, Inflater(false), BUFFER_SIZE),
                    MAX_INDEX_UNPACKED
                )
            )
        ).use { index ->
            repeat(count) {
                val nameLength = index.readIntLe()
                if (nameLength !in 1..MAX_NAME_BYTES) {
                    throw UnsupportedArchiveException("Некорректная длина имени в PAK v3.")
                }

                val nameBytes = ByteArray(nameLength)
                index.readFully(nameBytes)
                val name = decodeName(nameBytes)
                val key = calculateNameKey(nameBytes, nameBytes.size)

                val relativeOffset = index.readUIntLe() xor key
                val unpackedSize = index.readUIntLe() xor key
                index.readUIntLe() // reserved field
                val isCompressed = (index.readUIntLe() xor key) != 0L
                val packedSize = index.readUIntLe() xor key
                val storedSize = if (isCompressed) packedSize else unpackedSize
                val absoluteOffset = checkedAdd(dataBase, relativeOffset)

                validateEntryRange(source.size, absoluteOffset, storedSize)
                if (unpackedSize > MAX_ENTRY_UNPACKED) {
                    throw UnsupportedArchiveException("Слишком большой файл в архиве: $name")
                }

                result += ArchiveEntryInfo(
                    name = name,
                    offset = absoluteOffset,
                    storedSize = storedSize,
                    unpackedSize = unpackedSize,
                    isCompressed = isCompressed,
                    xorKey = key
                )
            }
        }
        return result
    }

    private fun validateCount(count: Int) {
        if (count !in 0..MAX_ENTRIES) {
            throw UnsupportedArchiveException("Некорректное количество файлов: $count")
        }
    }

    private fun validateIndexSizes(
        packedSize: Long,
        unpackedSize: Long,
        fileSize: Long
    ) {
        if (packedSize <= 0L || packedSize > MAX_INDEX_PACKED ||
            unpackedSize < 0L || unpackedSize > MAX_INDEX_UNPACKED ||
            HEADER_SIZE.toLong() + packedSize > fileSize
        ) {
            throw UnsupportedArchiveException("Некорректный размер индекса PAK.")
        }
    }

    private fun validateEntryRange(fileSize: Long, offset: Long, size: Long) {
        try {
            validateRange(offset, size, fileSize)
        } catch (error: IOException) {
            throw UnsupportedArchiveException("Файл в архиве указывает за пределы контейнера.")
        }
    }

    private fun decodeName(bytes: ByteArray): String {
        return try {
            SHIFT_JIS.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun calculateNameKey(name: ByteArray, length: Int): Long {
        var key = 0L
        for (index in 0 until length) {
            key = (key * 0x89L + name[index].toInt().toLong()) and UINT_MASK
        }
        return key
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            throw UnsupportedArchiveException("Переполнение смещения в PAK.")
        }
        return left + right
    }

    private class PrefixXorInputStream(
        input: java.io.InputStream,
        keyValue: Long
    ) : FilterInputStream(input) {
        private var key = keyValue
        private var transformed = 0

        override fun read(): Int {
            val value = super.read()
            if (value < 0 || transformed >= 16) return value
            val decoded = value xor (key and 0xffL).toInt()
            rotateKey()
            return decoded and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count <= 0) return count
            val toTransform = minOf(count, 16 - transformed)
            for (index in 0 until toTransform) {
                buffer[offset + index] =
                    (buffer[offset + index].toInt() xor (key and 0xffL).toInt()).toByte()
                rotateKey()
            }
            return count
        }

        private fun rotateKey() {
            key = ((key ushr 8) or (key shl 24)) and UINT_MASK
            transformed++
        }
    }

    private class ExpansionLimitInputStream(
        input: java.io.InputStream,
        private val maximum: Long
    ) : FilterInputStream(input) {
        private var consumed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) account(count.toLong())
            return count
        }

        private fun account(count: Long) {
            consumed += count
            if (consumed > maximum) {
                throw UnsupportedArchiveException("Индекс PAK превышает допустимый размер.")
            }
        }
    }

    private fun DataInputStream.readIntLe(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun DataInputStream.readUIntLe(): Long =
        readIntLe().toLong() and UINT_MASK

    private companion object {
        const val HEADER_SIZE = 0x114
        const val BUFFER_SIZE = 32 * 1024
        const val MAX_ENTRIES = 250_000
        const val MAX_NAME_BYTES = 64 * 1024
        const val MAX_INDEX_PACKED = 64L * 1024L * 1024L
        const val MAX_INDEX_UNPACKED = 128L * 1024L * 1024L
        const val MAX_ENTRY_UNPACKED = 2L * 1024L * 1024L * 1024L
        const val UINT_MASK = 0xffff_ffffL
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        fun littleEndianInt(bytes: ByteArray): Int =
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
