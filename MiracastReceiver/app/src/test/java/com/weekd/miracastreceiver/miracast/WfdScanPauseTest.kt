package com.weekd.miracastreceiver.miracast

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WfdScanPauseTest {
    @get:Rule val files = TemporaryFolder()

    private val testPackage = "com.weekd.miracastreceiver.miracasttest"

    @Test fun debugTestPackageTracksMarkerCreationAndRemoval() {
        val directory = files.root
        val marker = File(directory, "wfd-scan.pause")

        assertFalse(isDiagnosticWfdScanPaused(true, testPackage) { directory })
        assertTrue(marker.createNewFile())
        assertTrue(isDiagnosticWfdScanPaused(true, testPackage) { directory })
        assertTrue(marker.delete())
        assertFalse(isDiagnosticWfdScanPaused(true, testPackage) { directory })
    }

    @Test fun markerDoesNotPauseReleaseOrOtherPackages() {
        val directory = files.root
        assertTrue(File(directory, "wfd-scan.pause").createNewFile())

        assertFalse(isDiagnosticWfdScanPaused(false, testPackage) { directory })
        assertFalse(isDiagnosticWfdScanPaused(true, "com.weekd.miracastreceiver") { directory })
        assertFalse(isDiagnosticWfdScanPaused(true, "$testPackage.other") { directory })
    }

    @Test fun debugTestPackagePausesWhenFilesDirectoryIsUnavailable() {
        assertTrue(isDiagnosticWfdScanPaused(true, testPackage) { throw SecurityException("unavailable") })
    }

    @Test fun nonTestTargetsNeverInspectFilesDirectory() {
        assertFalse(isDiagnosticWfdScanPaused(false, testPackage) { error("unexpected access") })
        assertFalse(isDiagnosticWfdScanPaused(true, "other.package") { error("unexpected access") })
    }
}
