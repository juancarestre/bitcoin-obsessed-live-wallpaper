package com.bitcoinobsessed.livewallpaper

import android.content.Context
import android.graphics.*
import android.view.View
import java.text.SimpleDateFormat
import java.util.*

object Chart {
    val orange = Color.rgb(247,147,26)
    val green = Color.rgb(55,211,155)
    val red = Color.rgb(255,101,119)
    fun money(v: Double) = String.format(Locale.US, "%,.2f", v)
    fun time(v: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(v))
    fun stale(m: Market) = System.currentTimeMillis() - m.updated > 120000

    fun draw(canvas: Canvas, width: Float, height: Float, market: Market?, context: Context, compact: Boolean = false) {
        val p = Store.prefs(context)
        val scale = width / 320f
        canvas.save(); canvas.scale(scale, scale)
        val w = 320f; val h = height / scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(16,18,22); paint.alpha = 25
        canvas.drawRoundRect(1f,1f,w-1,h-1,26f,26f,paint)
        paint.color = Color.WHITE; paint.alpha = 150; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        canvas.drawRoundRect(1f,1f,w-1,h-1,26f,26f,paint); paint.style = Paint.Style.FILL
        val ink = Color.WHITE
        fun text(s: String, x: Float, y: Float, size: Float, c: Int = ink, bold: Boolean = false) {
            paint.color=c; paint.textSize=size; paint.typeface=if(bold) Typeface.create("sans-serif",Typeface.BOLD) else Typeface.create("sans-serif",Typeface.NORMAL)
            canvas.drawText(s,x,y,paint)
        }
        val pad = 16f
        text("₿  BITCOIN · PERPETUAL",pad,30f,12f,orange,true)
        if (market == null) {
            text("Waiting for price…",pad,70f,22f)
            text("Open the app to connect",pad,100f,13f)
            canvas.restore(); return
        }
        val price = "$${money(market.price)}"
        var priceSize = 37f
        paint.textSize=priceSize; paint.typeface=Typeface.DEFAULT_BOLD
        while (paint.measureText(price)>w-2*pad && priceSize>20) { priceSize--; paint.textSize=priceSize }
        text(price,pad,74f,priceSize,ink,true)
        text(String.format(Locale.US,"%+.2f%% · 24h",market.change),pad,99f,14f,if(market.change>=0)green else red)
        val visible = market.candles.takeLast(24)
        if (!compact && h >= 205 && visible.isNotEmpty()) {
            val top = 134f; val bottom = h-51f
            var low = visible.minOf { it.low }; var high = visible.maxOf { it.high }
            text("Low ${money(low)}  ·  High ${money(high)}",pad,118f,9f)
            val buffer = ((high-low)*.08).coerceAtLeast(.01); low-=buffer; high+=buffer
            fun y(v: Double) = bottom-((v-low)/(high-low)*(bottom-top)).toFloat()
            paint.color=ink; paint.alpha=35; paint.strokeWidth=1f
            for (i in 0..2) { val yy=top+(bottom-top)*i/2; canvas.drawLine(pad,yy,w-pad,yy,paint) }
            val step=(w-pad*2)/24
            visible.forEachIndexed { i,c ->
                val x=pad+step*(i+.5f)
                paint.color=if(c.close>=c.open)green else red
                paint.alpha=if(c.time+HOUR>System.currentTimeMillis())130 else 255
                paint.strokeWidth=1.3f
                canvas.drawLine(x,y(c.high),x,y(c.low),paint)
                val a=minOf(y(c.open),y(c.close)); val b=maxOf(y(c.open),y(c.close)).coerceAtLeast(a+1.5f)
                canvas.drawRect(x-step*.29f,a,x+step*.29f,b,paint)
            }
            val forming=visible.last().time+HOUR>System.currentTimeMillis()
            text("1h · 24 candles"+(if(forming)" · last candle forming" else " · closed"),pad,h-31,10f)
        }
        val error = p.getString("marketError",null)
        val label=if(stale(market)||error!=null) "OUT OF DATE · ${time(market.updated)}" else "Updated ${time(market.updated)} · mid price"
        text(label,pad,h-13f,10f,if(stale(market)||error!=null)orange else ink)
        canvas.restore()
    }
}

class ChartView(context: Context): View(context) {
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val market=Store.cached(context)
        contentDescription=market?.let { "Bitcoin perpetual. Price ${Chart.money(it.price)}. 24-hour change ${String.format(Locale.US,"%.2f",it.change)} percent. Last 24 hourly candles. Updated ${Chart.time(it.updated)}." } ?: "Bitcoin: waiting for data"
        Chart.draw(canvas,width.toFloat(),height.toFloat(),market,context)
    }
}
