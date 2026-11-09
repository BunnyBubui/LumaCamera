package th.ogay.luma

/** IRIDAS .cube 3D LUT, red-fastest input order, normalized domains only. */
object CubeLut {
    fun read(reader:java.io.Reader):Array<Array<IntArray>>{
        val text=StringBuilder();val buffer=CharArray(8192)
        while(true){val n=reader.read(buffer);if(n<0)break;require(text.length+n<=3_000_000){"LUT ใหญ่เกิน 3 MB"};text.append(buffer,0,n)}
        return parse(text.toString())
    }
    fun parse(text:String):Array<Array<IntArray>>{
        require(text.length<=3_000_000){"LUT ใหญ่เกิน 3 MB"}
        var size=0;val rows=ArrayList<FloatArray>()
        text.lineSequence().forEach{raw->val line=raw.substringBefore('#').trim();if(line.isEmpty())return@forEach
            val p=line.split(Regex("\\s+"))
            when(p[0]){
                "TITLE"->Unit
                "LUT_3D_SIZE"->{require(size==0);size=p.getOrNull(1)?.toIntOrNull()?:0;require(size in 2..33){"รองรับ LUT 3D ขนาด 2–33 เท่านั้น"}}
                "LUT_1D_SIZE"->error("ยังไม่รองรับ LUT 1D")
                "DOMAIN_MIN"->{require(p.drop(1).map{it.toFloat()}==listOf(0f,0f,0f)){"รองรับ DOMAIN_MIN 0 0 0"}}
                "DOMAIN_MAX"->{require(p.drop(1).map{it.toFloat()}==listOf(1f,1f,1f)){"รองรับ DOMAIN_MAX 1 1 1"}}
                else->{require(size>0&&p.size==3){"รูปแบบ .cube ไม่ถูกต้อง"};val v=p.map{it.toFloat()};require(v.all{it.isFinite()});rows+=v.toFloatArray();require(rows.size<=33*33*33)}
            }
        }
        require(size>0&&rows.size==size*size*size){"จำนวนค่า LUT ไม่ครบ"}
        return Array(size){r->Array(size){g->IntArray(size){b->val v=rows[(b*size+g)*size+r];0xff000000.toInt() or((v[0].coerceIn(0f,1f)*255+.5f).toInt() shl 16) or((v[1].coerceIn(0f,1f)*255+.5f).toInt() shl 8) or(v[2].coerceIn(0f,1f)*255+.5f).toInt()}}}
    }
}
