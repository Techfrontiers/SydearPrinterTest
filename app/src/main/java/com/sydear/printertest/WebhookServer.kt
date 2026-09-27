package com.sydear.printertest

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * HTTP server จิ๋วบนมือถือ — ให้สั่งพิมพ์ผ่าน webhook ได้จากเน็ตเวิร์ก
 * ไม่ใช้ library ภายนอก ใช้ ServerSocket ล้วนๆ
 *
 * Endpoints:
 *   GET  /         → ข้อมูล service
 *   GET  /status   → {ok, connected, printer, version}
 *   POST /print    → body JSON {text, bold, italic, underline, size, align}
 *                    ส่งคีย์ทาง ?key= หรือ header X-Webhook-Key
 */
class WebhookServer(
    private val port: Int,
    private var apiKey: String,
    private val onPrint: (text: String, style: PrintStyle) -> PrintResult,
    private val onStatus: () -> JSONObject,
    private val log: (String) -> Unit
) {
    data class PrintStyle(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val size: String,   // S / M / L / XL
        val align: String   // left / center / right
    )
    data class PrintResult(val ok: Boolean, val message: String)

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    val isRunning get() = running

    fun setApiKey(k: String) { apiKey = k }

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket(port)
            serverSocket = ss
            running = true
            thread(name = "webhook-accept", isDaemon = true) {
                log("Webhook เปิดที่พอร์ต $port")
                while (running) {
                    try {
                        val client = ss.accept()
                        thread(name = "webhook-conn", isDaemon = true) { handleClient(client) }
                    } catch (_: Exception) { /* socket closed */ }
                }
            }
            true
        } catch (e: Exception) {
            log("เปิด Webhook ไม่ได้: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        log("Webhook ปิดแล้ว")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val parsed = parseRequest(socket.getInputStream()) ?: run { socket.close(); return }
            val (code, json) = route(parsed.method, parsed.path, parsed.queryKey, parsed.headers, parsed.body)
            val out = socket.getOutputStream()
            out.write(buildResponse(code, json))
            out.flush()
        } catch (e: Exception) {
            try {
                val out = socket.getOutputStream()
                out.write(buildResponse(500, JSONObject().put("ok", false).put("error", "server error")))
                out.flush()
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    internal data class ParsedRequest(
        val method: String,
        val path: String,
        val queryKey: String?,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    /** แยก parse ออกมาเพื่อเทสได้โดยไม่ต้องใช้ socket จริง */
    internal fun parseRequest(input: java.io.InputStream): ParsedRequest? {
        // อ่าน header จนเจอ \r\n\r\n
        val headerBuf = ByteArrayOutputStream()
        val one = ByteArray(1)
        var matched = 0
        while (true) {
            val n = input.read(one)
            if (n <= 0) break
            headerBuf.write(one[0].toInt())
            matched = when {
                one[0] == 13.toByte() && matched == 0 -> 1
                one[0] == 10.toByte() && matched == 1 -> 2
                one[0] == 13.toByte() && matched == 2 -> 3
                one[0] == 10.toByte() && matched == 3 -> 4
                one[0] == 13.toByte() -> 1
                else -> 0
            }
            if (matched == 4) break
            if (headerBuf.size() > 32768) break
        }
        val lines = headerBuf.toString("UTF-8").split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) return null
        val reqLine = lines[0].split(" ")
        if (reqLine.size < 2) return null
        val method = reqLine[0].uppercase()
        val rawPath = reqLine[1]
        // รองรับ absolute-form (เช่น GET http://host:port/path) ที่ proxy/tunnel ส่งมา:
        // ตัด scheme://host ออกให้เหลือ origin-form ก่อนแยก path/query
        val originPath = rawPath.replace(Regex("^https?://[^/]+"), "").ifEmpty { "/" }
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(":")
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] =
                lines[i].substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        var body = ByteArray(0)
        if (contentLength in 1..1048576) {
            body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            if (read < contentLength) body = body.copyOf(read)
        }
        val pathOnly = originPath.substringBefore("?")
        val queryKey = originPath.substringAfter("?", "").split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "key") kv[1] else null
        }.firstOrNull()
        return ParsedRequest(method, pathOnly, queryKey, headers, body)
    }

    internal fun route(
        method: String, path: String, queryKey: String?,
        headers: Map<String, String>, body: ByteArray
    ): Pair<Int, JSONObject> {
        if (method == "GET" && (path == "/" || path.isEmpty())) {
            return 200 to JSONObject()
                .put("ok", true)
                .put("service", "SYDEAR Printer Webhook")
                .put("endpoints", "GET /status, POST /print")
        }
        if (method == "GET" && path == "/status") {
            return 200 to onStatus().put("ok", true)
        }
        if (method == "POST" && path == "/print") {
            val key = queryKey ?: headers["x-webhook-key"]
            if (key != apiKey) return 401 to JSONObject().put("ok", false).put("error", "invalid key")
            val json = try { JSONObject(body.toString(StandardCharsets.UTF_8)) } catch (_: Exception) { null }
                ?: return 400 to JSONObject().put("ok", false).put("error", "invalid json")
            val text = json.optString("text", "")
            if (text.isBlank()) return 400 to JSONObject().put("ok", false).put("error", "empty text")
            val style = PrintStyle(
                bold = json.optBoolean("bold", false),
                italic = json.optBoolean("italic", false),
                underline = json.optBoolean("underline", false),
                size = json.optString("size", "M"),
                align = json.optString("align", "left")
            )
            val res = try { onPrint(text, style) }
                      catch (e: Exception) { PrintResult(false, e.message ?: "print error") }
            return (if (res.ok) 200 else 500) to JSONObject()
                .put("ok", res.ok)
                .put(if (res.ok) "message" else "error", res.message)
        }
        return 404 to JSONObject().put("ok", false).put("error", "not found")
    }

    internal fun buildResponse(code: Int, json: JSONObject): ByteArray {
        val body = json.toString().toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        return head.toByteArray(StandardCharsets.UTF_8) + body
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "Error"
    }
}
