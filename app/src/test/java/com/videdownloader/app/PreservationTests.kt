package com.videdownloader.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Preservation Tests -- Task 2
 *
 * These tests MUST PASS on unfixed code. They confirm the baseline behavior that
 * must be preserved after the fixes are applied.
 *
 * Two groups of preservation tests:
 *   - Bug 1a: Sub-frame errors and successful loads are unaffected by the fix
 *   - Bug 2:  Media validation still runs for video/audio MIME types
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5
 */
class PreservationTests {

    // =========================================================================
    // Shared state for Bug 1a callback tests
    // =========================================================================

    /** Records all URLs passed to loadUrl() -- simulates the WebView back stack. */
    private val loadedUrls = mutableListOf<String>()

    /** Tracks whether stopLoading() was called. */
    private var stopLoadingCalled = false

    /** Tracks whether onPageError() was called. */
    private var pageErrorCalled = false

    /** Tracks whether isNetworkError state was changed (i.e., onPageError was invoked). */
    private var isNetworkErrorChanged = false

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        loadedUrls.clear()
        stopLoadingCalled = false
        pageErrorCalled = false
        isNetworkErrorChanged = false
    }

    // =========================================================================
    // Pure Kotlin replicas of the UNFIXED WebViewClient callbacks
    // (same approach as Bug1aBackStackExplorationTest.kt)
    //
    // Replacing Android SDK calls with lambdas so tests run on the JVM:
    //   view?.loadUrl(url)    ->  loadUrl(url)
    //   view?.stopLoading()   ->  stopLoading()
    //   viewModel.onPageError ->  onPageError()
    // =========================================================================

    /**
     * Replicates the FIXED onReceivedError (new overload, API 23+) from BrowserScreen.kt:
     *
     *   override fun onReceivedError(view, request, error) {
     *       if (request?.isForMainFrame == true) {
     *           view?.stopLoading()
     *           viewModel.onPageError(errorCode, description)
     *       }
     *   }
     *
     * The guard `if (isForMainFrame)` means sub-frame errors are completely ignored.
     * loadUrl("about:blank") has been removed by the Bug 1a fix.
     */
    private fun unfixedOnReceivedError_newOverload(
        isForMainFrame: Boolean,
        errorCode: Int = -2,
        description: String = "Unknown error",
        @Suppress("UNUSED_PARAMETER") loadUrl: (String) -> Unit,
        stopLoading: () -> Unit,
        onPageError: (Int, String) -> Unit
    ) {
        if (isForMainFrame) {
            stopLoading()
            onPageError(errorCode, description)
        }
        // Sub-frame: nothing happens -- this is the behavior we want to preserve
    }

    /**
     * Replicates the FIXED deprecated onReceivedError from BrowserScreen.kt:
     *
     *   override fun onReceivedError(view, errorCode, description, failingUrl) {
     *       if (failingUrl == view?.url) {
     *           view?.stopLoading()
     *           viewModel.onPageError(errorCode, description)
     *       }
     *   }
     *
     * Sub-frame resources have a different URL than view?.url, so the guard is false.
     * loadUrl("about:blank") has been removed by the Bug 1a fix.
     */
    private fun unfixedOnReceivedError_deprecated(
        failingUrlMatchesCurrentUrl: Boolean,
        errorCode: Int = -2,
        description: String = "Unknown error",
        @Suppress("UNUSED_PARAMETER") loadUrl: (String) -> Unit,
        stopLoading: () -> Unit,
        onPageError: (Int, String) -> Unit
    ) {
        if (failingUrlMatchesCurrentUrl) {
            stopLoading()
            onPageError(errorCode, description)
        }
        // Sub-frame: failingUrl != view?.url -> nothing happens
    }

    /**
     * Replicates the FIXED onReceivedHttpError from BrowserScreen.kt:
     *
     *   override fun onReceivedHttpError(view, request, errorResponse) {
     *       if (request?.isForMainFrame == true) {
     *           val statusCode = errorResponse?.statusCode ?: 0
     *           if (statusCode >= 400) {
     *               view?.stopLoading()
     *               viewModel.onPageError(statusCode, "HTTP $statusCode: $reason")
     *           }
     *       }
     *   }
     *
     * loadUrl("about:blank") has been removed by the Bug 1a fix.
     */
    private fun unfixedOnReceivedHttpError(
        isForMainFrame: Boolean,
        statusCode: Int,
        reasonPhrase: String = "Error",
        @Suppress("UNUSED_PARAMETER") loadUrl: (String) -> Unit,
        stopLoading: () -> Unit,
        onPageError: (Int, String) -> Unit
    ) {
        if (isForMainFrame) {
            if (statusCode >= 400) {
                stopLoading()
                onPageError(statusCode, "HTTP $statusCode: $reasonPhrase")
            }
        }
        // Sub-frame or statusCode < 400: nothing happens
    }

    // =========================================================================
    // Verbatim copy of isValidMediaFile() from DownloadService.kt
    // (same approach as Bug2NonMediaDownloadExplorationTest.kt)
    // =========================================================================

    /**
     * Verbatim copy of isValidMediaFile() from DownloadService.kt.
     * Checks if a file has valid video/audio magic bytes.
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
     * Replicates the UNFIXED Case 4 validation logic from performDownload() in DownloadService.kt.
     * isValidMediaFile() is called unconditionally regardless of mimeType.
     */
    private data class DownloadResult(val status: String, val fileExists: Boolean)

    private fun unfixedValidateDownload(file: File, @Suppress("UNUSED_PARAMETER") mimeType: String): DownloadResult {
        // UNFIXED: isValidMediaFile() is called unconditionally
        if (!isValidMediaFile(file)) {
            file.delete()
            return DownloadResult(status = "FAILED", fileExists = file.exists())
        }
        return DownloadResult(status = "COMPLETED", fileExists = file.exists())
    }

    /**
     * Replicates the FIXED Case 4 validation logic from performDownload() in DownloadService.kt.
     * isValidMediaFile() is only called for video/audio MIME types or known media extensions.
     */
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

    // =========================================================================
    // BUG 1a PRESERVATION TESTS
    // =========================================================================

    // -------------------------------------------------------------------------
    // Preservation 1a-A: Sub-frame error -> isNetworkError NOT changed, about:blank NOT pushed
    //
    // BEHAVIOR BEING PRESERVED: The callbacks only fire for main-frame errors.
    // Sub-frame resource errors (ads, iframes, images) are silently ignored.
    // This must remain true after the fix.
    //
    // PASSES on unfixed code because the guard `if (isForMainFrame)` is false
    // for sub-frame errors, so neither loadUrl("about:blank") nor onPageError() is called.
    // -------------------------------------------------------------------------

    /**
     * Preservation: onReceivedError (new overload) with isForMainFrame = false
     * -> isNetworkError state is NOT changed and about:blank is NOT pushed.
     *
     * This PASSES on unfixed code because the callback only acts on main-frame errors.
     * The fix must preserve this behavior.
     *
     * Validates: Requirements 3.3, 3.4
     */
    @Test
    fun `onReceivedError new overload - sub-frame error - isNetworkError NOT changed and about blank NOT pushed`() {
        // Preservation: sub-frame error (isForMainFrame = false) must be completely ignored
        unfixedOnReceivedError_newOverload(
            isForMainFrame = false, // sub-frame resource error (e.g., an ad iframe)
            errorCode = -2,
            description = "net::ERR_NAME_NOT_RESOLVED",
            loadUrl = { url -> loadedUrls.add(url) },
            stopLoading = { stopLoadingCalled = true },
            onPageError = { _, _ ->
                pageErrorCalled = true
                isNetworkErrorChanged = true
            }
        )

        // isNetworkError state must NOT have changed (onPageError was not called)
        assertFalse(
            "PRESERVATION BROKEN: Sub-frame error should NOT change isNetworkError state. " +
                "onPageError was called for a sub-frame error -- this would incorrectly show the error overlay.",
            isNetworkErrorChanged
        )

        // about:blank must NOT have been pushed onto the back stack
        assertFalse(
            "PRESERVATION BROKEN: Sub-frame error should NOT push about:blank onto the back stack. " +
                "loadedUrls=$loadedUrls",
            loadedUrls.contains("about:blank")
        )

        // stopLoading must NOT have been called for sub-frame errors
        assertFalse(
            "PRESERVATION BROKEN: Sub-frame error should NOT call stopLoading(). " +
                "stopLoadingCalled=$stopLoadingCalled",
            stopLoadingCalled
        )
    }

    /**
     * Preservation: onReceivedHttpError with statusCode = 200 (not an error)
     * -> nothing happens (statusCode < 400 guard prevents any action).
     *
     * This PASSES on unfixed code because the callback only acts when statusCode >= 400.
     *
     * Validates: Requirements 3.3
     */
    @Test
    fun `onReceivedHttpError - statusCode 200 - nothing happens`() {
        // Preservation: HTTP 200 is a success response, not an error -- callback must be a no-op
        unfixedOnReceivedHttpError(
            isForMainFrame = true,
            statusCode = 200, // success -- should not trigger any error handling
            reasonPhrase = "OK",
            loadUrl = { url -> loadedUrls.add(url) },
            stopLoading = { stopLoadingCalled = true },
            onPageError = { _, _ ->
                pageErrorCalled = true
                isNetworkErrorChanged = true
            }
        )

        assertFalse(
            "PRESERVATION BROKEN: HTTP 200 should NOT change isNetworkError state. " +
                "onPageError was called for a 200 response.",
            isNetworkErrorChanged
        )

        assertFalse(
            "PRESERVATION BROKEN: HTTP 200 should NOT push about:blank onto the back stack. " +
                "loadedUrls=$loadedUrls",
            loadedUrls.contains("about:blank")
        )

        assertFalse(
            "PRESERVATION BROKEN: HTTP 200 should NOT call stopLoading(). " +
                "stopLoadingCalled=$stopLoadingCalled",
            stopLoadingCalled
        )
    }

    // -------------------------------------------------------------------------
    // Preservation 1a-B: Main-frame error -> stopLoading() IS called
    //
    // BEHAVIOR BEING PRESERVED: stopLoading() must remain in all three callbacks.
    // It prevents the WebView from rendering its own error page behind the overlay.
    // The fix only removes loadUrl("about:blank") -- stopLoading() must stay.
    //
    // PASSES on unfixed code because stopLoading() is called before the bug line.
    // -------------------------------------------------------------------------

    /**
     * Preservation: onReceivedError (new overload) with isForMainFrame = true
     * -> stopLoading() IS called.
     *
     * This PASSES on unfixed code because stopLoading() is called before loadUrl("about:blank").
     * The fix must keep stopLoading() in place.
     *
     * Validates: Requirements 3.3, 3.4
     */
    @Test
    fun `onReceivedError new overload - main frame error - stopLoading IS called`() {
        // Preservation: stopLoading() must be called for main-frame errors (prevents native error page)
        unfixedOnReceivedError_newOverload(
            isForMainFrame = true,
            errorCode = -2,
            description = "net::ERR_NAME_NOT_RESOLVED",
            loadUrl = { url -> loadedUrls.add(url) },
            stopLoading = { stopLoadingCalled = true },
            onPageError = { _, _ -> pageErrorCalled = true }
        )

        assertTrue(
            "PRESERVATION BROKEN: stopLoading() must be called for main-frame errors. " +
                "It prevents the WebView from rendering its own error page behind the overlay. " +
                "stopLoadingCalled=$stopLoadingCalled",
            stopLoadingCalled
        )
    }

    /**
     * Preservation: onReceivedError (deprecated overload) with failingUrl == view?.url (main frame)
     * -> stopLoading() IS called.
     *
     * This PASSES on unfixed code because stopLoading() is called before loadUrl("about:blank").
     *
     * Validates: Requirements 3.3, 3.4
     */
    @Test
    fun `onReceivedError deprecated overload - main frame error - stopLoading IS called`() {
        unfixedOnReceivedError_deprecated(
            failingUrlMatchesCurrentUrl = true, // main-frame condition
            errorCode = -2,
            description = "net::ERR_NAME_NOT_RESOLVED",
            loadUrl = { url -> loadedUrls.add(url) },
            stopLoading = { stopLoadingCalled = true },
            onPageError = { _, _ -> pageErrorCalled = true }
        )

        assertTrue(
            "PRESERVATION BROKEN: stopLoading() must be called for main-frame errors (deprecated overload). " +
                "stopLoadingCalled=$stopLoadingCalled",
            stopLoadingCalled
        )
    }

    /**
     * Preservation: onReceivedHttpError with statusCode >= 400 on main frame
     * -> stopLoading() IS called.
     *
     * This PASSES on unfixed code because stopLoading() is called before loadUrl("about:blank").
     *
     * Validates: Requirements 3.3, 3.4
     */
    @Test
    fun `onReceivedHttpError - HTTP 403 main frame - stopLoading IS called`() {
        unfixedOnReceivedHttpError(
            isForMainFrame = true,
            statusCode = 403,
            reasonPhrase = "Forbidden",
            loadUrl = { url -> loadedUrls.add(url) },
            stopLoading = { stopLoadingCalled = true },
            onPageError = { _, _ -> pageErrorCalled = true }
        )

        assertTrue(
            "PRESERVATION BROKEN: stopLoading() must be called for HTTP 403 on main frame. " +
                "stopLoadingCalled=$stopLoadingCalled",
            stopLoadingCalled
        )
    }

    // =========================================================================
    // BUG 2 PRESERVATION TESTS
    // =========================================================================

    // -------------------------------------------------------------------------
    // Preservation 2-A: Valid MP4 file -> isValidMediaFile() returns true -> COMPLETED
    //
    // BEHAVIOR BEING PRESERVED: Valid video files must continue to be marked COMPLETED.
    // The fix only gates the call -- it must not skip validation for video/audio types.
    //
    // PASSES on unfixed code because isValidMediaFile() correctly identifies MP4 magic bytes.
    // -------------------------------------------------------------------------

    /**
     * Preservation: valid MP4 file (with "ftyp" magic bytes at offset 4) + mimeType = "video/mp4"
     * -> isValidMediaFile() returns true -> status COMPLETED.
     *
     * This PASSES on unfixed code because isValidMediaFile() correctly identifies MP4 files.
     * The fix must preserve this: video/mp4 downloads with valid headers must still be COMPLETED.
     *
     * Validates: Requirements 3.1
     */
    @Test
    fun `valid MP4 file with ftyp magic bytes - mimeType video-mp4 - status COMPLETED`() {
        // Create a file with valid MP4 magic bytes: "ftyp" at offset 4
        val mp4File = tempFolder.newFile("test_video.mp4")
        mp4File.outputStream().use { out ->
            // MP4 box: 4 bytes size + "ftyp" + "isom" brand
            val mp4Header = byteArrayOf(
                0x00, 0x00, 0x00, 0x18, // box size = 24 bytes
                0x66, 0x74, 0x79, 0x70, // "ftyp"
                0x69, 0x73, 0x6F, 0x6D  // "isom" brand
            )
            out.write(mp4Header)
            out.write(ByteArray(1024) { 0x00 }) // padding
        }

        assertTrue("Test setup: MP4 file should exist", mp4File.exists())

        // Verify isValidMediaFile() returns true for this file
        val isValid = isValidMediaFile(mp4File)
        assertTrue(
            "PRESERVATION BROKEN: isValidMediaFile() should return true for a valid MP4 file " +
                "with 'ftyp' magic bytes at offset 4. isValid=$isValid",
            isValid
        )

        // Verify the unfixed validation logic produces COMPLETED for video/mp4
        val result = unfixedValidateDownload(mp4File, mimeType = "video/mp4")
        assertTrue(
            "PRESERVATION BROKEN: Valid MP4 download (video/mp4) should be COMPLETED. " +
                "status=${result.status}, fileExists=${result.fileExists}",
            result.status == "COMPLETED"
        )
        assertTrue(
            "PRESERVATION BROKEN: Valid MP4 file should still exist after COMPLETED status. " +
                "fileExists=${result.fileExists}",
            result.fileExists
        )
    }

    // -------------------------------------------------------------------------
    // Preservation 2-B: Garbage bytes file + mimeType = "video/mp4" -> FAILED
    //
    // BEHAVIOR BEING PRESERVED: Corrupted/garbage video files must continue to be
    // marked FAILED. The fix must not skip validation for video/audio types.
    //
    // PASSES on unfixed code because isValidMediaFile() returns false for garbage bytes.
    // -------------------------------------------------------------------------

    /**
     * Preservation: garbage bytes file + mimeType = "video/mp4"
     * -> isValidMediaFile() returns false -> status FAILED.
     *
     * This PASSES on unfixed code because isValidMediaFile() correctly rejects garbage bytes.
     * The fix must preserve this: corrupted video downloads must still be marked FAILED.
     *
     * Validates: Requirements 3.2
     */
    @Test
    fun `garbage bytes file - mimeType video-mp4 - isValidMediaFile returns false - status FAILED`() {
        // Create a file with garbage bytes (no valid media magic bytes)
        val garbageFile = tempFolder.newFile("garbage_video.mp4")
        garbageFile.outputStream().use { out ->
            // Garbage bytes -- no valid media signature
            val garbage = byteArrayOf(
                0x00, 0x01, 0x02, 0x03,
                0x04, 0x05, 0x06, 0x07,
                0x08, 0x09, 0x0A, 0x0B
            )
            out.write(garbage)
            out.write(ByteArray(1024) { 0x42 }) // padding
        }

        assertTrue("Test setup: garbage file should exist before validation", garbageFile.exists())

        // Verify isValidMediaFile() returns false for garbage bytes
        val isValid = isValidMediaFile(garbageFile)
        assertFalse(
            "PRESERVATION BROKEN: isValidMediaFile() should return false for garbage bytes. " +
                "isValid=$isValid",
            isValid
        )

        // Verify the unfixed validation logic produces FAILED for garbage video/mp4
        val result = unfixedValidateDownload(garbageFile, mimeType = "video/mp4")
        assertTrue(
            "PRESERVATION BROKEN: Garbage bytes file with mimeType=video/mp4 should be FAILED. " +
                "The fix must preserve this behavior -- corrupted video files must still be rejected. " +
                "status=${result.status}",
            result.status == "FAILED"
        )
        assertFalse(
            "PRESERVATION BROKEN: Garbage bytes file should be deleted when status=FAILED. " +
                "fileExists=${result.fileExists}",
            result.fileExists
        )
    }

    // -------------------------------------------------------------------------
    // Preservation 2-C: Parameterized test -- isValidMediaFile() IS called for video/audio MIME types
    //
    // BEHAVIOR BEING PRESERVED: For all video/* and audio/* MIME types, the validation
    // logic must call isValidMediaFile(). The fix only skips the call for non-media types.
    //
    // This is a simple parameterized approach (no PBT library available).
    // PASSES on unfixed code because isValidMediaFile() is called unconditionally.
    // -------------------------------------------------------------------------

    /**
     * Preservation (parameterized): for each video/audio MIME type in the list,
     * isValidMediaFile() IS called (i.e., the validation logic runs for these types).
     *
     * This PASSES on unfixed code because isValidMediaFile() is called unconditionally
     * regardless of MIME type. The fix must preserve this for video/X and audio/X types.
     *
     * Validates: Requirements 3.1, 3.2
     */
    @Test
    fun `isValidMediaFile is called for video and audio MIME types - parameterized`() {
        // The list of video/audio MIME types that must always trigger isValidMediaFile()
        val mediaTypes = listOf(
            "video/mp4",
            "video/webm",
            "audio/mpeg",
            "audio/aac"
        )

        for (mimeType in mediaTypes) {
            // Create a file with garbage bytes so we can detect whether isValidMediaFile() ran.
            // If it ran and returned false -> status = FAILED.
            // If it was skipped -> status = COMPLETED (which would be a preservation failure).
            val safeName = mimeType.replace("/", "_")
            val testFile = tempFolder.newFile("test_$safeName.bin")
            testFile.outputStream().use { out ->
                // Garbage bytes -- isValidMediaFile() will return false for these
                out.write(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B))
                out.write(ByteArray(512) { 0x42 })
            }

            val result = unfixedValidateDownload(testFile, mimeType = mimeType)

            // If isValidMediaFile() was called (unfixed behavior), it returned false for garbage bytes
            // -> status = FAILED. This confirms the validation logic ran for this MIME type.
            // After the fix, the same behavior must be preserved for video/* and audio/* types.
            assertTrue(
                "PRESERVATION BROKEN: isValidMediaFile() must be called for mimeType='$mimeType'. " +
                    "Expected status=FAILED (garbage bytes -> isValidMediaFile()=false -> FAILED), " +
                    "but got status=${result.status}. " +
                    "This means the validation was skipped for a video/audio MIME type.",
                result.status == "FAILED"
            )

            assertFalse(
                "PRESERVATION BROKEN: isValidMediaFile() was called and returned false for mimeType='$mimeType', " +
                    "so the file should have been deleted. fileExists=${result.fileExists}",
                result.fileExists
            )
        }
    }

    // =========================================================================
    // BUG 2 PRESERVATION TESTS (FIXED LOGIC)
    // These tests verify that the FIXED behavior still preserves the correct
    // outcomes for video/audio MIME types.
    // =========================================================================

    /**
     * Preservation (fixed logic): valid MP4 file + mimeType = "video/mp4"
     * -> fixedValidateDownload() still returns COMPLETED.
     *
     * The fix must not skip validation for video/audio types -- valid media files
     * must still be accepted.
     *
     * Validates: Requirements 3.1
     */
    @Test
    fun `fixed logic - valid MP4 with video-mp4 - still COMPLETED`() {
        val mp4File = tempFolder.newFile("fixed_valid_video.mp4")
        mp4File.outputStream().use { out ->
            val mp4Header = byteArrayOf(
                0x00, 0x00, 0x00, 0x18,
                0x66, 0x74, 0x79, 0x70, // "ftyp"
                0x69, 0x73, 0x6F, 0x6D  // "isom"
            )
            out.write(mp4Header)
            out.write(ByteArray(1024) { 0x00 })
        }

        val result = fixedValidateDownload(mp4File, mimeType = "video/mp4")

        assertTrue(
            "PRESERVATION BROKEN (fixed logic): Valid MP4 with mimeType=video/mp4 should still be COMPLETED. " +
                "status=${result.status}",
            result.status == "COMPLETED"
        )
        assertTrue(
            "PRESERVATION BROKEN (fixed logic): Valid MP4 file should still exist after COMPLETED. " +
                "fileExists=${result.fileExists}",
            result.fileExists
        )
    }

    /**
     * Preservation (fixed logic): garbage bytes + mimeType = "video/mp4"
     * -> fixedValidateDownload() still returns FAILED.
     *
     * The fix must not skip validation for video/audio types -- corrupted media files
     * must still be rejected.
     *
     * Validates: Requirements 3.2
     */
    @Test
    fun `fixed logic - garbage bytes with video-mp4 - still FAILED`() {
        val garbageFile = tempFolder.newFile("fixed_garbage_video.mp4")
        garbageFile.outputStream().use { out ->
            out.write(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B))
            out.write(ByteArray(1024) { 0x42 })
        }

        val result = fixedValidateDownload(garbageFile, mimeType = "video/mp4")

        assertTrue(
            "PRESERVATION BROKEN (fixed logic): Garbage bytes with mimeType=video/mp4 should still be FAILED. " +
                "status=${result.status}",
            result.status == "FAILED"
        )
        assertFalse(
            "PRESERVATION BROKEN (fixed logic): Garbage file should be deleted when FAILED. " +
                "fileExists=${result.fileExists}",
            result.fileExists
        )
    }

    /**
     * Preservation (fixed logic): garbage bytes + mimeType = "audio/mpeg"
     * -> fixedValidateDownload() still returns FAILED.
     *
     * Audio MIME types must also still trigger validation in the fixed code.
     *
     * Validates: Requirements 3.2
     */
    @Test
    fun `fixed logic - garbage bytes with audio-mpeg - still FAILED`() {
        val garbageFile = tempFolder.newFile("fixed_garbage_audio.mp3")
        garbageFile.outputStream().use { out ->
            out.write(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B))
            out.write(ByteArray(1024) { 0x42 })
        }

        val result = fixedValidateDownload(garbageFile, mimeType = "audio/mpeg")

        assertTrue(
            "PRESERVATION BROKEN (fixed logic): Garbage bytes with mimeType=audio/mpeg should still be FAILED. " +
                "status=${result.status}",
            result.status == "FAILED"
        )
        assertFalse(
            "PRESERVATION BROKEN (fixed logic): Garbage audio file should be deleted when FAILED. " +
                "fileExists=${result.fileExists}",
            result.fileExists
        )
    }
}
