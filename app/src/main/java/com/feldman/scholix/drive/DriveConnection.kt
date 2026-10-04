package com.feldman.scholix.drive

import java.net.UnknownHostException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

object DriveConnection {
    fun unavailable(error:Throwable):Boolean {
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var current:Throwable?=error
        while(current!=null && seen.add(current)) {
            if(current is UnknownHostException || current is ConnectException || current is NoRouteToHostException || current is SocketTimeoutException)return true
            current=current.cause
        }
        return false
    }
    const val OFFLINE="Google Drive is unreachable. You can keep viewing saved files."
    const val PENDING="Saved on this device · Drive backup waiting for a connection"
}
