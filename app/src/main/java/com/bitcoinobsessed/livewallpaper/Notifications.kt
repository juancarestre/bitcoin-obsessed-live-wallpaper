package com.bitcoinobsessed.livewallpaper

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

val BACKEND: String get() = BuildConfig.API_BASE_URL

class ObsessedApp: Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs=Store.prefs(this)
        // Preserve the existing account's devices/rules when upgrading from the invite-only version.
        prefs.getString("premiumUid",null)?.let { uid ->
            if(!prefs.contains("accountUid"))prefs.edit().putString("accountUid",uid).apply()
        }
        prefs.edit().remove("premium").remove("premiumUid").apply()
        if(!prefs.getBoolean("accountMigrationV2",false)) {
            val edit=prefs.edit().putBoolean("accountMigrationV2",true).putBoolean("alerts",false).putBoolean("syncPending",false)
            listOf("installCode","syncStatus","lastAlert","wallChart","wallX","wallY","textSize","background","opacity","ink","darkText","padding").forEach { edit.remove(it) }
            edit.apply()
            WorkManager.getInstance(this).cancelUniqueWork("enrollment")
        }
        Notifications.channels(this)
        val periodic=PeriodicWorkRequestBuilder<RefreshWorker>(15,TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("market-refresh",ExistingPeriodicWorkPolicy.KEEP,periodic)
    }
}

