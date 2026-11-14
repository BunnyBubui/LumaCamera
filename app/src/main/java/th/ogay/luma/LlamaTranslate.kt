package th.ogay.luma

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** The llama.cpp model that is loaded now (one at a time). All calls must come from one worker thread. */
class LlamaEngine private constructor(private var handle: Long) {
    private external fun nativeFree(h: Long)
    private external fun nativeTemplate(h: Long, text: String): String
    private external fun nativeGenerate(h: Long, prompt: String, maxNew: Int, repeat: Float): String
    private external fun nativeStop()

    /** The model's own chat template wrapped around [text]; "" if the model has none. */
    fun wrap(text: String) = nativeTemplate(handle, text)
    fun generate(prompt: String, maxNew: Int, repeat: Float = 1.05f) = nativeGenerate(handle, prompt, maxNew, repeat)
    fun stop() = nativeStop()
    fun close() { if (handle != 0L) nativeFree(handle); handle = 0L }

    companion object {
        private var loaded = false
        @JvmStatic private external fun nativeLoad(path: String, threads: Int, nCtx: Int): Long
        fun load(path: File, threads: Int, nCtx: Int): LlamaEngine? {
            if (!loaded) { System.loadLibrary("luma_llm"); loaded = true }
            val h = nativeLoad(path.absolutePath, threads, nCtx)
            return if (h == 0L) null else LlamaEngine(h)
        }
    }
}

/** English name and code for each language the editor offers, in the form the translation prompts use. */
object TransLang {
    private val names = mapOf("th" to "Thai", "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "yue" to "Cantonese",
        "vi" to "Vietnamese", "id" to "Indonesian", "ms" to "Malay", "fil" to "Filipino", "hi" to "Hindi", "es" to "Spanish", "fr" to "French",
        "de" to "German", "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "ar" to "Arabic")
    fun code(tag: String) = tag.substringBefore('-')
    fun name(tag: String) = names[code(tag)] ?: tag
}

/** How a model wants to be asked. */
enum class TransFamily { HUNYUAN, CHINDA, TYPHOON, GEMMA }

/** What each translator can do: which family of prompt it uses, and whether it only knows Thai and English. */
class TransInfo(val family: TransFamily?, val thEnOnly: Boolean = false) {
    fun supports(from: String, to: String): Boolean {
        val f = TransLang.code(from); val t = TransLang.code(to)
        if (f == t) return false
        return if (thEnOnly) setOf(f, t) == setOf("th", "en") else true
    }
}

object TranslatorModels {
    private const val HY = "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/"
    private const val WAIT = "รอตัวรันของ Tencent (ตัวรันทั่วไปยังอ่านไฟล์นี้ไม่ได้)"
    val ALL = listOf(
        ModelSpec("mlkit", "translate", "ML Kit (Google)", "เล็กสุด", "แปลได้หลายภาษา · เร็ว · คุณภาพพอใช้", "", 30, emptyList(), builtin = true),
        ModelSpec("hymt-125", "translate", "Hy-MT 1.8B · 1.25-bit", "ประหยัดสุด", "Tencent · ~440 MB · 33 ภาษา", "", 440, emptyList(), unavailable = WAIT),
        ModelSpec("hymt-2bit", "translate", "Hy-MT 1.8B · 2-bit", "ประหยัด", "Tencent · ~574 MB · 33 ภาษา", "", 574, emptyList(), unavailable = WAIT),
        ModelSpec("hymt-q4", "translate", "Hy-MT 1.8B · Q4", "สมดุล", "Tencent · แปลแม่น 33 ภาษา รวมไทย", "", 1130, listOf("HY-MT1.5-1.8B-Q4_K_M.gguf" to HY + "HY-MT1.5-1.8B-Q4_K_M.gguf")),
        ModelSpec("chinda", "translate", "ChindaMT 0.8B", "ไทย↔อังกฤษ", "เล็ก เร็ว · เฉพาะไทยกับอังกฤษ", "", 672, listOf("ChindaMT-0.8B.i1-Q4_K_M.gguf" to "https://huggingface.co/mradermacher/ChindaMT-0.8B-i1-GGUF/resolve/main/ChindaMT-0.8B.i1-Q4_K_M.gguf")),
        ModelSpec("typhoon", "translate", "Typhoon Translate 1.5 4B", "ไทยแม่นสุด", "SCB 10X · ไทย↔อังกฤษ · ใหญ่ ช้ากว่า", "", 2500, listOf("typhoon-translate1.5-4b-q4_k_m.gguf" to "https://huggingface.co/scb10x/typhoon-translate1.5-4b-gguf/resolve/main/typhoon-translate1.5-4b-q4_k_m.gguf")),
        ModelSpec("tgemma", "translate", "TranslateGemma 4B", "55 ภาษา", "Google · หลายภาษา · ใหญ่ ช้ากว่า", "", 2490, listOf("translategemma-4b-it-Q4_K_M.gguf" to "https://huggingface.co/42ailab/TranslateGemma-4B-GGUF/resolve/main/translategemma-4b-it-Q4_K_M.gguf")),
    )
    private val info = mapOf("hymt-q4" to TransInfo(TransFamily.HUNYUAN), "chinda" to TransInfo(TransFamily.CHINDA, true),
        "typhoon" to TransInfo(TransFamily.TYPHOON, true), "tgemma" to TransInfo(TransFamily.GEMMA))
    fun infoOf(s: ModelSpec): TransInfo? = info[s.id]
    fun selected(c: Context) = ModelStore.selected(c, "translate", ALL)
}

/**
 * Translates caption lines on the phone with a GGUF model. The model loads once and stays for the next batch; each line is a
 * separate short request, so the progress bar moves line by line and a stop takes effect within a few tokens.
 */
object LlamaTranslator {
    private var engine: LlamaEngine? = null
    private var engineId: String? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; engine?.stop() }
    fun release() { engine?.close(); engine = null; engineId = null }

