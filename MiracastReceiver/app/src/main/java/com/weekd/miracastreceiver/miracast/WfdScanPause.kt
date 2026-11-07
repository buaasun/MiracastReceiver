package com.weekd.miracastreceiver.miracast

import java.io.File

internal fun isDiagnosticWfdScanPaused(debug: Boolean, packageName: String, filesDir: () -> File): Boolean {
    if (!debug || packageName != "com.weekd.miracastreceiver.miracasttest") return false
    return try {
        File(filesDir(), "wfd-scan.pause").exists()
    } catch (_: Exception) {
        true
    }
}
