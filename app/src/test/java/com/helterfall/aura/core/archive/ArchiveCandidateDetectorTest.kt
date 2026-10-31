package com.helterfall.aura.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveCandidateDetectorTest {
    @Test
    fun detectsNitroplusNpaCandidateCaseInsensitively() {
        val candidate = ArchiveCandidateDetector.detect("DATA.NPA")

        assertEquals("npa", candidate?.extension)
        assertEquals("NPA", candidate?.label)
    }

    @Test
    fun doesNotTreatUnknownExtensionAsArchive() {
        assertNull(ArchiveCandidateDetector.detect("readme.txt"))
    }

    @Test
    fun handlesFileWithoutExtension() {
        assertNull(ArchiveCandidateDetector.detect("datafile"))
    }
}
