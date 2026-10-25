package com.blank.app.util

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

object DiagnosticHttpServer {

    private const val TAG = "DiagServer"
    private const val PORT = 8080
    private var serverSocket: ServerSocket? = null
    private var workerThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        workerThread = Thread({
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                Log.i(TAG, "Diagnostic HTTP server running on port $PORT")

                while (isRunning.get() && !ss.isClosed) {
                    val client = ss.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Diagnostic server closed: ${e.message}")
                }
            }
        }, "BlankDiagServer").apply { start() }
    }

    private fun handleClient(client: Socket) {
        Thread {
            try {
                client.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val line = reader.readLine() ?: return@use
                    val out: OutputStream = s.getOutputStream()

                    val html = LiveQueryMonitor.getHtmlDashboard()
                    val bytes = html.toByteArray(Charsets.UTF_8)

                    val response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"

                    out.write(response.toByteArray(Charsets.UTF_8))
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: Exception) {
                // Ignore client socket errors
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        workerThread?.interrupt()
        workerThread = null
    }
}
