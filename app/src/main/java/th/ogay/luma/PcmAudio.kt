package th.ogay.luma

import android.annotation.SuppressLint
import android.media.*
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class PcmAudio(private val file:File){
    private val running=AtomicBoolean();private var recorder:AudioRecord?=null;private var thread:Thread?=null
    private var bytes=0L;private var first=0L;private var hardware=0L;private var framePosition=0L
    @SuppressLint("MissingPermission")
    fun start(){
        val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        val r=AudioRecord(MediaRecorder.AudioSource.CAMCORDER,48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,48000))
        check(r.state==AudioRecord.STATE_INITIALIZED){"เปิดไมค์ไม่ได้"};recorder=r;running.set(true);r.startRecording();first=SystemClock.elapsedRealtimeNanos()
        thread=Thread{
            RandomAccessFile(file,"rw").use{out->out.setLength(0);out.write(ByteArray(44));val buffer=ByteArray(maxOf(min,9600))
                while(running.get()){val n=r.read(buffer,0,buffer.size);if(n>0){out.write(buffer,0,n);bytes+=n;if(hardware==0L&&Build.VERSION.SDK_INT>=24){val t=AudioTimestamp();if(r.getTimestamp(t,AudioTimestamp.TIMEBASE_BOOTTIME)==AudioRecord.SUCCESS){hardware=t.nanoTime;framePosition=t.framePosition}}}else if(n<0)break}
                header(out,bytes)
            }
        }.apply{name="LumaRawAudio";start()}
    }
    fun stop():JSONObject{
        running.set(false);runCatching{recorder?.stop()};thread?.join(3000);recorder?.release();recorder=null
        return JSONObject().put("sampleRate",48000).put("channels",1).put("bits",16).put("samples",bytes/2).put("startElapsedRealtimeNs",first).put("hardwareTimestampNs",hardware).put("hardwareFramePosition",framePosition)
    }
    companion object{
        fun recover(file:File){if(file.exists()&&file.length()>=44)RandomAccessFile(file,"rw").use{header(it,it.length()-44)}}
        private fun header(out:RandomAccessFile,bytes:Long){fun int(v:Long){out.writeInt(Integer.reverseBytes(v.toInt()))};fun short(v:Int){out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())}
            out.seek(0);out.writeBytes("RIFF");int(bytes+36);out.writeBytes("WAVEfmt ");int(16);short(1);short(1);int(48000);int(96000);short(2);short(16);out.writeBytes("data");int(bytes)
        }
    }
}
