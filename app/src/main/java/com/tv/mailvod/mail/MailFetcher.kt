package com.tv.mailvod.mail

import com.tv.mailvod.config.Config
import com.tv.mailvod.store.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.util.ByteArrayDataSource
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * 分层邮件拉取:
 *   IMAP 协议层 —— 纯手写 SSL Socket (LOGIN / ID / SELECT / SEARCH / FETCH RFC822)
 *                   零连接池,零反射,全部命令走同一条物理 TCP+TLS 通道
 *   MIME 解析层 —— JavaMail MimeMessage 解析 RFC822 原始字节
 *                   附件优先 (application/json 或 .json 文件名),正文 text/plain fallback
 *                   JavaMail 自动解 multipart / base64 / GBK / QP / 所有编码
 */
class MailFetcher {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val TAG = "MailFetcher"

    private val jsonArrayRegex = Regex("""\[[\s\S]*\]""")
    private val jsonBlockRegex = Regex("""\{[\s\S]*\}""")

    suspend fun fetch(config: Config): List<VideoItem> = withContext(Dispatchers.IO) {
        val mail = config.mail
        require(mail.user.isNotBlank() && mail.authCode.isNotBlank()) {
            "config.json mail.user / mail.auth_code 未配置"
        }
        val client = ImapClient(mail.host, mail.port)
        client.connect()
        try {
            client.login(mail.user, mail.authCode)
            client.id()
            client.select(mail.folder)
            val ids = client.searchAll()
            Log.i(TAG, "FETCHING ${ids.size} messages total")

            val session = Session.getInstance(java.util.Properties())
            val results = mutableListOf<VideoItem>()
            for (seq in ids) {
                val rfc822Bytes = client.fetchRfc822(seq)
                if (rfc822Bytes == null) {
                    Log.w(TAG, "MSG#$seq FETCH RFC822 got null, skip")
                    continue
                }
                val msgResult = runCatching {
                    MimeMessage(session, ByteArrayInputStream(rfc822Bytes))
                }
                if (msgResult.isFailure) {
                    Log.w(TAG, "MSG#$seq MimeMessage parse FAILED: ${msgResult.exceptionOrNull()?.message}")
                    continue
                }
                val msg = msgResult.getOrNull()!!
                val subject = msg.subject?.trim().orEmpty()
                Log.i(TAG, "MSG#$seq subject=[$subject] rfc822.len=${rfc822Bytes.size}")
                if (!subject.startsWith(mail.subjectPrefix)) {
                    Log.i(TAG, "  SKIP: prefix not match")
                    continue
                }
                val jsonText = extractJson(msg)
                Log.i(TAG, "  jsonText.len=${jsonText.length}, head=${jsonText.take(80)}")
                val videos = parseVideos(jsonText)
                Log.i(TAG, "  PARSE result: ${videos.size} items")
                results.addAll(videos)
            }
            Log.i(TAG, "TOTAL PARSED ${results.size} video items")
            results
        } finally {
            runCatching { client.close() }
        }
    }

    /** 从 MimeMessage 提取 JSON 内容: 附件优先 → 正文 fallback。
     *  关键: 有些情况下 msg.content 返回的是 MIME raw 文本而非 Multipart 对象,
     *  所以先尝试 msg.content, 如果不是 Multipart 就强制用 ByteArrayDataSource 构造 MimeMultipart。
     */
    private fun extractJson(msg: MimeMessage): String {
        val ct = runCatching { msg.contentType }.getOrNull().orEmpty()
        val content = runCatching { msg.content }.getOrNull()

        val mp: Multipart? = when {
            content is Multipart -> content
            ct.lowercase().startsWith("multipart/") -> runCatching {
                val ds = ByteArrayDataSource(msg.inputStream, ct)
                javax.mail.internet.MimeMultipart(ds)
            }.getOrNull()
            else -> null
        }
        if (mp != null) {
            return extractJsonFromMultipart(mp)
        }
        // 单 part 邮件
        return when (content) {
            is String -> content
            is InputStream -> runCatching { content.bufferedReader().use { it.readText() } }.getOrDefault("")
            else -> runCatching {
                val ds = ByteArrayDataSource(msg.inputStream, ct.ifBlank { "text/plain" })
                javax.mail.internet.MimeMultipart(ds).let { extractJsonFromMultipart(it) }
            }.getOrDefault("")
        }
    }

