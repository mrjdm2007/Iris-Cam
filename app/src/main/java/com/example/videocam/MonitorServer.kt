package com.example.videocam

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Small web server for the Wi-Fi monitor. Serves the monitor page, a live MJPEG picture, live audio (WebSocket)
 * and a few remote-control commands. Everything needs the PIN.
 */
class MonitorServer(private val host: Host, val pin: String) {
    interface Host {
        fun page(): ByteArray
        fun stateJson(): String
        fun command(name: String, arg: String): String
        fun clientsChanged(video: Int, audio: Int)
    }

    private var ss: ServerSocket? = null
    @Volatile
    private var pool = Executors.newCachedThreadPool()
    private val frameLock = Object()

    @Volatile
    private var frame: ByteArray? = null

    @Volatile
    private var frameId = 0L

    @Volatile
    private var running = false

    private val videoCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val h264Count = java.util.concurrent.atomic.AtomicInteger(0)
    private val audioClients = CopyOnWriteArrayList<WsClient>()
    private val h264Clients = CopyOnWriteArrayList<WsClient>()
    @Volatile private var latestH264Config: ByteArray? = null

    val mjpegClients: Int get() = videoCount.get()
    val h264ClientCount: Int get() = h264Count.get()
    val videoClients: Int get() = videoCount.get() + h264Count.get()
    val audioClientCount: Int get() = audioClients.size
    var port = 8080
        private set

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        // stop() shuts the executor down; recreate it so the monitor can be started again
        // after the user leaves and re-enters the monitor panel.
        if (pool.isShutdown || pool.isTerminated) pool = Executors.newCachedThreadPool()
        val executor = pool
        for (p in 8080..8090) {
            try {
                ss = ServerSocket(p)
                port = p
                break
            } catch (_: Exception) {
            }
        }
        val s = ss ?: return false
        running = true
        executor.execute {
            while (running) {
                try {
                    val c = s.accept()
                    executor.execute { handle(c) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            ss?.close()
        } catch (_: Exception) {
        }
        ss = null
        synchronized(frameLock) { frameLock.notifyAll() }
        audioClients.forEach { it.close() }
        audioClients.clear()
        h264Clients.forEach { it.close() }
        h264Clients.clear()
        latestH264Config = null
        pool.shutdownNow()
    }

    fun pushFrame(jpeg: ByteArray) {
        frame = jpeg
        synchronized(frameLock) {
            frameId++
            frameLock.notifyAll()
        }
    }

    fun pushH264(packet: ByteArray) {
        if (packet.isEmpty()) return
        if (packet[0].toInt() == 1) latestH264Config = packet
        for (c in h264Clients) c.send(packet)
    }

    fun pushAudio(buf: ByteArray, n: Int) {
        if (audioClients.isEmpty()) return
        val data = buf.copyOf(n)
        for (c in audioClients) c.send(data)
    }

    // ---------------- request handling ----------------

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val sb = StringBuilder()
        var count = 0
        while (count < 8192) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            count++
            val n = sb.length
            if (n >= 4 && sb[n - 1] == '\n' && sb[n - 2] == '\r' && sb[n - 3] == '\n' && sb[n - 4] == '\r') break
        }
        val lines = sb.toString().split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val full = parts[1]
        val qi = full.indexOf('?')
        val path = if (qi >= 0) full.substring(0, qi) else full
        val query = HashMap<String, String>()
        if (qi >= 0) {
            for (kv in full.substring(qi + 1).split("&")) {
                val e = kv.indexOf('=')
                if (e > 0) query[kv.substring(0, e)] = try {
                    URLDecoder.decode(kv.substring(e + 1), "UTF-8")
                } catch (_: Exception) {
                    kv.substring(e + 1)
                }
            }
        }
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return Request(parts[0], path, query, headers)
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 15000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val req = readRequest(input) ?: return
            if (req.query["pin"] != pin) {
                respond(out, 403, "text/plain", "Wrong or missing PIN".toByteArray())
                return
            }
            when (req.path) {
                "/", "/index.html" -> respond(out, 200, "text/html; charset=utf-8", host.page())
                "/state" -> respond(out, 200, "application/json", host.stateJson().toByteArray())
                "/cmd" -> {
                    val r = host.command(req.query["c"] ?: "", req.query["a"] ?: "")
                    respond(out, 200, "text/plain", r.toByteArray())
                }
                "/stream" -> {
                    sock.soTimeout = 0
                    streamVideo(out)
                }
                "/ws" -> {
                    sock.soTimeout = 0
                    websocket(sock, req, out)
                }
                else -> respond(out, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun streamVideo(out: OutputStream) {
        videoCount.incrementAndGet()
        host.clientsChanged(videoCount.get(), audioClients.size)
        try {
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frm\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
            )
            var last = -1L
            while (running) {
                var f: ByteArray? = null
                synchronized(frameLock) {
                    if (frameId == last) frameLock.wait(1000)
                    if (frameId != last) {
                        f = frame
                        last = frameId
                    }
                }
                val data = f ?: continue
                out.write(("--frm\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n\r\n").toByteArray())
                out.write(data)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            videoCount.decrementAndGet()
            host.clientsChanged(videoClients, audioClients.size)
        }
    }

    private class WsClient(val sock: Socket, val out: OutputStream) {
        @Volatile
        var alive = true
        private val q = java.util.concurrent.LinkedBlockingQueue<ByteArray>(40)

        fun send(d: ByteArray) {
            if (!alive) return
            if (!q.offer(d)) {
                q.poll()
                q.offer(d)
            }
        }

        fun pump() {
            try {
                while (alive) {
                    val d = q.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    val head = when {
                        d.size < 126 -> byteArrayOf(0x82.toByte(), d.size.toByte())
                        d.size <= 65535 -> byteArrayOf(0x82.toByte(), 126, (d.size shr 8).toByte(), (d.size and 0xFF).toByte())
                        else -> ByteBuffer.allocate(10).order(java.nio.ByteOrder.BIG_ENDIAN)
                            .put(0x82.toByte()).put(127).putLong(d.size.toLong()).array()
                    }
                    out.write(head)
                    out.write(d)
                    out.flush()
                }
            } catch (_: Exception) {
            }
            alive = false
        }

        fun close() {
            alive = false
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun websocket(sock: Socket, req: Request, out: OutputStream) {
        val key = req.headers["sec-websocket-key"] ?: return
        val sha = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        val acc = android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP)
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acc\r\n\r\n").toByteArray()
        )
        out.flush()
        val c = WsClient(sock, out)
        if (req.query["type"] == "h264") {
            h264Clients.add(c)
            h264Count.incrementAndGet()
            latestH264Config?.let { c.send(it) }
            host.clientsChanged(videoClients, audioClients.size)
            try {
                c.pump()
            } finally {
                h264Clients.remove(c)
                h264Count.decrementAndGet()
                host.clientsChanged(videoClients, audioClients.size)
            }
        } else {
            audioClients.add(c)
            host.clientsChanged(videoClients, audioClients.size)
            try {
                c.pump()
            } finally {
                audioClients.remove(c)
                host.clientsChanged(videoClients, audioClients.size)
            }
        }
    }
}
