package th.ogay.luma

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Who reads a typed instruction for automatic editing: a language model on the phone (run by the same llama.cpp engine as
 * the caption translator, held to a grammar so it can only answer with a brief) or a model behind an API the user has a key for.
 * Either way only text goes out: the instruction, the shot list and any caption words. The video itself never leaves the phone.
 */
object AutoBrain {
    private const val Q17 = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/"
    private const val Q4 = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/"
    val MODELS = listOf(
        ModelSpec("qwen3-1.7b", "planner", "Qwen3 1.7B", "เร็ว", "Alibaba · ~1.1 GB · อ่านคำสั่งไทยได้", "", 1110,
            listOf("Qwen3-1.7B-Q4_K_M.gguf" to Q17 + "Qwen3-1.7B-Q4_K_M.gguf")),
        ModelSpec("qwen3-4b", "planner", "Qwen3 4B Instruct", "เข้าใจดีกว่า", "Alibaba · ~2.5 GB · ช้ากว่า ใช้ RAM มากกว่า", "", 2500,
            listOf("Qwen3-4B-Instruct-2507-Q4_K_M.gguf" to Q4 + "Qwen3-4B-Instruct-2507-Q4_K_M.gguf")),
    )
    fun selected(c: Context) = ModelStore.selected(c, "planner", MODELS)

    /** One API service. [anthropic] uses Anthropic's own message format; the rest speak the OpenAI chat format. */
    class Provider(val id: String, val name: String, val base: String, val model: String, val anthropic: Boolean = false)
    val PROVIDERS = listOf(
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
        Provider("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", "claude-haiku-5-5", anthropic = true),
        Provider("custom", "กำหนดเอง (OpenAI-compatible)", "", ""),
    )
    private fun prefs(c: Context) = c.getSharedPreferences("camera", 0)
    fun provider(c: Context) = PROVIDERS.getOrElse(prefs(c).getInt("auto_api_provider", 0)) { PROVIDERS[0] }
    fun key(c: Context, p: Provider = provider(c)) = prefs(c).getString("auto_api_key_" + p.id, "").orEmpty()
    fun model(c: Context, p: Provider = provider(c)) = prefs(c).getString("auto_api_model_" + p.id, null)?.takeIf { it.isNotBlank() } ?: p.model
    fun base(c: Context, p: Provider = provider(c)) = (if (p.id == "custom") prefs(c).getString("auto_api_base", "").orEmpty() else p.base).trimEnd('/')
    fun setKey(c: Context, v: String) { prefs(c).edit().putString("auto_api_key_" + provider(c).id, v.trim()).apply() }
    fun setModel(c: Context, v: String) { prefs(c).edit().putString("auto_api_model_" + provider(c).id, v.trim()).apply() }
    fun setBase(c: Context, v: String) { prefs(c).edit().putString("auto_api_base", v.trim()).apply() }

    @Volatile private var engine: LlamaEngine? = null
    @Volatile private var conn: HttpURLConnection? = null
    /** Stops a model that is thinking, or a request that is waiting. */
    fun stop() { engine?.stop(); runCatching { conn?.disconnect() } }

    /** Asks the phone's model. Runs on the calling thread; the model is loaded for this one answer and freed after it. */
    fun local(c: Context, m: ModelSpec, prompt: String): Result<String> = runCatching {
        if (!ModelStore.installed(c, m)) error("ยังไม่ได้ดาวน์โหลด ${m.name}")
        LlamaTranslator.tooBig(c, m)?.let { error(it) }
        LlamaTranslator.release() // one model in memory at a time
        val file = File(ModelStore.dir(c, m), m.files[0].first)
        val e = LlamaEngine.load(file, Runtime.getRuntime().availableProcessors().coerceIn(2, 4), 4096) ?: error("เปิดโมเดลไม่ได้ ไฟล์อาจเสียหาย ลองดาวน์โหลดใหม่")
        engine = e
        try {
            val thinking = m.id == "qwen3-1.7b"
            var wrapped = e.wrap(prompt).ifEmpty { "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n" }
            // Qwen3 1.7B thinks aloud unless handed an empty thought; the grammar would block the thinking anyway.
            if (thinking && !wrapped.contains("<think>")) wrapped += "<think>\n\n</think>\n\n"
            e.generate(wrapped, 420, AutoEdit.grammar()).ifEmpty { error("โมเดลไม่ได้ตอบ (ข้อมูลอาจยาวเกินความจำของโมเดล)") }
        } finally { engine = null; e.close() }
    }

    /** Asks the API model. Runs on the calling thread. */
    fun api(c: Context, prompt: String): Result<String> = runCatching {
        val p = provider(c); val key = key(c, p); val base = base(c, p); val model = model(c, p)
        if (key.isEmpty()) error("ยังไม่ได้ใส่ API key ของ ${p.name}")
        if (base.isEmpty() || model.isEmpty()) error("ยังไม่ได้ใส่ Base URL หรือชื่อโมเดล")
        val body: JSONObject; val url: String
        if (p.anthropic) {
            url = "$base/messages"
            body = JSONObject().put("model", model).put("max_tokens", 800).put("temperature", 0.2)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        } else {
            url = "$base/chat/completions"
            body = JSONObject().put("model", model).put("temperature", 0.2)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }
        val h = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (p.anthropic) { setRequestProperty("x-api-key", key); setRequestProperty("anthropic-version", "2023-06-01") }
            else setRequestProperty("Authorization", "Bearer $key")
        }
        conn = h
        try {
            h.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = h.responseCode
            val text = (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(when (code) {
                401, 403 -> "API key ไม่ถูกต้องหรือไม่มีสิทธิ์ (HTTP $code)"
                404 -> "ไม่พบโมเดล \"$model\" หรือที่อยู่ API ผิด (HTTP 404)"
                429 -> "เรียก API ถี่เกินหรือเครดิตหมด (HTTP 429)"
                else -> "API ตอบผิดพลาด (HTTP $code) " + text.take(160)
            })
            val j = JSONObject(text)
            if (p.anthropic) j.getJSONArray("content").getJSONObject(0).getString("text")
            else j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally { conn = null; h.disconnect() }
    }
}
