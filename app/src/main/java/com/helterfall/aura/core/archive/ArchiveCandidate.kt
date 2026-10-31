package com.helterfall.aura.core.archive

data class ArchiveCandidate(
    val extension: String,
    val label: String
)

data class GameFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val uri: String,
    val archiveCandidate: ArchiveCandidate?
)

/**
 * This is intentionally only a filename-based hint.
 *
 * An extension is not a reliable archive identifier: different engines reuse
 * extensions such as .pak and .dat for unrelated container layouts. A real
 * format reader must validate the file signature and internal structure.
 */
object ArchiveCandidateDetector {
    private val knownExtensions = mapOf(
        "npa" to "NPA",
        "npk" to "NPK",
        "pak" to "Nitro+ PAK v2/v3 (проверка сигнатуры при открытии)",
        "xp3" to "XP3",
        "arc" to "ARC",
        "cpk" to "CRI CPK",
        "pfs" to "PFS",
        "int" to "INT"
    )

    fun detect(fileName: String): ArchiveCandidate? {
        val extension = fileName
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()

        val label = knownExtensions[extension] ?: return null
        return ArchiveCandidate(extension = extension, label = label)
    }
}
