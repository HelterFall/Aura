package com.helterfall.aura.core.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractionProgressTest {

    @Test
    fun preparingProgressUsesPreparedFileCount() {
        val progress = ExtractionProgress(
            phase = ExtractionPhase.PREPARING,
            totalFiles = 100,
            preparedFiles = 42,
            completedFiles = 0,
            totalBytes = 10_000L,
            processedBytes = 0L,
            activeFiles = emptyList(),
            elapsedMillis = 1_000L,
            bytesPerSecond = 0L,
            estimatedRemainingMillis = null,
            failedFiles = 0,
            workerCount = 4
        )

        assertEquals(0.42f, progress.fraction, 0.001f)
    }

    @Test
    fun calculatesProgressFromBytes() {
        val progress = ExtractionProgress(
            phase = ExtractionPhase.EXTRACTING,
            totalFiles = 4,
            preparedFiles = 0,
            completedFiles = 1,
            totalBytes = 1_000L,
            processedBytes = 250L,
            activeFiles = listOf("image.dat"),
            elapsedMillis = 1_000L,
            bytesPerSecond = 250L,
            estimatedRemainingMillis = 3_000L,
            failedFiles = 0,
            workerCount = 4
        )

        assertEquals(0.25f, progress.fraction, 0.001f)
    }

    @Test
    fun usesFileCountWhenArchiveContainsOnlyEmptyFiles() {
        val progress = ExtractionProgress(
            phase = ExtractionPhase.EXTRACTING,
            totalFiles = 4,
            preparedFiles = 0,
            completedFiles = 2,
            totalBytes = 0L,
            processedBytes = 0L,
            activeFiles = emptyList(),
            elapsedMillis = 100L,
            bytesPerSecond = 0L,
            estimatedRemainingMillis = null,
            failedFiles = 0,
            workerCount = 2
        )

        assertEquals(0.5f, progress.fraction, 0.001f)
    }

    @Test
    fun finishedPhaseAlwaysReportsOneHundredPercent() {
        val progress = ExtractionProgress(
            phase = ExtractionPhase.FINISHED,
            totalFiles = 3,
            preparedFiles = 0,
            completedFiles = 2,
            totalBytes = 10_000L,
            processedBytes = 4_000L,
            activeFiles = emptyList(),
            elapsedMillis = 500L,
            bytesPerSecond = 8_000L,
            estimatedRemainingMillis = 0L,
            failedFiles = 1,
            workerCount = 3
        )

        assertEquals(1f, progress.fraction, 0.001f)
    }
}
