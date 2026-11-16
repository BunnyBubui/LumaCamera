package th.ogay.luma

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A small MCP (Model Context Protocol) client over Streamable HTTP: JSON-RPC posted to one address, answered either as JSON or as
 * a stream of server-sent events. The app lists each server's tools and hands them to an API model during automatic editing, so
 * the model can, for example, search a music service and give back a link to a track. Servers that run as a program on a
 * computer (stdio) cannot run on a phone; only servers reachable over https work here.
 */
object Mcp {
    class Server(val name: String, val url: String, val token: String)

    fun servers(c: Context): List<Server> = runCatching {
        val a = JSONArray(c.getSharedPreferences("camera", 0).getString("mcp_servers", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Server(it.optString("name"), it.optString("url"), it.optString("token")) } }
    }.getOrDefault(emptyList())
    fun save(c: Context, list: List<Server>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token)) }
        c.getSharedPreferences("camera", 0).edit().putString("mcp_servers", a.toString()).apply()
    }

    /** One tool. [callName] is unique across servers and safe for every API's tool-name rules. */
    class Tool(val session: Session, val name: String, val description: String, val schema: JSONObject, val callName: String)

    /** The JSON-RPC answer with [id] from a reply body: plain JSON, or the "data:" lines of an event stream. Null when absent. */
    fun pick(body: String, eventStream: Boolean, id: Int): String? {
        if (!eventStream) return body.trim().ifEmpty { null }
        val events = ArrayList<String>(); val cur = StringBuilder()
        for (line in body.lineSequence()) {
            when {
                line.startsWith("data:") -> { if (cur.isNotEmpty()) cur.append('\n'); cur.append(line.removePrefix("data:").trimStart()) }
                line.isBlank() -> if (cur.isNotEmpty()) { events += cur.toString(); cur.clear() }
            }
        }
        if (cur.isNotEmpty()) events += cur.toString()
        return events.firstOrNull { Regex("\"id\"\\s*:\\s*\"?$id\"?[,}\\s]").containsMatchIn(it) } ?: events.lastOrNull()
    }

    /** A tool name an API accepts: letters, digits, _ and -, at most 64 characters, with the server's number in front. */
    fun safeName(server: Int, tool: String): String = ("s${server}_" + tool.replace(Regex("[^A-Za-z0-9_-]"), "_")).take(64)

    class Session(val server: Server) {
        private var sid: String? = null
        private var next = 1
        private var ready = false

        private fun post(body: JSONObject, expect: Int?): String? {
            val h = (URL(server.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                if (ready) setRequestProperty("MCP-Protocol-Version", "2025-06-18")
                sid?.let { setRequestProperty("Mcp-Session-Id", it) }
                if (server.token.isNotBlank()) setRequestProperty("Authorization", "Bearer " + server.token.trim())
            }
            try {
                h.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = h.responseCode
                h.getHeaderField("Mcp-Session-Id")?.let { sid = it }
                if (expect == null) return null
                val text = (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 401 || code == 403) error("${server.name}: ไม่มีสิทธิ์ (HTTP $code) ตรวจ token")
                if (code !in 200..299) error("${server.name}: HTTP $code " + text.take(120))
                return pick(text, h.contentType.orEmpty().contains("event-stream"), expect)
            } finally { h.disconnect() }
        }

        private fun rpc(method: String, params: JSONObject): JSONObject {
            val id = next++
            val reply = post(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params), id) ?: error("${server.name}: ไม่มีคำตอบ")
            val j = JSONObject(reply)
            j.optJSONObject("error")?.let { error("${server.name}: " + it.optString("message", "error")) }
            return j.optJSONObject("result") ?: JSONObject()
        }

        fun open() {
            if (ready) return
            rpc("initialize", JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "Lumora").put("version", "1")))
            ready = true
            runCatching { post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), null) }
        }

        fun tools(index: Int): List<Tool> {
            open()
            val out = ArrayList<Tool>(); var cursor: String? = null
            do {
                val r = rpc("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } })
                val a = r.optJSONArray("tools") ?: JSONArray()
                for (i in 0 until a.length()) { val t = a.getJSONObject(i)
                    out += Tool(this, t.getString("name"), t.optString("description").take(600), t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"), safeName(index, t.getString("name"))) }
                cursor = r.optString("nextCursor").takeIf { it.isNotEmpty() }
            } while (cursor != null && out.size < 64)
            return out
        }

        /** Calls tool [name]; the text parts of the result, at most 4000 characters. */
        fun call(name: String, args: JSONObject): String {
            val r = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
            val parts = r.optJSONArray("content") ?: JSONArray()
            val text = (0 until parts.length()).joinToString("\n") { i -> val p = parts.getJSONObject(i)
                when (p.optString("type")) { "text" -> p.optString("text"); "resource" -> p.optJSONObject("resource")?.optString("uri").orEmpty(); else -> "[${p.optString("type")}]" } }
                .ifEmpty { r.optJSONObject("structuredContent")?.toString().orEmpty() }
            return (if (r.optBoolean("isError")) "ERROR: " else "") + text.take(4_000)
        }
    }

    /** Every tool of every saved server; servers that fail are skipped and named in [errors]. */
    fun allTools(c: Context, errors: MutableList<String>): List<Tool> = servers(c).flatMapIndexed { i, s ->
        runCatching { Session(s).tools(i) }.getOrElse { errors += (it.message ?: s.name); emptyList() }
    }
}
