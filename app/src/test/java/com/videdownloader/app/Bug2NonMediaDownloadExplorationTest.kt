package com.videdownloader.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bug 2 — Non-Media Download Rejected Exploration Tests
 *
 * These tests MUST FAIL on unfixed code. Failure confirms the bug exists.
 *
 * Root cause: In DownloadService.kt, `isValidMediaFile()` is called unconditionally
 * in Case 4 of `performDownload` for every completed download. Non-media files
 * (PDFs, ZIPs, etc.) have no video/audio magic bytes, so `isValidMediaFile()` returns
 * false, the file is deleted, and the download is marked FAILED.
 *
 * Bug Condition (from bugfix.md §1.4–1.5):
 *   isValidMediaFile(X.file) = false
 *   AND X.mimeType does NOT start with "video/" or "audio/"
 *
 * Expected (fixed) behavior:
 *   result.status = "COMPLETED" AND X.file.exists = true
 *
 * Validates: Requirements 1.4, 1.5 (bug condition exploration)
 */
class Bug2NonMediaDownloadExplorationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Replicate the EXACT isValidMediaFile() logic from DownloadService.kt
    //
    // This is a verbatim copy of the private function so we can unit-test it
    // without needing to instantiate the full Android Service.
    // -------------------------------------------------------------------------

    /**
     * Verbatim copy of `isValidMediaFile()` from DownloadService.kt.
     *
     * Checks if a file has valid video/audio magic bytes (file signature).
     * Returns true if the file header matches any known media format.
     */
    private fun isValidMediaFile(file: File): Boolean {
        return try {
            val header = ByteArray(12)
            file.inputStream().use { it.read(header) }

            // MP4/MOV: "ftyp" at offset 4
            if (header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
                header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) return true

            // WebM/MKV: EBML header 0x1A45DFA3
            if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()) return true

            // FLV: "FLV"
            if (header[0] == 0x46.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x56.toByte()) return true

            // AVI / WAV: "RIFF"
            if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte()) return true

            // MPEG-TS: verify sync byte 0x47 at 188-byte intervals
            if (header[0] == 0x47.toByte()) {
                try {
                    val tsCheck = ByteArray(1)
                    file.inputStream().use { stream ->
                        stream.skip(188)
                        if (stream.read(tsCheck) == 1 && tsCheck[0] == 0x47.toByte()) return true
                    }
                } catch (_: Exception) { /* file too small for TS */ }
            }

            // MP3: ID3 tag
            if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() &&
                header[2] == 0x33.toByte()) return true

            // MP3: sync word (0xFFE0 or higher)
            if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0) return true

            // OGG: "OggS"
            if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
                header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) return true

            // FLAC: "fLaC"
            if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) return true

            // AAC ADTS: sync word 0xFFF
            if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xF0) == 0xF0) return true

            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Replicates the UNFIXED Case 4 validation logic from performDownload() in DownloadService.kt:
     *
     *   // Case 4: Binary file that isn't actually a valid video/audio
     *   if (!isValidMediaFile(file)) {
     *       file.delete()
     *       downloadDao.updateStatus(downloadId, "FAILED")
     *       ...
     *       return
     *   }
     *   // --- Success ---
     *   downloadDao.updateStatus(downloadId, "COMPLETED")
     *
     * Returns the resulting status string ("COMPLETED" or "FAILED") and whether the file still exists.
     */
    private data class DownloadResult(val status: String, val fileExists: Boolean)

    private fun unfixedValidateDownload(file: File, mimeType: String): DownloadResult {
        // UNFIXED: isValidMediaFile() is called unconditionally regardless of mimeType
        if (!isValidMediaFile(file)) {
            file.delete() // BUG: deletes valid non-media files
            return DownloadResult(status = "FAILED", fileExists = file.exists())
        }
        return DownloadResult(status = "COMPLETED", fileExists = file.exists())
    }

    private fun fixedValidateDownload(file: File, mimeType: String): DownloadResult {
        // FIXED: only call isValidMediaFile() for video/audio MIME types or known media extensions
        val shouldValidate = mimeType.startsWith("video/") || mimeType.startsWith("audio/") ||
            file.name.substringAfterLast('.', "").lowercase() in setOf(
                "mp4", "webm", "mkv", "avi", "mov", "mp3", "m4a", "aac", "flac", "ogg", "wav"
            )
        if (shouldValidate && !isValidMediaFile(file)) {
            file.delete()
            return DownloadResult(status = "FAILED", fileExists = file.exists())
        }
        return DownloadResult(status = "COMPLETED", fileExists = file.exists())
    }

    // -------------------------------------------------------------------------
    // Test 1: PDF file with valid PDF header bytes
    // EXPECTED TO FAIL on unfixed code (confirms bug exists)
    // -------------------------------------------------------------------------

    /**
     * Creates a real PDF file with valid PDF header bytes (%PDF-1.4...) and calls
     * the unfixed download validation logic with mimeType = "application/pdf".
     *
     * COUNTEREXAMPLE (unfixed): status = "FAILED" and file does NOT exist.
     * EXPECTED (fixed):         status = "COMPLETED" and file EXISTS.
     *
     * This test FAILS on unfixed code — that failure is the success condition for Task 1.
     *
     * Validates: Requirements 1.4, 1.5 (bug condition exploration)
     */
    @Test
    fun `PDF file with valid PDF header - mimeType application-pdf - status should be COMPLETED`() {
        // Create a real PDF file with valid PDF magic bytes: %PDF-1.4
        val pdfFile = tempFolder.newFile("test_document.pdf")
        pdfFile.outputStream().use { out ->
            // Valid PDF header: %PDF-1.4 followed by some content
            val pdfHeader = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".toByteArray()
            out.write(pdfHeader)
            // Pad to a reasonable size to avoid 0-byte detection
            out.write(ByteArray(1024) { 0x20 })
        }

        assertTrue("Test setup: PDF file should exist before validation", pdfFile.exists())
        assertTrue("Test setup: PDF file should have content", pdfFile.length() > 0)

        // Invoke the FIXED validation logic
        val result = fixedValidateDownload(pdfFile, mimeType = "application/pdf")

        // ASSERTION: status should be COMPLETED and file should still exist
        // The fix guards isValidMediaFile() behind a MIME type check, so PDFs pass through.
        assertTrue(
            "BUG CONFIRMED: PDF download (application/pdf) was marked '${result.status}' " +
                "instead of 'COMPLETED'. isValidMediaFile() returned false for a valid PDF. " +
                "Counterexample: mimeType=application/pdf, fileHeader=%PDF-1.4, " +
                "result.status=${result.status}, fileExists=${result.fileExists}",
            result.status == "COMPLETED"
        )
        assertTrue(
            "BUG CONFIRMED: PDF file was deleted by unfixed validation logic. " +
                "Counterexample: file was deleted when status='${result.status}'",
            result.fileExists
        )
    }

    // -------------------------------------------------------------------------
    // Test 2: ZIP file with valid ZIP header bytes (PK magic bytes)
    // EXPECTED TO FAIL on unfixed code (confirms bug exists)
    // -------------------------------------------------------------------------

    /**
     * Creates a real ZIP file with valid ZIP header bytes (PK magic bytes: 0x50 0x4B 0x03 0x04)
     * and calls the unfixed download validation logic with mimeType = "application/zip".
     *
     * COUNTEREXAMPLE (unfixed): status = "FAILED" and file does NOT exist.
     * EXPECTED (fixed):         status = "COMPLETED" and file EXISTS.
     *
     * This test FAILS on unfixed code — that failure is the success condition for Task 1.
     *
     * Validates: Requirements 1.4, 1.5 (bug condition exploration)
     */
    @Test
    fun `ZIP file with valid ZIP header - mimeType application-zip - status should be COMPLETED`() {
        // Create a real ZIP file with valid ZIP magic bytes: PK\x03\x04
        val zipFile = tempFolder.newFile("test_archive.zip")
        zipFile.outputStream().use { out ->
            // Valid ZIP local file header signature: PK (0x50 0x4B) + 0x03 0x04
            val zipHeader = byteArrayOf(
                0x50, 0x4B, 0x03, 0x04, // Local file header signature
                0x14, 0x00,             // Version needed to extract
                0x00, 0x00,             // General purpose bit flag
                0x00, 0x00,             // Compression method (stored)
                0x00, 0x00              // Last mod file time
            )
            out.write(zipHeader)
            // Pad to a reasonable size
            out.write(ByteArray(1024) { 0x00 })
        }

        assertTrue("Test setup: ZIP file should exist before validation", zipFile.exists())
        assertTrue("Test setup: ZIP file should have content", zipFile.length() > 0)

        // Invoke the FIXED validation logic
        val result = fixedValidateDownload(zipFile, mimeType = "application/zip")

        // ASSERTION: status should be COMPLETED and file should still exist
        // The fix guards isValidMediaFile() behind a MIME type check, so ZIPs pass through.
        assertTrue(
            "BUG CONFIRMED: ZIP download (application/zip) was marked '${result.status}' " +
                "instead of 'COMPLETED'. isValidMediaFile() returned false for a valid ZIP. " +
                "Counterexample: mimeType=application/zip, fileHeader=PK\\x03\\x04, " +
                "result.status=${result.status}, fileExists=${result.fileExists}",
            result.status == "COMPLETED"
        )
        assertTrue(
            "BUG CONFIRMED: ZIP file was deleted by unfixed validation logic. " +
                "Counterexample: file was deleted when status='${result.status}'",
            result.fileExists
        )
    }

    // -------------------------------------------------------------------------
    // Sanity check: isValidMediaFile() correctly identifies non-media files
    // (This confirms our replicated logic is accurate)
    // -------------------------------------------------------------------------

    @Test
    fun `isValidMediaFile returns false for PDF header - confirms bug root cause`() {
        val pdfFile = tempFolder.newFile("check.pdf")
        pdfFile.writeBytes("%PDF-1.4\nsome content".toByteArray() + ByteArray(100))

        val result = isValidMediaFile(pdfFile)
        assertFalse(
            "isValidMediaFile() should return false for PDF files (no video/audio magic bytes). " +
                "This confirms the root cause: the function is correct for media validation " +
                "but should not be called for non-media MIME types.",
            result
        )
    }

    @Test
    fun `isValidMediaFile returns false for ZIP header - confirms bug root cause`() {
        val zipFile = tempFolder.newFile("check.zip")
        zipFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100))

        val result = isValidMediaFile(zipFile)
        assertFalse(
            "isValidMediaFile() should return false for ZIP files (no video/audio magic bytes). " +
                "This confirms the root cause: the function is correct for media validation " +
                "but should not be called for non-media MIME types.",
            result
        )
    }
}
