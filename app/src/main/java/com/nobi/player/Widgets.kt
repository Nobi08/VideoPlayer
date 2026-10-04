package com.nobi.player
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class BarView(c: Context) : View(c) {
  var level = 0f
  private val d = resources.displayMetrics.density
  private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
  override fun onDraw(cv: Canvas) {
    val w = width.toFloat(); val hh = height.toFloat()
    val bw = 14 * d; val kr = 11 * d
    val l = (w - bw) / 2; val r = l + bw; val top = kr; val bot = hh - kr
    val ky = bot - level.coerceIn(0f, 1f) * (bot - top)
    pt.style = Paint.Style.FILL
    pt.color = 0xCC333333.toInt(); cv.drawRoundRect(l, top - bw / 2, r, bot + bw / 2, bw / 2, bw / 2, pt)
    pt.color = Color.WHITE; cv.drawRoundRect(l, ky, r, bot + bw / 2, bw / 2, bw / 2, pt)
    cv.drawCircle(w / 2, ky, kr, pt)
    pt.style = Paint.Style.STROKE; pt.strokeWidth = 1.5f * d; pt.color = Color.BLACK
    cv.drawCircle(w / 2, ky, kr, pt)
  }
}

class Gauge(c: Context, ic: Int) : LinearLayout(c) {
  private val bar = BarView(c)
  private val tv = TextView(c)
  init {
    val d = resources.displayMetrics.density
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
    addView(ImageView(c).apply { setImageResource(ic) }, LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()))
    addView(bar, LinearLayout.LayoutParams((40 * d).toInt(), (150 * d).toInt()))
    tv.setTextColor(Color.WHITE); tv.textSize = 16f; tv.typeface = Typeface.DEFAULT_BOLD; tv.gravity = Gravity.CENTER
    addView(tv, LinearLayout.LayoutParams(-2, -2))
  }
  fun set(level: Float) {
    bar.level = level; bar.invalidate()
    tv.text = (level.coerceIn(0f, 1f) * 100).roundToInt().toString()
    visibility = View.VISIBLE
  }
}
