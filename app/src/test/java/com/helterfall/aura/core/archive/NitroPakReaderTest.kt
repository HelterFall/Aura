package com.helterfall.aura.core.archive

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NitroPakReaderTest {

    @Test
    fun readsAndDecompressesNitroPakV2Entry() {
        val payload = "Aura PAK v2 test payload".toByteArray()
        val source = ByteArrayRandomAccessDataSource(createV2Archive("sample.txt", payload))
        val reader = NitroPakReader()
        val index = reader.open(source)

        assertEquals(2, index.version)
        assertEquals("sample.txt", index.entries.single().name)
        assertTrue(index.entries.single().isCompressed)
        assertEquals(
            payload.toList(),
            reader.openEntry(source, index, index.entries.single()).use { it.readBytes().toList() }
        )
    }

    @Test
    fun readsAndDecodesNitroPakV3Entry() {
        val payload = "Aura PAK v3 XOR prefix".toByteArray()
        val source = ByteArrayRandomAccessDataSource(createV3Archive("scene.bin", payload))
        val reader = NitroPakReader()
        val index = reader.open(source)

        assertEquals(3, index.version)
        assertEquals("scene.bin", index.entries.single().name)
        assertEquals(
            payload.toList(),
            reader.openEntry(source, index, index.entries.single()).use { it.readBytes().toList() }
        )
    }

    @Test(expected = UnsupportedArchiveException::class)
    fun rejectsUnknownPakVersion() {
        val source = ByteArrayRandomAccessDataSource(ByteArray(0x114).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 99)
        })
        NitroPakReader().open(source)
    }

    @Test(expected = UnsupportedArchiveException::class)
    fun rejectsEntryOutsideArchiveBounds() {
        val original = createV2Archive("broken.bin", byteArrayOf(1))
        val malformed = ByteArrayOutputStream().apply {
            writeIntLe(10)
            write("broken.bin".toByteArray())
            writeUIntLe(0xfffffff0L)
            writeUIntLe(1)
            writeUIntLe(1)
            writeIntLe(0)
            writeUIntLe(1)
        }.toByteArray()
        val packed = deflate(malformed)
        val rebuilt = ByteArray(0x114 + packed.size + 1)
        System.arraycopy(original, 0, rebuilt, 0, 0x114)
        ByteBuffer.wrap(rebuilt).order(ByteOrder.LITTLE_ENDIAN).putInt(12, packed.size)
        System.arraycopy(packed, 0, rebuilt, 0x114, packed.size)
        rebuilt[rebuilt.lastIndex] = 1
        NitroPakReader().open(ByteArrayRandomAccessDataSource(rebuilt))
    }

    private fun createV2Archive(name: String, payload: ByteArray): ByteArray {
        val packedPayload = deflate(payload)
        val rawIndex = ByteArrayOutputStream().apply {
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            writeIntLe(nameBytes.size)
            write(nameBytes)
            writeUIntLe(0)
            writeUIntLe(payload.size.toLong())
            writeUIntLe(payload.size.toLong())
            writeIntLe(1)
            writeUIntLe(packedPayload.size.toLong())
        }.toByteArray()
        val packedIndex = deflate(rawIndex)
        val header = ByteArray(0x114)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 2)
            putInt(4, 1)
            putInt(8, rawIndex.size)
            putInt(12, packedIndex.size)
        }
        return header + packedIndex + packedPayload
    }

    private fun createV3Archive(name: String, payload: ByteArray): ByteArray {
        val gameName = "AURA".toByteArray(Charsets.US_ASCII)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val fileKey = calculateNameKey(nameBytes)
        val rawIndex = ByteArrayOutputStream().apply {
            writeIntLe(nameBytes.size)
            write(nameBytes)
            writeUIntLe(fileKey)
            writeUIntLe(payload.size.toLong() xor fileKey)
            writeUIntLe(fileKey)
            writeUIntLe(fileKey)
            writeUIntLe(fileKey)
        }.toByteArray()
        val packedIndex = deflate(rawIndex)
        val header = ByteArray(0x114)
        val gameKey = calculateNameKey(gameName)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 3)
            position(4)
            put(gameName)
            putInt(0x104, 0x64)
            putInt(0x108, rawIndex.size xor gameKey.toInt())
            putInt(0x10c, 1 xor gameKey.toInt())
            putInt(0x110, packedIndex.size xor 0x64)
        }

        val encryptedPayload = payload.copyOf()
        var key = fileKey
        for (index in 0 until minOf(16, encryptedPayload.size)) {
            encryptedPayload[index] =
                (encryptedPayload[index].toInt() xor (key and 0xffL).toInt()).toByte()
            key = ((key ushr 8) or (key shl 24)) and 0xffff_ffffL
        }
        return header + packedIndex + encryptedPayload
    }

    private fun calculateNameKey(bytes: ByteArray): Long {
        var key = 0L
        for (byte in bytes) {
            key = (key * 0x89L + byte.toInt().toLong()) and 0xffff_ffffL
        }
        return key
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeUIntLe(value: Long) {
        writeIntLe(value.toInt())
    }
}
