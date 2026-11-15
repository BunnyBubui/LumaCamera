package th.ogay.luma.ai

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.File
import org.json.JSONObject

/** Created, run and released on the worker's single thread, including GPU fallback. */
class DetailRuntime(private val context: Context, private val forceCpu: Boolean = false) : Closeable {
    private var model: CompiledModel? = null
    private var inputs: List<TensorBuffer> = emptyList()
    private var outputs: List<TensorBuffer> = emptyList()
    private var gpu = false
    var backend = ""
        private set
    var fallbackReason: String? = null
        private set
    var modelSha256 = ""
        private set
    private val rgb = FloatArray(EnhanceSpec.INPUT * EnhanceSpec.INPUT * 3)

    init {
        if (!forceCpu) {
            try { open(true) } catch (e: Exception) { fallbackReason = "${e.javaClass.simpleName}: ${e.message?.take(240)}"; close() }
            catch (e: LinkageError) { fallbackReason = e.javaClass.simpleName; close() }
        }
        if (model == null) open(false)
    }
    private fun open(useGpu: Boolean) {
        modelSha256 = context.assets.open(EnhanceSpec.MODEL_MANIFEST).bufferedReader().use {
            JSONObject(it.readText()).getString("sha256").also { hash ->
                check(hash.matches(Regex("[0-9a-f]{64}"))) { "ข้อมูลตรวจสอบโมเดลไม่ถูกต้อง" }
            }
        }
        val fingerprint = modelSha256.take(20)
        val options = CompiledModel.Options(if (useGpu) Accelerator.GPU else Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = minOf(4, Runtime.getRuntime().availableProcessors()))
            if (useGpu) gpuOptions = CompiledModel.GpuOptions(
                precision = CompiledModel.GpuOptions.Precision.FP16,
                serializationDir = File(context.codeCacheDir, "detail-sr2").apply { mkdirs() }.path,
                modelCacheKey = "detail-$fingerprint-${EnhanceSpec.INPUT}",
                serializeProgramCache = true)
        }
        model = CompiledModel.create(context.assets, EnhanceSpec.MODEL_ASSET, options)
        try {
            inputs = model!!.createInputBuffers()
            outputs = model!!.createOutputBuffers()
            check(inputs.size == 1 && outputs.size == 1) { "รูปแบบโมเดล AI ไม่ถูกต้อง" }
            gpu = useGpu
            backend = if (useGpu) "GPU FP16" else "CPU"
        } catch (e: Exception) { close(); throw e }
    }
    /** RGB, normalized 0..1. Output remains float until the final tile is quantized. */
    fun run(pixels: IntArray): FloatArray {
        require(pixels.size * 3 == rgb.size)
        for (i in pixels.indices) {
            val c = pixels[i]; val j = i * 3
            rgb[j] = ((c ushr 16) and 255) / 255f
            rgb[j + 1] = ((c ushr 8) and 255) / 255f
            rgb[j + 2] = (c and 255) / 255f
        }
        fun invoke(): FloatArray {
            inputs.single().writeFloat(rgb)
            model!!.run(inputs, outputs)
            return outputs.single().readFloat().also {
                check(it.size == EnhanceSpec.INPUT * EnhanceSpec.INPUT * 16 * 3) { "ขนาดผลลัพธ์โมเดลไม่ถูกต้อง" }
            }
        }
        return try { invoke() } catch (e: Exception) {
            if (!gpu) throw e
            fallbackReason = "${e.javaClass.simpleName}: ${e.message?.take(240)}"
            close(); open(false); invoke()
        }
    }
    override fun close() {
        outputs.forEach { it.close() }; outputs = emptyList()
        inputs.forEach { it.close() }; inputs = emptyList()
        model?.close(); model = null
    }
}
