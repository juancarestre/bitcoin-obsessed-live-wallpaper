package com.bitcoinobsessed.livewallpaper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*

class BitcoinWidget: AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { renderAll(context); enqueue(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { render(context,manager,id) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context,intent)
        if (intent.action == "com.bitcoinobsessed.REFRESH") {
            enqueue(context)
        }
    }
    companion object {
        private fun enqueue(c: Context) {
            WorkManager.getInstance(c).enqueueUniqueWork("widget-refresh",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun renderAll(c: Context) {
            val manager=AppWidgetManager.getInstance(c)
            manager.getAppWidgetIds(ComponentName(c,BitcoinWidget::class.java)).forEach { render(c,manager,it) }
        }
        fun render(c: Context, manager: AppWidgetManager, id: Int) {
            val opts=manager.getAppWidgetOptions(id)
            // Cap bitmap dimensions to stay within RemoteViews binder memory limits.
            val w=(opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180)*2).coerceIn(220,800)
            val h=(opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,220)*2).coerceIn(180,800)
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            Chart.draw(Canvas(bitmap),w.toFloat(),h.toFloat(),Store.cached(c),c, h.toFloat()/w<.65)
            val views=RemoteViews(c.packageName,R.layout.widget)
            views.setImageViewBitmap(R.id.chart,bitmap)
            Store.cached(c)?.let { views.setContentDescription(R.id.chart,"Bitcoin perpetual. Price ${Chart.money(it.price)}. Updated ${Chart.time(it.updated)}. Tap to open the app.") }
            views.setOnClickPendingIntent(R.id.chart,PendingIntent.getActivity(c,id,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.refresh,PendingIntent.getBroadcast(c,id,Intent(c,BitcoinWidget::class.java).setAction("com.bitcoinobsessed.REFRESH"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setTextColor(R.id.refresh,android.graphics.Color.WHITE)
            manager.updateAppWidget(id,views)
        }
    }
}
