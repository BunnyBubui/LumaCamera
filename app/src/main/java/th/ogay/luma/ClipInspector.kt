package th.ogay.luma

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import java.io.File

object ClipInspector{
    fun inspect(c:Context,uri:Uri):JSONObject{
        val ex=MediaExtractor();val json=JSONObject()
        try{ex.setDataSource(c,uri,null);var video=-1;var audio=false
            for(i in 0 until ex.trackCount){val f=ex.getTrackFormat(i);val mime=f.getString(MediaFormat.KEY_MIME)?:"";if(mime.startsWith("video/")){video=i;json.put("codec",mime);for(key in listOf(MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT,MediaFormat.KEY_FRAME_RATE,MediaFormat.KEY_COLOR_STANDARD,MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.KEY_COLOR_RANGE))if(f.containsKey(key))json.put(key,f.getInteger(key))}else if(mime.startsWith("audio/"))audio=true}
            check(video>=0){"ไม่มี video track"};ex.selectTrack(video)
            var count=0L;var first=-1L;var last=-1L
            while(ex.sampleTime>=0){if(first<0)first=ex.sampleTime;last=ex.sampleTime;count++;if(!ex.advance())break}
            json.put("frames",count).put("audio",audio).put("firstSampleUs",first).put("lastSampleUs",last).put("measuredFps",if(last>first)(count-1)*1_000_000.0/(last-first)else 0.0)
            return json
        }finally{ex.release()}
    }
    fun record(c:Context,uri:Uri,name:String,requestedFps:Int?=null,sensor:JSONObject?=null,playbackFps:Int?=requestedFps){runCatching{
        val report=inspect(c,uri)
        if(requestedFps!=null){report.put("requestedCaptureFps",requestedFps).put("expectedPlaybackFps",playbackFps);report.put("matchesRequestedPlaybackRate",playbackFps!=null&&kotlin.math.abs(report.optDouble("measuredFps")-playbackFps)<playbackFps*0.1)}
        if(sensor!=null)report.put("sensorTiming",sensor)
        val file=File(c.filesDir,"reports/$name.json");file.parentFile?.mkdirs();file.writeText(report.toString(2));MediaLedger.rememberFile(c,file,"application/json","ผลตรวจไฟล์วิดีโอจริง")
        MediaLedger.add(c,uri,name,"video/mp4","${report.optInt("width")}×${report.optInt("height")} · %.1f fps · %s".format(java.util.Locale.US,report.optDouble("measuredFps"),if(report.optBoolean("audio"))"มีเสียง" else "ไม่มีเสียง"))
    }}
}
