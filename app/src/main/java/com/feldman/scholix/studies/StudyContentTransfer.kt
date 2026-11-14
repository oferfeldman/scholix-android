package com.feldman.scholix.studies

import android.content.Context
import com.seamless.link.api.SeamlessLink
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID

/** A short-lived, loopback-only source; Seamless owns the authenticated remote transport. */
internal class StudyContentTransfer(context: Context, private val file: File, private val onReceipt: (String) -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 600_000 }
    private val capability = "${context.packageName}.study-content"
    private var sha256 = ""
    private val token = UUID.randomUUID().toString()
    private val ready = CompletableDeferred<JSONObject>()
    private val link = SeamlessLink(context.applicationContext) { state ->
        if (state.optBoolean("connected") && !ready.isCompleted) {
            linkRegister(state.getString("deviceId"))
        } else if (state.has(SeamlessLink.ERROR)) ready.completeExceptionally(IllegalStateException("Connect Seamless to transfer study files."))
    }

    private fun linkRegister(deviceId: String) {
        link.request("register", JSONObject().put("capability", capability).put("port", server.localPort)) { result ->
            if (result.containsKey(SeamlessLink.ERROR)) ready.completeExceptionally(IllegalStateException("Allow Scholix access in Seamless to transfer study files."))
            else ready.complete(JSONObject().put("sourceDeviceId", deviceId).put("contentToken", token)
                .put("contentCapability", capability).put("contentSha256", sha256))
        }
    }

    suspend fun publish(): JSONObject {
        sha256 = withContext(Dispatchers.IO) { MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } }
        check(link.connect()) { "Install or connect Seamless to transfer this study." }
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) server.accept().use { socket ->
                    socket.soTimeout = 600_000
                    val input = DataInputStream(socket.getInputStream())
                    if (input.readUTF() == token) {
                        val output = DataOutputStream(socket.getOutputStream())
                        output.writeLong(file.length())
                        file.inputStream().use { it.copyTo(output) }
                        output.flush()
                        val receipt = input.readUTF()
                        withContext(Dispatchers.Main) { onReceipt(receipt) }
                        close()
                    }
                }
            } catch (_: java.io.IOException) { close() }
        }
        return try { withTimeout(15_000) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw java.io.IOException("Could not connect Seamless. Reconnect it and try again.", e)
        } catch (e: Exception) { close(); throw e }
    }

    override fun close() {
        runCatching { server.close() }
        link.unregister(capability)
        link.close()
        scope.cancel()
    }

}
