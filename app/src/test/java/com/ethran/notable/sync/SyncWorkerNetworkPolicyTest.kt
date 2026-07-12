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
        assertTrue(isLikelyLocalServerUrl("http://169.254.10.5/dav"))
    }

    @Test
    fun tailnetHosts_doNotRequireValidatedInternet() {
        assertTrue(isLikelyLocalServerUrl("http://100.101.102.103/dav"))
    }

    @Test
    fun localIpv6Hosts_doNotRequireValidatedInternet() {
        assertTrue(isLikelyLocalServerUrl("http://[::1]:8080/dav"))
        assertTrue(isLikelyLocalServerUrl("http://[fe80::1]/dav"))
        assertTrue(isLikelyLocalServerUrl("http://[fd12:3456::1]/dav"))
    }

    @Test
    fun schemelessLocalUrls_doNotRequireValidatedInternet() {
        assertTrue(isLikelyLocalServerUrl("192.168.1.5:8080/dav"))
    }

    @Test
    fun remoteAndMalformedHosts_requireValidatedInternet() {
        assertFalse(isLikelyLocalServerUrl("https://dav.example.com/webdav"))
        assertFalse(isLikelyLocalServerUrl("http://[2001:db8::1]/dav"))
        assertFalse(isLikelyLocalServerUrl("http://8.8.8.8/dav"))
        assertFalse(isLikelyLocalServerUrl("http://999.168.1.1/dav"))
        assertFalse(isLikelyLocalServerUrl("http://100.30.1.1/dav"))
        assertFalse(isLikelyLocalServerUrl("not a url"))
        assertFalse(isLikelyLocalServerUrl(""))
    }
}
