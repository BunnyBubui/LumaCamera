package th.ogay.luma

import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CaptureResult as CR
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.nio.ByteOrder
import kotlin.math.*

object RawBayer {
    data class Linear(val width:Int,val height:Int,val rgb:FloatArray)
    fun sample(image:Image,metadata:TotalCaptureResult,c:CC):Linear{
        val pattern=c.get(CC.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?:error("ไม่มีข้อมูล Bayer CFA")
        check(pattern in 0..3){"CFA แบบนี้ยังไม่รองรับการเรนเดอร์ สามารถใช้ DNG ต้นฉบับได้"}
        val black=c.get(CC.SENSOR_BLACK_LEVEL_PATTERN)?:error("ไม่พบ black level")
        val white=(c.get(CC.SENSOR_INFO_WHITE_LEVEL)?:65535).toFloat()
        val gains=metadata.get(CR.COLOR_CORRECTION_GAINS)
        val transform=metadata.get(CR.COLOR_CORRECTION_TRANSFORM)
        val plane=image.planes[0];val buf=plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val step=max(1,ceil(sqrt(image.width.toDouble()*image.height/4/1_200_000)).toInt())
        val width=image.width/(2*step);val height=image.height/(2*step);val rgb=FloatArray(width*height*3)
        val locations=when(pattern){0->intArrayOf(0,3);1->intArrayOf(1,2);2->intArrayOf(2,1);else->intArrayOf(3,0)}
        fun value(x:Int,y:Int):Float{val raw=buf.getShort(y*plane.rowStride+x*plane.pixelStride).toInt()and 65535;val b=black.getOffsetForIndex(x%2,y%2);return((raw-b)/(white-b)).coerceAtLeast(0f)}
        val greens=(0..3).filter{it!=locations[0]&&it!=locations[1]}
        val gr=gains?.red?:1f;val gg0=gains?.greenEven?:1f;val gg1=gains?.greenOdd?:1f;val gb=gains?.blue?:1f
        val matrix=FloatArray(9){i->transform?.getElement(i/3,i%3)?.toFloat()?:if(i%4==0)1f else 0f}
        for(y in 0 until height)for(x in 0 until width){val xx=x*2*step;val yy=y*2*step
            val r=value(xx+locations[0]%2,yy+locations[0]/2)*gr
            val b=value(xx+locations[1]%2,yy+locations[1]/2)*gb
            val g=(value(xx+greens[0]%2,yy+greens[0]/2)*gg0+value(xx+greens[1]%2,yy+greens[1]/2)*gg1)/2
            val index=(y*width+x)*3
            for(ch in 0..2)rgb[index+ch]=(matrix[ch*3]*r+matrix[ch*3+1]*g+matrix[ch*3+2]*b).coerceIn(0f,1f)
        };return Linear(width,height,rgb)
    }
}
