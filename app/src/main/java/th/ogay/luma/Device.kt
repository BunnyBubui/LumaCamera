package th.ogay.luma

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * What this phone can carry, decided in one place. The app was tuned on one phone; everything heavy (language models, speech
 * models, big preview pictures, how finely a video is analysed) asks here instead of assuming that phone, so a weaker phone
 * gets lighter defaults and a clear message instead of a crash, and a stronger one is not held back.
 */
object Device {
    enum class Tier { LOW, MID, HIGH }

    /** Phones sold as "4 GB" report about 3.7 GB, "6 GB" about 5.6 GB, "8 GB" about 7.5 GB. */
    fun tierOf(totalMb: Long, cores: Int, lowRam: Boolean): Tier = when {
        lowRam || totalMb < 4_500 || cores <= 4 -> Tier.LOW
        totalMb < 7_000 -> Tier.MID
        else -> Tier.HIGH
    }

    /**
     * True when every core listed in /proc/cpuinfo text [cpuinfo] has the dot-product instructions ("asimddp") the language-model
     * library is built with. Only 64-bit ARM needs them; when the text says nothing about features the answer is yes.
     */
    fun hasDotProd(cpuinfo: String, abi: String): Boolean {
        if (abi != "arm64-v8a") return true
        val lines = cpuinfo.lineSequence().filter { it.startsWith("Features") }.toList()
        return lines.isEmpty() || lines.all { l -> l.substringAfter(':').split(' ').any { it == "asimddp" } }
    }

    /**
     * Threads for heavy maths from each core's top speed [maxFreqs] (kHz): the fast cores only, since a slow core in the group
     * holds the fast ones back. Between 2 and 4; with no speeds known, up to 4 of [cores].
     */
    fun fastThreads(maxFreqs: List<Long>, cores: Int): Int {
        val f = maxFreqs.filter { it > 0 }
        if (f.size < 2 || f.min() == f.max()) return cores.coerceIn(2, 4)
        return f.count { it > f.min() }.coerceIn(2, 4)
    }

    /** True when a model of [modelMb] fits a phone with [totalMb] of memory (it may take at most 60 % of it). */
    fun fits(modelMb: Int, totalMb: Long) = modelMb.toLong() * 10 <= totalMb * 6

    @Volatile private var cachedTier: Tier? = null
    fun totalMb(c: Context): Long {
        val mi = ActivityManager.MemoryInfo(); (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1_048_576L
    }
    fun tier(c: Context): Tier = cachedTier ?: tierOf(totalMb(c), Runtime.getRuntime().availableProcessors(),
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice).also { cachedTier = it }
    fun low(c: Context) = tier(c) == Tier.LOW

    val llmSupported: Boolean by lazy { hasDotProd(runCatching { File("/proc/cpuinfo").readText() }.getOrDefault(""), Build.SUPPORTED_ABIS.firstOrNull().orEmpty()) }

    val threads: Int by lazy {
        val n = Runtime.getRuntime().availableProcessors()
        fastThreads((0 until n).map { i -> runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L) }, n)
    }

    /** Why this phone cannot run model [m] (short Thai sentence), or null when it can. [llm] = it runs on the language-model library. */
    fun cannotRun(c: Context, m: ModelSpec, llm: Boolean): String? = when {
        llm && !llmSupported -> "ชิปของเครื่องนี้ไม่มีชุดคำสั่งที่โมเดลภาษาต้องใช้ จึงรันโมเดลนี้ไม่ได้"
        !fits(m.approxMb, totalMb(c)) -> "เครื่องนี้มี RAM ไม่พอสำหรับ ${m.name} ลองโมเดลที่เล็กกว่า"
        else -> null
    }

    /** One line for reports and bug notes. */
    fun summary(c: Context) = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} RAM ${totalMb(c)} MB ${Runtime.getRuntime().availableProcessors()} cores tier ${tier(c)} threads $threads llm ${if (llmSupported) "ok" else "no"}"
}
