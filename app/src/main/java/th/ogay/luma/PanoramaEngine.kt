package th.ogay.luma

import android.content.Context
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.*

object PanoramaEngine{
    fun stitch(c:Context,files:List<File>):File{
        require(files.size in 2..12){"ใช้ 2–12 ภาพต่อชุด"};check(OpenCVLoader.initLocal()){ "เปิดระบบต่อภาพไม่ได้" }
        val mats=mutableListOf<Mat>();val homographies=mutableListOf<Mat>();val descriptors=mutableListOf<Mat>();val points=mutableListOf<MatOfKeyPoint>()
        val orb=ORB.create(2500);val matcher=DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        try{
            files.forEach{file->val b=PhotoEffects.read(file,1_200_000);val mat=Mat();Utils.bitmapToMat(b,mat);b.recycle();mats+=mat
                val gray=Mat();Imgproc.cvtColor(mat,gray,Imgproc.COLOR_RGBA2GRAY);val key=MatOfKeyPoint();val desc=Mat();val empty=Mat();orb.detectAndCompute(gray,empty,key,desc);gray.release();empty.release();points+=key;descriptors+=desc}
            homographies+=Mat.eye(3,3,CvType.CV_64F)
            for(i in 1 until mats.size){
                check(!descriptors[i].empty()&&!descriptors[i-1].empty()){ "รายละเอียดไม่พอ ลองฉากที่มีลายชัดเจน" }
                val matches=ArrayList<MatOfDMatch>();matcher.knnMatch(descriptors[i],descriptors[i-1],matches,2)
                val good=matches.mapNotNull{m->val a=m.toArray();if(a.size>=2&&a[0].distance<0.72f*a[1].distance)a[0]else null};matches.forEach{it.release()}
                check(good.size>=16){ "ภาพ ${i+1} ซ้อนทับภาพก่อนหน้าไม่พอ กรุณาซ้อนกันประมาณ 40–60%" }
                val kp=points[i].toArray();val previous=points[i-1].toArray();val from=MatOfPoint2f(*good.map{kp[it.queryIdx].pt}.toTypedArray());val to=MatOfPoint2f(*good.map{previous[it.trainIdx].pt}.toTypedArray());val inliers=Mat()
                val local=Calib3d.findHomography(from,to,Calib3d.RANSAC,3.0,inliers);from.release();to.release()
                check(!local.empty()&&Core.countNonZero(inliers)>=12){"จับคู่ภาพ ${i+1} ไม่ได้ ลองหมุนกล้องให้น้อยลง"};inliers.release()
                val world=Mat();val empty=Mat();Core.gemm(homographies.last(),local,1.0,empty,0.0,world);empty.release();local.release();homographies+=world
            }
            val corners=mats.mapIndexed{i,m->val a=MatOfPoint2f(Point(0.0,0.0),Point(m.cols().toDouble(),0.0),Point(m.cols().toDouble(),m.rows().toDouble()),Point(0.0,m.rows().toDouble()));val b=MatOfPoint2f();Core.perspectiveTransform(a,b,homographies[i]);val r=b.toArray().toList();a.release();b.release();r}.flatten()
            val minX=corners.minOf{it.x};val minY=corners.minOf{it.y};val maxX=corners.maxOf{it.x};val maxY=corners.maxOf{it.y}
            check((maxX-minX).isFinite()&&(maxY-minY).isFinite()&&maxX>minX&&maxY>minY){"มุมมองภาพไม่ถูกต้อง"}
            val scale=min(1.0,sqrt(6_000_000.0/((maxX-minX)*(maxY-minY))))
            val width=ceil((maxX-minX)*scale).toInt();val height=ceil((maxY-minY)*scale).toInt();check(width>0&&height>0&&width<20000&&height<20000){"ภาพกว้างเกินไป"}
            val output=Mat.zeros(height,width,CvType.CV_8UC4);val weights=ShortArray(width*height)
            val translate=Mat.eye(3,3,CvType.CV_64F);translate.put(0,0,scale);translate.put(1,1,scale);translate.put(0,2,-minX*scale);translate.put(1,2,-minY*scale)
            try{
                mats.forEachIndexed{i,m->
                    val transform=Mat();val empty=Mat();Core.gemm(translate,homographies[i],1.0,empty,0.0,transform);empty.release()
                    val warped=Mat();Imgproc.warpPerspective(m,warped,transform,Size(width.toDouble(),height.toDouble()))
                    val mask=Mat(m.rows(),m.cols(),CvType.CV_8UC1);val row=ByteArray(m.cols())
                    for(y in 0 until m.rows()){for(x in row.indices)row[x]=(minOf(x,y,m.cols()-1-x,m.rows()-1-y,50).coerceAtLeast(0)*5).coerceAtMost(250).toByte();mask.put(y,0,row)}
                    val feather=Mat();Imgproc.warpPerspective(mask,feather,transform,Size(width.toDouble(),height.toDouble()));mask.release();transform.release()
                    val a=ByteArray(width*4);val b=ByteArray(width*4);val w=ByteArray(width)
                    for(y in 0 until height){output.get(y,0,a);warped.get(y,0,b);feather.get(y,0,w)
                        for(x in 0 until width){val weight=w[x].toInt()and 255;if(weight==0)continue;val pos=y*width+x;val old=weights[pos].toInt();for(ch in 0..2){val v=((a[x*4+ch].toInt()and 255)*old+(b[x*4+ch].toInt()and 255)*weight)/(old+weight);a[x*4+ch]=v.toByte()};a[x*4+3]=255.toByte();weights[pos]=(old+weight).toShort()};output.put(y,0,a)
                    };warped.release();feather.release()
                }
                val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);Utils.matToBitmap(output,bitmap);val file=File(c.cacheDir,"panorama-${System.currentTimeMillis()}.jpg");PhotoEffects.write(bitmap,file);bitmap.recycle();return file
            }finally{translate.release();output.release()}
        }finally{mats.forEach{it.release()};homographies.forEach{it.release()};descriptors.forEach{it.release()};points.forEach{it.release()};orb.clear();matcher.clear()}
    }
}
