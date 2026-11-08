package th.ogay.luma

import java.io.File
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log10

class LearnedFilterTest {
    private val filter=File("src/main/assets/luma-sr1.bin").inputStream().use {LearnedFilter(it)}
    @Test fun flatColorsAreExact() {
        for (rgb in intArrayOf(0xff234862.toInt(),-1,0xff000000.toInt())) {
            val out=filter.expand(IntArray(9*11){rgb},9,11)
            assertEquals(5*2*7*2,out.size);assertTrue(out.all{it==rgb})
        }
    }
    @Test fun independentlyProcessedTilesHaveNoSeams() {
        val stride=13;val h=10
        val input=IntArray(stride*h){i-> 0xff000000.toInt() or (i*100347 and 0xffffff)}
        val full=filter.expand(input,stride,h);val split=3
        for ((x,w) in listOf(0 to split, split to stride-4-split)) {
            val tile=IntArray((w+4)*h){i->input[(i/(w+4))*stride+x+i%(w+4)]}
            val small=filter.expand(tile,w+4,h)
            for(r in 0 until (h-4)*2) for(c in 0 until w*2) assertEquals(full[r*(stride-4)*2+x*2+c],small[r*w*2+c])
        }
    }
    private fun quality(name:String) {
        fun resource(file:String)=javaClass.getResourceAsStream("/fixtures/$name-$file")!!
        fun image(suffix:String): Triple<Int,Int,ByteArray> = DataInputStream(GZIPInputStream(resource(suffix))).use {
            val width=it.readUnsignedShort();val height=it.readUnsignedShort()
            Triple(width,height,ByteArray(width*height).also(it::readFully))
        }
        val (hw,hh,high)=image("high.gray.gz")
        val (w,h,low)=image("low.gray.gz")
        val pad=IntArray((w+4)*(h+4)) { i ->
            val gray=low[(i/(w+4)-2).coerceIn(0,h-1)*w+(i%(w+4)-2).coerceIn(0,w-1)].toInt() and 255
            0xff000000.toInt() or (gray*0x010101)
        }
        val out=filter.expand(pad,w+4,h+4)
        var sq=0.0; var count=0
        for(y in 4 until hh-4) for(x in 4 until hw-4) {
            val diff=(out[y*hw+x] and 255)-(high[y*hw+x].toInt() and 255)
            sq+=diff*diff;count++
        }
        val baseline=resource("bicubic.txt").bufferedReader().use {it.readText().trim().toDouble()}
        val learned=sq/count
        println("$name: bicubic ${10*log10(65025/baseline)} dB / SR1 ${10*log10(65025/learned)} dB")
        assertTrue("$name has higher MSE than bicubic", learned<baseline)
    }
    @Test fun heldOutCameraBeatsBicubic()=quality("camera")
    @Test fun heldOutRocketBeatsBicubic()=quality("rocket")
}