    private fun extractJsonFromMultipart(mp: Multipart): String {
        var fallbackBody = ""
        var htmlBody = ""
        for (i in 0 until mp.count) {
            val part = mp.getBodyPart(i)
            val ct = part.contentType.orEmpty().lowercase()
            val fileName = runCatching { part.fileName }.getOrNull().orEmpty()
            // 附件: application/json 或 .json 文件名
            val isJsonAttachment = (fileName.isNotBlank() && fileName.endsWith(".json", ignoreCase = true))
                || ct.startsWith("application/json")
            if (isJsonAttachment) {
                val text = readPartText(part)
                Log.i("MailFetcher", "  ATTACHMENT hit: name=[$fileName] ct=[$ct], len=${text.length}")
                if (text.isNotBlank()) return text
            }
            // 递归嵌套 multipart
            if (ct.startsWith("multipart/")) {
                val sub = runCatching { part.content }.getOrNull() as? Multipart
                if (sub != null) {
                    val json = extractJsonFromMultipart(sub)
                    if (json.isNotBlank()) return json
                }
            }
            // 收集 text/plain 作为 fallback, text/html 兜底
            if (ct.startsWith("text/plain") && fallbackBody.isBlank()) {
                fallbackBody = readPartText(part)
            } else if (ct.startsWith("text/html") && htmlBody.isBlank()) {
                htmlBody = readPartText(part)
            }
        }
        Log.i("MailFetcher", "  no attachment, fallback to text/plain len=${fallbackBody.length}, html len=${htmlBody.length}")
        return fallbackBody.ifBlank { stripHtml(htmlBody) }
    }

    /** 剥离 HTML 标签与常见实体, 还原纯文本 (QQ邮箱/手机端常把正文转成 text/html)。 */
    private fun stripHtml(text: String): String {
        if (!text.contains('<') && !text.contains('&')) return text
        return text
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|tr|li)>"), "\n")
            .replace(Regex("(?is)<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("\u00A0", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    private fun readPartText(part: javax.mail.BodyPart): String {
        val c = runCatching { part.content }.getOrNull()
        return when (c) {
            is String -> c
            is InputStream -> runCatching { c.bufferedReader().use { it.readText() } }.getOrDefault("")
            else -> ""
        }
    }

    /** 从 JSON 文本解析 VideoItem 列表。
     *  优先级: 分隔符 ********** 之前的内容 → 直接以 [ 或 { 开头的 JSON → 正则 fallback。
     */
    private fun parseVideos(rawText: String): List<VideoItem> {
        // QQ邮箱/手机客户端可能把正文转成 text/html, 先剥掉 HTML 标签
        val text = stripHtml(rawText)
        if (text.isBlank()) {
            Log.w("MailFetcher", "json 文本为空")
            return emptyList()
        }
        // 分隔符优先
        val sep = "**********"
        val candidate = when {
            text.contains(sep) -> {
                val before = text.substringBefore(sep).trim()
                Log.i("MailFetcher", "  发现分隔符 **********, 取前面 ${before.length} chars")
                before
            }
            else -> text.trim()
        }
        val raw = when {
            candidate.startsWith("[") && candidate.endsWith("]") -> candidate
            candidate.startsWith("{") && candidate.endsWith("}") -> candidate
            else -> {
                jsonArrayRegex.find(candidate)?.value ?: jsonBlockRegex.find(candidate)?.value
            }
        } ?: run {
            Log.w("MailFetcher", "  未找到 JSON 块, head=[${candidate.take(120)}]")
            return emptyList()
        }
        val element = runCatching { json.parseToJsonElement(raw) }.getOrElse {
            Log.w("MailFetcher", "  JSON parse FAILED: ${it.message}")
            return emptyList()
        }
        val objs: List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> return emptyList()
        }
        return objs.mapIndexedNotNull { idx, obj ->
            val t = obj["title"]?.jsonPrimitive?.contentOrNull
            val u = obj["url"]?.jsonPrimitive?.contentOrNull
            Log.i("MailFetcher", "  obj[$idx] title=[$t] url=[$u]")
            parseItem(obj)
        }
    }

    private fun parseItem(obj: JsonObject): VideoItem? {
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
        if (title.isNullOrBlank() || url.isNullOrBlank()) return null
        return VideoItem(
            id = 0,
            title = title,
            episode = obj["episode"]?.jsonPrimitive?.intOrNull ?: 0,
            year = obj["year"]?.jsonPrimitive?.intOrNull,
            country = obj["country"]?.jsonPrimitive?.contentOrNull,
            type = obj["type"]?.jsonPrimitive?.contentOrNull,
            director = obj["director"]?.jsonPrimitive?.contentOrNull,
            actors = obj["actors"]?.jsonPrimitive?.contentOrNull,
            url = url,
            headers = (obj["headers"] as? JsonObject)
                ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
                ?.filterValues { it.isNotBlank() }
                ?: emptyMap(),
            addedAt = 0
        )
    }
}

/** 单连接 IMAP over SSL 客户端。 */
private class ImapClient(private val host: String, private val port: Int) {

    private var seq = 0
    private lateinit var sock: SSLSocket
    private lateinit var writer: OutputStreamWriter

