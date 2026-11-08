package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class CompositionOverlay(context: Context) : View(context) {
    var grid = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66ffffff
        strokeWidth = context.resources.displayMetrics.density
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!grid) return
        for (i in 1..2) {
            canvas.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), paint)
            canvas.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, paint)
        }
    }
}
