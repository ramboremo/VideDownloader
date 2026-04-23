package com.cognitivechaos.xdownload.service

import com.cognitivechaos.xdownload.data.model.DetectedMedia

/**
 * Pure, stateless ranker for detected media candidates.
 *
 * All functions are pure: no I/O, no side effects, no Android dependencies.
 * Safe to call from any thread and trivially unit-testable on the JVM.
 *
 * Guiding principle: ranking ≠ filtering.
 * Every candidate receives a score; none are ever removed from the list.
 */
object VideoRanker {

    // ── Score weights ────────────────────────────────────────────────────────

    // URL type scores
    const val SCORE_PH_GET_MEDIA: Int = 10_000
    const val SCORE_DIRECT_VIDEO: Int = 5_000
    const val SCORE_STREAM_MANIFEST: Int = 3_000

    // Metadata bonuses
    const val SCORE_HAS_THUMBNAIL: Int = 200
    const val SCORE_HAS_TITLE: Int = 50

    // Temporal bonus: +100 per detection index, capped at +2 000
    // Cap ensures a PH get_media URL detected first (+10 000) still outranks
    // a generic MP4 detected last (+5 000 + 2 000 = +7 000).
    const val TEMPORAL_BONUS_PER_INDEX: Int = 100
    const val TEMPORAL_MAX_BONUS: Int = 2_000

    // File size bonus: +1 per 100 KB, capped at +3 000
    const val FILE_SIZE_BONUS_PER_100KB: Int = 1
    const val FILE_SIZE_MAX_BONUS: Int = 3_000

    // Playback state
    const val PLAYING_BONUS: Int = 4_000
    const val VISIBLE_BONUS: Int = 1_000
    const val HIDDEN_PENALTY: Int = -500

    // Ad penalties
    const val AD_URL_PENALTY: Int = -8_000
    const val AD_UI_PENALTY: Int = -3_000

    // Tiny-file penalty (< 500 KB)
    const val TINY_FILE_THRESHOLD: Long = 500_000L
    const val TINY_FILE_PENALTY: Int = -8_000

    // ── Ad-network URL patterns ──────────────────────────────────────────────

    val AD_URL_PATTERNS: List<String> = listOf(
        "vast", "vpaid", "preroll", "adserver", "doubleclick", "trafficjunky",
        "exoclick", "adnxs", "adsystem", "adtech", "advertising", "adform",
        "smartadserver", "rubiconproject", "openx", "pubmatic", "appnexus",
        "spotxchange", "spotx", "freewheel", "innovid", "tremor", "taboola",
        "outbrain", "revcontent", "mgid", "propellerads", "adsterra",
        "cdn77-vid", "magsrv",
        "/ads/", "/ad/", "ad_tag", "adtag", "ad_unit", "adunit",
        "midroll", "postroll",
        "vast.xml", "vast.php", "vast?", "vpaid.js"
    )

    // ── PH host patterns (for URL-type detection) ────────────────────────────

    private val PH_HOSTS = listOf("pornhub.com", "pornhub.net", "pornhub.org")

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Individual signal contributions that sum to [total].
     */
    data class ScoreBreakdown(
        val typeScore: Int,
        val thumbnailBonus: Int,
        val titleBonus: Int,
        val temporalBonus: Int,
        val fileSizeBonus: Int,
        val playbackBonus: Int,
        val adUrlPenalty: Int,
        val adUiPenalty: Int,
        val tinyFilePenalty: Int,
        val total: Int
    )

    /**
     * Pure scoring function.
     *
     * @param candidate       The media candidate to score.
     * @param prefetchedSize  Pre-fetched file size in bytes, or null if unknown.
     */
    fun score(candidate: DetectedMedia, prefetchedSize: Long? = null): ScoreBreakdown {
        val url = candidate.url.lowercase()

        // ── URL type ────────────────────────────────────────────────────────
        val isPhGetMedia = PH_HOSTS.any { url.contains(it) } && url.contains("get_media")
        val isDirectVideo = url.substringBefore("?").let { u ->
            u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".m4v") ||
            u.endsWith(".mov") || u.endsWith(".mkv") || u.endsWith(".avi")
        }
        val isStream = url.contains(".m3u8") || url.contains(".mpd")

