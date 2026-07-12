package com.ethran.notable.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkerNetworkPolicyTest {
    @Test
    fun localWebDavHosts_doNotRequireValidatedInternet() {
        assertTrue(isLikelyLocalServerUrl("http://192.168.1.20:8080/webdav"))
        assertTrue(isLikelyLocalServerUrl("https://10.0.0.3/dav"))
        assertTrue(isLikelyLocalServerUrl("http://notes.local/webdav"))
        assertTrue(isLikelyLocalServerUrl("http://localhost:8080"))
    }

    @Test
    fun remoteAndMalformedHosts_requireValidatedInternet() {
        assertFalse(isLikelyLocalServerUrl("https://dav.example.com/webdav"))
        assertFalse(isLikelyLocalServerUrl("not a url"))
        assertFalse(isLikelyLocalServerUrl(""))
    }
}
