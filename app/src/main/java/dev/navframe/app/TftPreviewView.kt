package dev.navframe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import dev.navframe.core.TftFrame

/** Decode the transmitted JPEG. Scaling affects only the phone preview. */
class TftPreviewView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val destination = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    fun showFrame(frame: TftFrame) {
        val next = BitmapFactory.decodeByteArray(frame.encodedBytes, 0, frame.encodedBytes.size) ?: return
        require(next.width == 480 && next.height == 240)
        bitmap?.recycle()
        bitmap = next
        contentDescription = "Vista TFT, imagen JPEG nativa de 480 por 240 píxeles"
        invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width / 2)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        destination.set(0, 0, width, height)
        bitmap?.let { canvas.drawBitmap(it, null, destination, paint) }
    }
    override fun onDetachedFromWindow() { bitmap?.recycle(); bitmap = null; super.onDetachedFromWindow() }
}
