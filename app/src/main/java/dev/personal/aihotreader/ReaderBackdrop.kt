package dev.personal.aihotreader

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Paints behind system bars without giving the document a second safe area. */
class ReaderBackdrop(var pageColor: Int, var webTopColor: Int, var webBottomColor: Int) : Drawable() {
    var topColor = webTopColor
    var bottomColor = webBottomColor
    var sideColor = pageColor
    var topInset = 0
    var bottomInset = 0
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        canvas.drawColor(sideColor)
        paint.color = topColor
        canvas.drawRect(0f, 0f, bounds.width().toFloat(), topInset.toFloat(), paint)
        paint.color = bottomColor
        canvas.drawRect(0f, (bounds.height() - bottomInset).toFloat(),
            bounds.width().toFloat(), bounds.height().toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
