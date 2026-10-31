package com.helterfall.aura.core.archive

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class ExtractionPhase {
    PREPARING,
    EXTRACTING,
    FINISHED
}

data class ExtractionProgress(
    val phase: ExtractionPhase,
    val totalFiles: Int,
    val preparedFiles: Int,
    val completedFiles: Int,
    val totalBytes: Long,
    val processedBytes: Long,
    val activeFiles: List<String>,
    val elapsedMillis: Long,
    val bytesPerSecond: Long,
    val estimatedRemainingMillis: Long?,
    val failedFiles: Int,
    val workerCount: Int
) {
    val fraction: Float
        get() {
            if (phase == ExtractionPhase.FINISHED) return 1f
            if (phase == ExtractionPhase.PREPARING) {
                if (totalFiles <= 0) return 0f
                return (preparedFiles.toFloat() / totalFiles).coerceIn(0f, 1f)
            }
            if (totalBytes > 0L) {
                return (processedBytes.toDouble() / totalBytes.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
            }
            if (totalFiles <= 0) return 0f
            return (completedFiles.toFloat() / totalFiles).coerceIn(0f, 1f)
        }
}

data class ExtractionReport(
    val extractedFiles: Int,
    val extractedBytes: Long,
    val failures: List<String>
)

class ArchiveExtractor(
    private val context: Context,
    private val reader: NitroPakReader = NitroPakReader(),
    maxParallelFiles: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_PARALLEL_FILES)
) {
    private val workerCount = maxParallelFiles.coerceIn(1, MAX_PARALLEL_FILES)

    fun extractAll(
        archiveUri: Uri,
        outputTreeUri: Uri,
        archiveName: String,
        archive: ArchiveIndex,
        onProgress: (ExtractionProgress) -> Unit = {}
    ): ExtractionReport {
        val outputTree = DocumentFile.fromTreeUri(context, outputTreeUri)
            ?: throw IOException("Не удалось открыть папку назначения.")

        val baseName = archiveName.substringBeforeLast('.', archiveName)
            .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
            .ifBlank { "archive" }
        val outputRoot = createUniqueDirectory(outputTree, "${baseName}_extracted")

        val failures = Collections.synchronizedList(mutableListOf<String>())
        val plannedFiles = ArrayList<PlannedFile>(archive.entries.size)
        // The output root is newly created, so its contents are known to be empty.
        // Keep an in-memory directory/name index instead of repeatedly calling
        // DocumentFile.findFile(), which lists the directory on every lookup.
        val outputRootState = DirectoryState(outputRoot)
        val totalBytes = AtomicLong(0L)

        val startedAt = System.nanoTime()
        val processedBytes = AtomicLong(0L)
        val preparedFiles = AtomicInteger(0)
        val completedFiles = AtomicInteger(0)
        val extractedFiles = AtomicInteger(0)
        val extractedBytes = AtomicLong(0L)
        val activeFiles = ConcurrentHashMap.newKeySet<String>()
        val lastProgressAt = AtomicLong(0L)
        val transferStartedAt = AtomicLong(0L)

        fun publishProgress(phase: ExtractionPhase, force: Boolean = false) {
            val now = System.nanoTime()
            if (!force) {
                val previous = lastProgressAt.get()
                if (now - previous < PROGRESS_INTERVAL_NANOS ||
                    !lastProgressAt.compareAndSet(previous, now)
                ) {
                    return
                }
            } else {
                lastProgressAt.set(now)
            }

            val elapsedMillis = ((now - startedAt) / 1_000_000L).coerceAtLeast(0L)
            val currentBytes = processedBytes.get()
            val currentTotalBytes = totalBytes.get()
            val rateStart = transferStartedAt.get().takeIf { it > 0L } ?: now
            val rateElapsedMillis = ((now - rateStart) / 1_000_000L).coerceAtLeast(0L)
            val bytesPerSecond = if (rateElapsedMillis > 0L) {
                (currentBytes * 1_000L / rateElapsedMillis).coerceAtLeast(0L)
            } else {
                0L
            }
            val remainingBytes = (currentTotalBytes - currentBytes).coerceAtLeast(0L)
            val etaMillis = when {
                phase == ExtractionPhase.FINISHED -> 0L
                bytesPerSecond > 0L -> remainingBytes * 1_000L / bytesPerSecond
                else -> null
            }

            val snapshot = ExtractionProgress(
                phase = phase,
                totalFiles = archive.entries.size,
                preparedFiles = preparedFiles.get(),
                completedFiles = completedFiles.get(),
                totalBytes = currentTotalBytes,
                processedBytes = currentBytes,
                activeFiles = activeFiles.toList().sorted().take(MAX_VISIBLE_ACTIVE_FILES),
                elapsedMillis = elapsedMillis,
                bytesPerSecond = bytesPerSecond,
                estimatedRemainingMillis = etaMillis,
                failedFiles = failures.size,
                workerCount = workerCount
            )
            runCatching { onProgress(snapshot) }
        }

        publishProgress(ExtractionPhase.PREPARING, force = true)

        ContentResolverRandomAccessDataSource(context.contentResolver, archiveUri).use { source ->
            // SAF providers may be slow. Directory lookups are resolved from the
            // in-memory tree because this output root was just created by Aura.
            // This avoids listing the same growing directory for every file.
            for (entry in archive.entries) {
                try {
                    val segments = safePathSegments(entry.name)
                    if (segments == null) {
                        failures += "${entry.name}: небезопасный путь"
                        completedFiles.incrementAndGet()
                    } else {
                        var parent = outputRootState
                        for (directoryName in segments.dropLast(1)) {
                            parent = findOrCreateDirectory(parent, directoryName)
                        }
                        val outputFile = createUniqueFile(parent, segments.last())
                        plannedFiles += PlannedFile(entry, outputFile)
                        totalBytes.updateAndGet { current ->
                            if (Long.MAX_VALUE - current < entry.unpackedSize) Long.MAX_VALUE
                            else current + entry.unpackedSize
                        }
                    }
                } catch (error: Exception) {
                    failures += "${entry.name}: ${error.message ?: "ошибка подготовки файла"}"
                    completedFiles.incrementAndGet()
                } finally {
                    preparedFiles.incrementAndGet()
                    // Rate-limit UI callbacks; progress still advances for every entry.
                    publishProgress(ExtractionPhase.PREPARING)
                }
            }

            transferStartedAt.compareAndSet(0L, System.nanoTime())
            publishProgress(ExtractionPhase.EXTRACTING, force = true)

            if (plannedFiles.isNotEmpty()) {
                val executor = Executors.newFixedThreadPool(workerCount)
                try {
                    val futures = plannedFiles.map { task ->
                        executor.submit {
                            activeFiles.add(task.entry.name)
                            publishProgress(ExtractionPhase.EXTRACTING, force = true)

                            try {
                                val rawOutput = context.contentResolver.openOutputStream(
                                    task.outputFile.uri,
                                    "w"
                                ) ?: throw IOException("Не удалось создать выходной файл.")

                                var fileBytes = 0L
                                BufferedOutputStream(rawOutput, IO_BUFFER_SIZE).use { output ->
                                    reader.openEntry(source, archive, task.entry).use { input ->
                                        fileBytes = copyExpected(
                                            input = input,
                                            output = output,
                                            expectedSize = task.entry.unpackedSize
                                        ) { count ->
                                            processedBytes.addAndGet(count)
                                            publishProgress(ExtractionPhase.EXTRACTING)
                                        }
                                    }
                                }
                                extractedBytes.addAndGet(fileBytes)
                                extractedFiles.incrementAndGet()
                            } catch (error: Exception) {
                                task.outputFile.delete()
                                failures += "${task.entry.name}: ${error.message ?: "ошибка извлечения"}"
                            } finally {
                                activeFiles.remove(task.entry.name)
                                completedFiles.incrementAndGet()
                                publishProgress(ExtractionPhase.EXTRACTING, force = true)
                            }
                        }
                    }

                    futures.forEach { it.get() }
                } finally {
                    executor.shutdown()
                    if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                    }
                }
            }
        }

        publishProgress(ExtractionPhase.FINISHED, force = true)

        return ExtractionReport(
            extractedFiles = extractedFiles.get(),
            extractedBytes = extractedBytes.get(),
            failures = synchronized(failures) { failures.toList() }
        )
    }

    private fun copyExpected(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        onBytesCopied: (Long) -> Unit
    ): Long {
        val buffer = ByteArray(IO_BUFFER_SIZE)
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
            onBytesCopied(count.toLong())
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

    private fun findOrCreateDirectory(parent: DirectoryState, name: String): DirectoryState {
        parent.directories[name]?.let { return it }
        if (name in parent.occupiedNames) {
            throw IOException("В пути уже существует файл: $name")
        }

        val created = parent.document.createDirectory(name)
            ?: throw IOException("Не удалось создать папку: $name")
        parent.occupiedNames += name
        return DirectoryState(created).also { parent.directories[name] = it }
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

    private fun createUniqueFile(parent: DirectoryState, requestedName: String): DocumentFile {
        val extensionIndex = requestedName.lastIndexOf('.')
        val base = if (extensionIndex > 0) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (extensionIndex > 0) requestedName.substring(extensionIndex) else ""

        var candidate = requestedName
        var suffix = 2
        while (candidate in parent.occupiedNames) {
            candidate = "$base ($suffix)$extension"
            suffix++
        }

        val created = parent.document.createFile("application/octet-stream", candidate)
            ?: throw IOException("Не удалось создать файл: $candidate")
        parent.occupiedNames += candidate
        return created
    }

    private class DirectoryState(val document: DocumentFile) {
        val occupiedNames = HashSet<String>()
        val directories = HashMap<String, DirectoryState>()
    }

    private data class PlannedFile(
        val entry: ArchiveEntryInfo,
        val outputFile: DocumentFile
    )

    private companion object {
        const val DEFAULT_PARALLEL_FILES = 4
        const val MAX_PARALLEL_FILES = 4
        const val MAX_VISIBLE_ACTIVE_FILES = 4
        const val IO_BUFFER_SIZE = 256 * 1024
        const val PROGRESS_INTERVAL_NANOS = 120_000_000L
        const val EXECUTOR_SHUTDOWN_SECONDS = 10L
    }
}
