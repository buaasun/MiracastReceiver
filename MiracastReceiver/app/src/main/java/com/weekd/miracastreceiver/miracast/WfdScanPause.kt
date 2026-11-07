package com.weekd.miracastreceiver.miracast

import java.io.File

internal fun isDiagnosticWfdScanPaused(debug: Boolean, packageName: String, filesDir: () -> File): Boolean =
    debug && packageName == "com.weekd.miracastreceiver.miracasttest" &&
        File(filesDir(), "wfd-scan.pause").exists()