object Enrollment {
    @Synchronized fun sync(c: Context) {
        val p=Store.prefs(c)
        if(!p.getBoolean("syncPending",false))return
        check(AccountSession.available(c)) { "Sign in with Google to save alerts." }
        val token=p.getString("fcmToken","")!!
        check(token.isNotBlank()) { "Notifications are still connecting. Please try again." }
        val id=p.getString("deviceId",null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId",it).commit() }
        val version=p.getLong("settingsVersion",0)
        val body=JSONObject().put("id",id).put("token",token).put("enabled",p.getBoolean("alerts",false))
            .put("period",p.getInt("period",14)).put("low",p.getInt("low",30)).put("high",p.getInt("high",70))
            .put("priceTarget",if(p.getBoolean("priceAlert",false))p.getString("priceTarget","")!!.toDouble() else JSONObject.NULL)
            .put("priceRuleId",p.getString("priceRuleId",null) ?: JSONObject.NULL)
        AccountSession.request(c,"/device",body,"PUT")
        if(p.getLong("settingsVersion",0)==version) p.edit().putBoolean("syncPending",false).putString("syncStatus","Alerts saved · ${Chart.time(System.currentTimeMillis())}").apply()
    }
    fun queue(c: Context) {
        Store.prefs(c).edit().putBoolean("syncPending",true).putLong("settingsVersion",System.currentTimeMillis()).apply()
        WorkManager.getInstance(c).enqueueUniqueWork("enrollment",ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EnrollmentWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
}
class EnrollmentWorker(c: Context,params: WorkerParameters): Worker(c,params) {
    override fun doWork(): Result = try { Enrollment.sync(applicationContext); Result.success() } catch(e:Exception) {
        Store.prefs(applicationContext).edit().putString("syncStatus","Connection pending. Open the app to try again.").apply()
        if(runAttemptCount<8)Result.retry() else Result.failure()
    }
}
class RefreshWorker(c: Context,params: WorkerParameters): Worker(c,params) {
    override fun doWork(): Result {
        val result=runCatching { Store.refresh(applicationContext) }
        result.exceptionOrNull()?.let { Store.prefs(applicationContext).edit().putString("marketError","Unable to update. Check your connection.").apply() }
        BitcoinWidget.renderAll(applicationContext); Notifications.price(applicationContext)
        return if(result.isSuccess)Result.success() else Result.retry()
    }
}

object Notifications {
    fun channels(c: Context) {
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("price","Bitcoin price",NotificationManager.IMPORTANCE_LOW).apply { description="Silent price updates and last update time"; setSound(null,null); enableVibration(false) })
        manager.createNotificationChannel(NotificationChannel("rsi","Smart alerts",NotificationManager.IMPORTANCE_HIGH).apply { description="RSI signals and price target alerts"; enableVibration(true) })
    }
    fun allowed(c: Context) = (Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(c).areNotificationsEnabled()
    private fun open(c: Context) = PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun price(c: Context) {
        val manager=NotificationManagerCompat.from(c)
        if(!Store.prefs(c).getBoolean("priceNotification",false)) { manager.cancel(1); return }
        if(!allowed(c))return
        val market=Store.cached(c) ?: return
        val suffix=if(Chart.stale(market)||Store.prefs(c).contains("marketError"))" · OUT OF DATE" else ""
        val text=String.format(java.util.Locale.US,"%+.2f%% · 24h · %s%s",market.change,Chart.time(market.updated),suffix)
        val builder=NotificationCompat.Builder(c,"price").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BTC $${Chart.money(market.price)} · Perp").setContentText(text)
            .setSubText("Bitcoin · mid price").setContentIntent(open(c)).setOnlyAlertOnce(true).setSilent(true).setOngoing(true)
        val bitmap=android.graphics.Bitmap.createBitmap(640,420,android.graphics.Bitmap.Config.ARGB_8888)
        Chart.draw(android.graphics.Canvas(bitmap),640f,420f,market,c)
        builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(text))
        try { manager.notify(1,builder.build()) } catch(_:SecurityException) { }
    }
    @Synchronized fun alert(c: Context, data: Map<String,String>) {
        val p=Store.prefs(c)
        val test=data["zone"]=="test"
        val priceAlert=data["zone"]=="price"
        val user=AccountSession.auth.currentUser ?: return
        if(data["ownerUid"]!=user.uid || !AccountSession.available(c) || !allowed(c))return
        if(priceAlert && (!p.getBoolean("priceAlert",false)||data["priceRuleId"]!=p.getString("priceRuleId",null)))return
        if(!priceAlert && !test && !p.getBoolean("alerts",false))return
        val id=data["alertId"] ?: return
        val time=data["closeTime"]?.toLongOrNull() ?: return
        if(System.currentTimeMillis()-time>HOUR || time>System.currentTimeMillis()+60000)return
        val seen=p.getStringSet("seenAlerts",emptySet())!!.toMutableSet()
        if(id in seen)return
        val rsi=data["rsi"]?.toDoubleOrNull() ?: return
        val price=data["price"]?.toDoubleOrNull() ?: return
        if(!rsi.isFinite()||!price.isFinite())return
        val zone=when(data["zone"]) { "overbought"->"Overbought"; "oversold"->"Oversold"; "test"->"Test notification"; "price"->"Price target reached"; else->return }
        val title=if(test)zone else if(priceAlert)"BTC · $zone" else "BTC · $zone · RSI ${String.format(java.util.Locale.US,"%.1f",rsi)}"
        val body=if(test)"Your alerts are connected. Sound and vibration follow your notification settings." else if(priceAlert)"Observed price $${Chart.money(price)} at ${Chart.time(time)}. Your target has been reached. Open the app to set another alert." else "1h candle closed at ${Chart.time(time)} · $${Chart.money(price)} · Bitcoin perpetual"
        val notification=NotificationCompat.Builder(c,"rsi").setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(c)).setAutoCancel(true).setOnlyAlertOnce(true).build()
        try {
            NotificationManagerCompat.from(c).notify(id,2,notification)
            seen.add(id)
            p.edit().putStringSet("seenAlerts",seen.toList().takeLast(100).toSet()).putString("lastAlert","$title · ${Chart.time(time)}").commit()
            if(priceAlert)p.edit().putBoolean("priceFired",true).apply()
        } catch(_:SecurityException) { }
    }
}
class PushService: FirebaseMessagingService() {
    override fun onNewToken(token: String) { Store.prefs(this).edit().putString("fcmToken",token).apply(); if(AccountSession.available(this)) Enrollment.queue(this) }
    override fun onMessageReceived(message: RemoteMessage) { Notifications.alert(this,message.data) }
}
