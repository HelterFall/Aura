package com.helterfall.aura.core.archive

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class ExtractionReport(
    val extractedFiles: Int,
    val extractedBytes: Long,
    val failures: List<String>
)

class ArchiveExtractor(
    private val context: Context,
    private val reader: NitroPakReader = NitroPakReader()
) {

    fun extractAll(
        archiveUri: Uri,
        outputTreeUri: Uri,
        archiveName: String,
        archive: ArchiveIndex
    ): ExtractionReport {
        val outputTree = DocumentFile.fromTreeUri(context, outputTreeUri)
            ?: throw IOException("Не удалось открыть папку назначения.")

        val baseName = archiveName.substringBeforeLast('.', archiveName)
            .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
            .ifBlank { "archive" }
        val outputRoot = createUniqueDirectory(outputTree, "${baseName}_extracted")

        var extractedFiles = 0
        var extractedBytes = 0L
        val failures = mutableListOf<String>()

        ContentResolverRandomAccessDataSource(context.contentResolver, archiveUri).use { source ->
            for (entry in archive.entries) {
                val segments = safePathSegments(entry.name)
                if (segments == null) {
                    failures += "${entry.name}: небезопасный путь"
                    continue
                }

                var parent = outputRoot
                try {
                    for (directoryName in segments.dropLast(1)) {
                        parent = findOrCreateDirectory(parent, directoryName)
                    }

                    val outputFile = createUniqueFile(parent, segments.last())
                    try {
                        val output = context.contentResolver.openOutputStream(outputFile.uri, "w")
                            ?: throw IOException("Не удалось создать выходной файл.")
                        output.use { outputStream ->
                            reader.openEntry(source, archive, entry).use { input ->
                                extractedBytes += copyExpected(input, outputStream, entry.unpackedSize)
                            }
                        }
                        extractedFiles++
                    } catch (error: Exception) {
                        outputFile.delete()
                        throw error
                    }
                } catch (error: Exception) {
                    failures += "${entry.name}: ${error.message ?: "ошибка извлечения"}"
                }
            }
        }

        return ExtractionReport(
            extractedFiles = extractedFiles,
            extractedBytes = extractedBytes,
            failures = failures
        )
    }

    private fun copyExpected(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > expectedSize - count.toLong()) {
                throw IOException("Распакованный размер превышает размер из индекса.")
            }
            output.write(buffer, 0, count)
            total += count.toLong()
        }

        if (total != expectedSize) {
            throw IOException("Размер после распаковки не совпадает с индексом.")
        }
        return total
    }

    private fun safePathSegments(rawName: String): List<String>? {
        if (rawName.isBlank() || rawName.contains('\u0000')) return null
        val normalized = rawName.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            return null
        }

        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." || it.contains(':') }) {
            return null
        }
        return segments
    }

    private fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            if (!existing.isDirectory) throw IOException("В пути уже существует файл: $name")
            return existing
        }
        return parent.createDirectory(name) ?: throw IOException("Не удалось создать папку: $name")
    }

    private fun createUniqueDirectory(parent: DocumentFile, requestedName: String): DocumentFile {
        var candidate = requestedName
        var suffix = 2
        while (parent.findFile(candidate) != null) {
            candidate = "$requestedName ($suffix)"
            suffix++
        }
        return parent.createDirectory(candidate)
            ?: throw IOException("Не удалось создать папку для извлечённых файлов.")
    }

    private fun createUniqueFile(parent: DocumentFile, requestedName: String): DocumentFile {
        val extensionIndex = requestedName.lastIndexOf('.')
        val base = if (extensionIndex > 0) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (extensionIndex > 0) requestedName.substring(extensionIndex) else ""

        var candidate = requestedName
        var suffix = 2
        while (parent.findFile(candidate) != null) {
            candidate = "$base ($suffix)$extension"
            suffix++
        }

        return parent.createFile("application/octet-stream", candidate)
            ?: throw IOException("Не удалось создать файл: $candidate")
    }
}
