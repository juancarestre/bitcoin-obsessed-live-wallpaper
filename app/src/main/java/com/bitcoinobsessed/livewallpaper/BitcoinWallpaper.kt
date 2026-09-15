package com.bitcoinobsessed.livewallpaper

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class BitcoinWallpaper: WallpaperService() {
    override fun onCreateEngine(): Engine = LiveEngine()
    inner class LiveEngine: Engine() {
        private val handler=Handler(Looper.getMainLooper())
        private var shown=false
        private var photo: Bitmap?=null
        private var photoStamp=-1L
        private var fetching=false
        private val tick=object: Runnable {
            override fun run() {
                if(!shown)return
                drawFrame()
                if(!fetching) {
                    fetching=true
                    Store.fetchAsync(applicationContext) { handler.post { fetching=false; if(shown)drawFrame() } }
                }
                handler.postDelayed(this,60000)
            }
        }
        override fun onVisibilityChanged(visible: Boolean) { shown=visible; handler.removeCallbacks(tick); if(visible)handler.post(tick) }
        override fun onSurfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { if(shown)drawFrame() }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { shown=false; handler.removeCallbacks(tick); super.onSurfaceDestroyed(holder) }
        override fun onDestroy() { shown=false; handler.removeCallbacksAndMessages(null); photo?.recycle(); photo=null; super.onDestroy() }
        private fun drawFrame() {
            if(!surfaceHolder.surface.isValid)return
            val c=runCatching { surfaceHolder.lockCanvas() }.getOrNull() ?: return
            try {
                val prefs=Store.prefs(applicationContext)
                c.drawColor(Color.rgb(16,18,22))
                val file=File(filesDir,"wallpaper.jpg")
                if(photoStamp!=file.lastModified()) {
                    photo?.recycle(); photo=BitmapFactory.decodeFile(file.path); photoStamp=file.lastModified()
                }
                photo?.let { b ->
                    val scale=maxOf(c.width.toFloat()/b.width,c.height.toFloat()/b.height)
                    val left=(c.width-b.width*scale)/2; val top=(c.height-b.height*scale)/2
                    c.drawBitmap(b,null,RectF(left,top,left+b.width*scale,top+b.height*scale),Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val market=Store.cached(applicationContext)
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; setShadowLayer(4f,1f,2f,Color.BLACK) }
                val factor=resources.displayMetrics.density
                val margin=20f*factor
                val x=c.width-margin
                val y=(c.height-160f*factor)*.04f+80f*factor
                p.textAlign=Paint.Align.RIGHT
                p.textSize=18*factor
                val text=market?.let { "BTC  $${Chart.money(it.price)}" } ?: "BTC · connecting…"
                c.drawText(text,x,y,p)
                p.textSize=11f*factor
                val details=market?.let { String.format(java.util.Locale.US,"%+.2f%% · 24h · %s",it.change,Chart.time(it.updated)) } ?: "No data"
                c.drawText(details,x,y+22*factor,p)
                c.drawText("Perpetual · mid price"+(if(market!=null && (Chart.stale(market)||prefs.contains("marketError")))" · OUT OF DATE" else ""),x,y+40*factor,p)
            } finally { surfaceHolder.unlockCanvasAndPost(c) }
        }
    }
}
