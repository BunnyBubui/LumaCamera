package th.ogay.luma

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraProbeTest {
    @get:Rule val permission=GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun until(condition:()->Boolean){
        val deadline=SystemClock.elapsedRealtime()+60000
        while(SystemClock.elapsedRealtime()<deadline){
            val ready=try{condition()}catch(_:androidx.test.uiautomator.StaleObjectException){false}
            if(ready)return;SystemClock.sleep(200)
        }
        assertTrue("Camera probe did not reach the expected state",condition())
    }
    @Test fun autoStopsAndStoresActualFileFrameReport(){
        val old=MediaLedger.all(context).map{it.uri}.toSet()
        ActivityScenario.launch<CameraProbeActivity>(Intent(context,CameraProbeActivity::class.java)).use{
            until{device.findObject(By.desc("เริ่มทดสอบกล้อง 10 วินาที"))?.isEnabled==true}
            device.findObject(By.desc("เริ่มทดสอบกล้อง 10 วินาที")).click()
            until{MediaLedger.all(context).any{it.uri !in old&&it.title.startsWith("camera-probe-")}}
            val item=MediaLedger.all(context).first{it.uri !in old&&it.title.startsWith("camera-probe-")}
            val json=context.contentResolver.openInputStream(item.uri)!!.bufferedReader().use{JSONObject(it.readText())}
            assertEquals(json.toString(),"complete",json.getString("stage"))
            assertFalse(json.getBoolean("cancelled"))
            assertTrue(json.getLong("captureWallNs")>=9_000_000_000L)
            assertTrue(json.getJSONObject("file").getLong("frames")>30)
            assertFalse(json.getJSONObject("file").getBoolean("audio"))
            assertTrue(json.getDouble("captureFpsEstimateFromFile")>10)
            val inventory=json.getJSONObject("inventory")
            assertEquals(2,inventory.getInt("schema"))
            val cameras=inventory.getJSONArray("cameras")
            assertTrue(cameras.length()>0)
            for(i in 0 until cameras.length()){
                assertTrue(cameras.getJSONObject(i).has("videoTiming"))
                assertTrue(cameras.getJSONObject(i).has("highSpeedConfigurations"))
            }
        }
    }
}
