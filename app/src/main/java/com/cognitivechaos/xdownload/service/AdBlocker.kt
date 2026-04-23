package com.cognitivechaos.xdownload.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple ad blocker that checks URLs against a list of known ad domains.
 */
@Singleton
class AdBlocker @Inject constructor() {

    private val adHosts = setOf(
        "doubleclick.net",
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "ad.doubleclick.net",
        "ads.google.com",
        "facebook.com/tr",
        "connect.facebook.net/signals",
        "analytics.google.com",
        "google-analytics.com",
        "ssl.google-analytics.com",
        "adnxs.com",
        "adsrvr.org",
        "adsymptotic.com",
        "adtechus.com",
        "advertising.com",
        "amazon-adsystem.com",
        "bidswitch.net",
        "casalemedia.com",
        "chartbeat.com",
        "contextweb.com",
        "criteo.com",
        "crwdcntrl.net",
        "demdex.net",
        "exoclick.com",
        "imrworldwide.com",
        "mathtag.com",
        "moatads.com",
        "mookie1.com",
        "openx.net",
        "outbrain.com",
        "popads.net",
        "pubmatic.com",
        "quantserve.com",
        "rlcdn.com",
        "rubiconproject.com",
        "scorecardresearch.com",
        "serving-sys.com",
        "sharethrough.com",
        "simpli.fi",
        "taboola.com",
        "tapad.com",
        "tidaltv.com",
        "turn.com",
        "yieldmanager.com",
        "yieldmo.com",
        "zedo.com"
    )

    fun isAd(url: String): Boolean {
        val host = try {
            java.net.URL(url).host.lowercase()
        } catch (e: Exception) {
            return false
        }

        return adHosts.any { adHost ->
            host == adHost || host.endsWith(".$adHost")
        }
    }
}