    fun connect() {
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(null, null, null)
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), 20000)
        sock = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        sock.startHandshake()
        writer = OutputStreamWriter(sock.outputStream, "UTF-8")
        val greeting = readLineRaw()
        Log.i("ImapClient", "greeting: $greeting")
        if (!greeting.startsWith("* OK")) error("IMAP connect 失败: $greeting")
    }

    fun login(user: String, pass: String) {
        val tag = nextTag()
        writer.write("$tag LOGIN \"$user\" \"$pass\"\r\n")
        writer.flush()
        val resp = readUntilTag(tag)
        Log.i("ImapClient", "LOGIN: last=${resp.lastOrNull()}")
        if (!resp.last().startsWith("$tag OK")) error("LOGIN 失败: ${resp.last()}")
    }

    fun id() {
        val tag = nextTag()
        writer.write("$tag ID (\"name\" \"mailvod\" \"version\" \"1.0.0\")\r\n")
        writer.flush()
        val resp = readUntilTag(tag)
        Log.i("ImapClient", "ID: last=${resp.lastOrNull()}")
        if (!resp.last().startsWith("$tag OK")) error("ID 失败: ${resp.last()}")
    }

    fun select(folder: String) {
        val tag = nextTag()
        writer.write("$tag SELECT $folder\r\n")
        writer.flush()
        val resp = readUntilTag(tag)
        Log.i("ImapClient", "SELECT: last=${resp.lastOrNull()}")
        if (!resp.last().startsWith("$tag OK")) error("SELECT 失败: ${resp.last()}")
    }

    fun searchAll(): List<Int> {
        val tag = nextTag()
        writer.write("$tag SEARCH ALL\r\n")
        writer.flush()
        val resp = readUntilTag(tag)
        val data = resp.firstOrNull { it.startsWith("* SEARCH") }?.removePrefix("* SEARCH")?.trim()
            ?: return emptyList()
        return data.split(" ").filter { it.isNotEmpty() }.map { it.toInt() }
    }

    /** FETCH RFC822: 服务器返回完整原始邮件字节。 */
    fun fetchRfc822(seq: Int): ByteArray? {
        val tag = nextTag()
        writer.write("$tag FETCH $seq RFC822\r\n")
        writer.flush()
        sock.soTimeout = 30_000
        val allBytes = try {
            readUntilTagRaw(tag)
        } catch (_: java.net.SocketTimeoutException) {
            Log.e("ImapClient", "FETCH#$seq RFC822 socket timeout!")
            null
        } finally {
            sock.soTimeout = 0
        }
        if (allBytes == null) return null
        val all = String(allBytes, charset("UTF-8"))
        val sizeMatch = Regex("""RFC822 \{(\d+)\}""").find(all)
        if (sizeMatch == null) {
            Log.w("ImapClient", "FETCH#$seq RFC822 size literal not found, head=${all.take(200)}")
            return null
        }
        val size = sizeMatch.groupValues[1].toInt()
        val bodyStart = sizeMatch.range.last + 3
        if (bodyStart + size > allBytes.size) {
            Log.w("ImapClient", "FETCH#$seq truncated! expect=$size avail=${allBytes.size - bodyStart}")
            return null
        }
        val bytes = allBytes.copyOfRange(bodyStart, bodyStart + size)
        Log.i("ImapClient", "FETCH#$seq RFC822 ok, ${bytes.size} bytes")
        return bytes
    }

    fun close() { runCatching { sock.close() } }

    private fun nextTag(): String = "A%03d".format(++seq)

    private fun readUntilTag(tag: String): List<String> {
        val result = mutableListOf<String>()
        val lastLine = StringBuilder()
        val stream = sock.inputStream
        while (true) {
            val b = stream.read()
            if (b == -1) break
            val ch = b.toChar()
            if (ch == '\r') continue
            if (ch == '\n') {
                val line = lastLine.toString()
                result.add(line)
                Log.d("ImapClient", "<< $line")
                if (line.startsWith("$tag OK") || line.startsWith("$tag NO") || line.startsWith("$tag BAD")) break
                lastLine.clear()
            } else {
                lastLine.append(ch)
            }
        }
        return result
    }

    private fun readUntilTagRaw(tag: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val lastLine = StringBuilder()
        val stream = sock.inputStream
        while (true) {
            val b = stream.read()
            if (b == -1) break
            out.write(b)
            val ch = b.toChar()
            if (ch == '\r') continue
            if (ch == '\n') {
                val line = lastLine.toString()
                Log.d("ImapClient", "<< $line")
                if (line.startsWith("$tag OK") || line.startsWith("$tag NO") || line.startsWith("$tag BAD")) break
                lastLine.clear()
            } else {
                lastLine.append(ch)
            }
        }
        return out.toByteArray()
    }

    private fun readLineRaw(): String {
        val sb = StringBuilder()
        val stream = sock.inputStream
        while (true) {
            val b = stream.read()
            if (b == -1) break
            val ch = b.toChar()
            if (ch == '\n') break
            if (ch == '\r') continue
            sb.append(ch)
        }
        return sb.toString()
    }
}