    /** A short Thai message when this phone cannot hold the model, else null. */
    fun tooBig(c: Context, m: ModelSpec): String? {
        val mi = ActivityManager.MemoryInfo(); (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return if (m.approxMb * 1_048_576L > mi.totalMem * 6 / 10) "เครื่องนี้มี RAM ไม่พอสำหรับโมเดลนี้ ลองโมเดลที่เล็กกว่า" else null
    }

    /** What the text is (for example a Chinese martial-arts film), so the model picks matching words and tone. Empty = none. */
    @Volatile var context: String = ""

    private fun prompt(f: TransFamily, from: String, to: String, text: String): String {
        val fn = TransLang.name(from); val tn = TransLang.name(to)
        val hint = context.trim().take(120).let { if (it.isEmpty()) "" else "Context: the text is dialogue from $it. Choose vocabulary, tone and names that suit it.\n" }
        return hint + when (f) {
            TransFamily.HUNYUAN -> "Translate the following segment into $tn, without additional explanation.\n\n$text"
            TransFamily.CHINDA -> "Translate $fn to $tn.\nRules:\n- Return only the translated text\n\n${TransLang.code(from).uppercase()}: $text"
            TransFamily.TYPHOON -> "Translate the following $fn text into $tn. Return only the translated text, without explanations.\nSource Text:\n\n$text"
            TransFamily.GEMMA -> "You are a professional $fn (${TransLang.code(from)}) to $tn (${TransLang.code(to)}) translator. Your goal is to accurately convey the meaning and nuances of the original $fn text while adhering to $tn grammar, vocabulary, and cultural sensitivities.\n" +
                "Produce only the $tn translation, without any additional explanations or commentary. Please translate the following $fn text into $tn:\n\n\n$text"
        }
    }

    private fun fallbackWrap(f: TransFamily, ask: String) = when (f) {
        TransFamily.CHINDA, TransFamily.TYPHOON -> "<|im_start|>user\n$ask<|im_end|>\n<|im_start|>assistant\n"
        TransFamily.GEMMA -> "<start_of_turn>user\n$ask<end_of_turn>\n<start_of_turn>model\n"
        TransFamily.HUNYUAN -> ""
    }

    private fun clean(raw: String): String {
        var s = raw
        val end = s.lastIndexOf("</think>"); if (end >= 0) s = s.substring(end + 8)
        return s.trim().trim('"', '“', '”').trim()
    }

    /**
     * Translates [lines]; [progress] is called after each one with (done, total, text or null when that line failed).
     * Runs on the calling thread. Returns an error message, or null when it finished (or was cancelled).
     */
    fun run(c: Context, m: ModelSpec, from: String, to: String, lines: List<String>, loaded: () -> Unit = {}, progress: (Int, Int, String?) -> Unit): String? {
        val info = TranslatorModels.infoOf(m) ?: return "โมเดลนี้ยังใช้ไม่ได้"
        val family = info.family ?: return "โมเดลนี้ยังใช้ไม่ได้"
        if (!info.supports(from, to)) return "โมเดลนี้แปลคู่ภาษานี้ไม่ได้ · เลือกโมเดลอื่น"
        tooBig(c, m)?.let { return it }
        cancelled = false
        val file = File(ModelStore.dir(c, m), m.files[0].first)
        if (!file.isFile) return "ยังไม่ได้ดาวน์โหลดโมเดล"
        try {
            if (engineId != m.id) {
                release()
                engine = LlamaEngine.load(file, Runtime.getRuntime().availableProcessors().coerceIn(2, 4), 1536) ?: return "เปิดโมเดลไม่ได้ ไฟล์อาจเสีย · ลองลบแล้วดาวน์โหลดใหม่"
                engineId = m.id
            }
            val e = engine ?: return "เปิดโมเดลไม่ได้"
            loaded()
            lines.forEachIndexed { i, text ->
                if (cancelled) return null
                val ask = prompt(family, from, to, text)
                // The model's own template first; if the file has none the library can read, the usual form for that family.
                var wrapped = e.wrap(ask).ifEmpty { fallbackWrap(family, ask) }
                if (wrapped.isEmpty()) return "ไฟล์โมเดลนี้ไม่มีรูปแบบแชทที่อ่านได้ ใช้แปลไม่ได้ · ลองโมเดลอื่น"
                if (family == TransFamily.CHINDA && !wrapped.contains("<think>")) wrapped += "<think>\n\n</think>\n\n"
                val out = clean(e.generate(wrapped, (text.length * 3).coerceIn(64, 512)))
                progress(i + 1, lines.size, out.takeIf { it.isNotBlank() })
            }
            return null
        } catch (t: UnsatisfiedLinkError) { return "ไลบรารีแปลภาษาไม่พร้อมในเครื่องนี้" }
        catch (t: OutOfMemoryError) { release(); return "หน่วยความจำไม่พอ ลองโมเดลที่เล็กกว่า" }
    }
}
