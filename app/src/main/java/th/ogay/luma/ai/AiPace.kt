package th.ogay.luma.ai

import android.content.Context

/**
 * How hard the AI works. While someone watches its progress it runs flat out; otherwise (with the saver on, the
 * default) it rests after each tile for half as long as the tile took, so the phone stays smooth and cool and the
 * job simply finishes later.
 */
object AiPace {
    @Volatile var watching = false
    fun afterTile(context: Context, tileMs: Long) {
        val saver = !watching && context.getSharedPreferences("camera", 0).getBoolean("ai_saver", true)
        android.os.Process.setThreadPriority(if (saver) android.os.Process.THREAD_PRIORITY_BACKGROUND else android.os.Process.THREAD_PRIORITY_DEFAULT)
        // Rests half as long as the tile took (was as long): still cool and smooth, but a background job ends a third sooner.
        if (saver) Thread.sleep((tileMs / 2).coerceIn(10L, 250L))
    }
}