        val typeScore = when {
            isPhGetMedia -> SCORE_PH_GET_MEDIA
            isDirectVideo -> SCORE_DIRECT_VIDEO
            isStream -> SCORE_STREAM_MANIFEST
            else -> 0
        }

        // ── Metadata bonuses ────────────────────────────────────────────────
        val thumbnailBonus = if (!candidate.thumbnailUrl.isNullOrBlank() &&
            candidate.thumbnailUrl != "null") SCORE_HAS_THUMBNAIL else 0
        val titleBonus = if (!candidate.title.isNullOrBlank()) SCORE_HAS_TITLE else 0

        // ── Temporal bonus ──────────────────────────────────────────────────
        val temporalBonus = minOf(
            candidate.detectionIndex * TEMPORAL_BONUS_PER_INDEX,
            TEMPORAL_MAX_BONUS
        )

        // ── File size bonus ─────────────────────────────────────────────────
        val effectiveSize = prefetchedSize ?: candidate.fileSize
        val fileSizeBonus = if (effectiveSize != null && effectiveSize > 0) {
            minOf((effectiveSize / 100_000L).toInt() * FILE_SIZE_BONUS_PER_100KB, FILE_SIZE_MAX_BONUS)
        } else 0

        // ── Tiny-file penalty ───────────────────────────────────────────────
        val tinyFilePenalty = if (effectiveSize != null && effectiveSize < TINY_FILE_THRESHOLD)
            TINY_FILE_PENALTY else 0

        // ── Playback state ──────────────────────────────────────────────────
        val playbackBonus = when {
            candidate.isPlaying == true -> PLAYING_BONUS
            candidate.isVisible == true -> VISIBLE_BONUS
            candidate.isVisible == false -> HIDDEN_PENALTY
            else -> 0  // null = unknown, no adjustment
        }

        // ── Ad URL penalty ──────────────────────────────────────────────────
        val adUrlPenalty = if (AD_URL_PATTERNS.any { url.contains(it) }) AD_URL_PENALTY else 0

        // ── Ad UI penalty ───────────────────────────────────────────────────
        val adUiPenalty = if (candidate.hasAdUIPatterns == true) AD_UI_PENALTY else 0

        val total = typeScore + thumbnailBonus + titleBonus + temporalBonus +
            fileSizeBonus + tinyFilePenalty + playbackBonus + adUrlPenalty + adUiPenalty

        return ScoreBreakdown(
            typeScore = typeScore,
            thumbnailBonus = thumbnailBonus,
            titleBonus = titleBonus,
            temporalBonus = temporalBonus,
            fileSizeBonus = fileSizeBonus,
            playbackBonus = playbackBonus,
            adUrlPenalty = adUrlPenalty,
            adUiPenalty = adUiPenalty,
            tinyFilePenalty = tinyFilePenalty,
            total = total
        )
    }

    /**
     * Rank a list of candidates, highest score first.
     * Ties are broken by descending [DetectedMedia.detectionIndex] (later-detected wins).
     *
     * The output list always contains exactly the same candidates as the input —
     * no candidate is ever removed.
     *
     * @param candidates    Candidates to rank.
     * @param fileSizes     Map of URL → pre-fetched file size in bytes.
     */
    fun rank(
        candidates: List<DetectedMedia>,
        fileSizes: Map<String, Long> = emptyMap()
    ): List<DetectedMedia> {
        return candidates.sortedWith(
            compareByDescending<DetectedMedia> { score(it, fileSizes[it.url]).total }
                .thenByDescending { it.detectionIndex }
        )
    }
}
