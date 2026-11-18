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
    private val token = UUID.randomUUID().toString()
    private val capability = "${context.packageName}.${UUID.randomUUID()}.study-content"
    private var sha256 = ""
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private var registering = false
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
    private val ready = CompletableDeferred<JSONObject>()
    private val link = SeamlessLink(context.applicationContext) { state ->
        if (closed.get()) return@SeamlessLink
        if (state.optBoolean("connected") && !ready.isCompleted && !registering) {
            registering = true
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

    init {
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val socket = server.accept()
                    connections.add(socket)
                    if (closed.get()) {
                        connections.remove(socket)
                        socket.close()
                        break
                    }
                    launch {
                        try { socket.use {
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
                        } } catch (_: java.io.IOException) { /* A failed route must not close the publisher before a retry. */ }
                        finally { connections.remove(socket) }
                    }
                }
            } catch (_: java.io.IOException) { close() }
        }
    }

    suspend fun publish(): JSONObject {
        return try {
            sha256 = withContext(Dispatchers.IO) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            withContext(Dispatchers.Main.immediate) {
                check(!closed.get()) { "Study transfer closed." }
                check(link.connect()) { "Install or connect Seamless to transfer this study." }
            }
            withTimeout(15_000) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw java.io.IOException("Could not connect Seamless. Reconnect it and try again.", e)
        } catch (e: Exception) { close(); throw e }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        connections.forEach { runCatching { it.close() } }
        scope.launch {
            try {
                link.unregister(capability)
                link.close()
            } finally {
                ready.completeExceptionally(java.io.IOException("Study transfer closed."))
                scope.cancel()
            }
        }
    }

}
