package com.feldman.scholix.drive

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException

class DriveConnectionTest {
    @Test fun dnsAndWrappedConnectionFailuresAreOffline() {
        assertTrue(DriveConnection.unavailable(UnknownHostException("www.googleapis.com")))
        assertTrue(DriveConnection.unavailable(IOException("wrapped",ConnectException())))
        assertTrue(DriveConnection.unavailable(SocketTimeoutException()))
        assertFalse(DriveAuth.message(UnknownHostException("www.googleapis.com")).contains("www.googleapis.com"))
    }
    @Test fun permissionAndFileFailuresRemainErrors() {
        assertFalse(DriveConnection.unavailable(DriveHttpError(403)))
        assertFalse(DriveConnection.unavailable(DriveNeedsConsent()))
        assertFalse(DriveConnection.unavailable(IOException("Missing cached file")))
    }
    @Test fun cyclicCauseChainsTerminate() {
        val first=IOException("first");val second=IOException("second")
        first.initCause(second);second.initCause(first)
        assertFalse(DriveConnection.unavailable(first))
    }
}
