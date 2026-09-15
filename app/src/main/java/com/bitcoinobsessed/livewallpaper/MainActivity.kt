package com.bitcoinobsessed.livewallpaper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.*
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity: Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var content:LinearLayout
    private lateinit var alertsPanel:LinearLayout
    private lateinit var preview:ChartView
    private lateinit var status:TextView
    private var accountBusy=false
    private var active=false
    private val prefs get()=Store.prefs(this)
    private val tick=object:Runnable { override fun run() { if(active) { refresh(); handler.postDelayed(this,60000) } } }
    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        AccountSession.auth.setLanguageCode("en")
        val scroll=ScrollView(this).apply { setBackgroundColor(Color.rgb(16,18,22)); isFillViewport=true }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(16),dp(22),dp(32)) }
        scroll.addView(content); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            if(Build.VERSION.SDK_INT>=30) { val b=insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(b.left,b.top,b.right,b.bottom) }
            else { @Suppress("DEPRECATION") view.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom) }
            insets
        }
        text("₿  BITCOIN OBSESSED",13,Chart.orange,true)
        text("Your daily Bitcoin fix.",28,Color.WHITE,true)
        text("LIVE WALLPAPER  /  1H CANDLES",11,Color.LTGRAY)
        preview=ChartView(this)
        content.addView(preview,LinearLayout.LayoutParams(-1,dp(285)).apply { topMargin=dp(20); bottomMargin=dp(12) })
        status=text("Connecting to the market…",13,Color.LTGRAY)
        button("↻ Refresh now") { refresh(true) }
        section("01","Your screen")
        button("Add candlestick widget") {
            val manager=getSystemService(AppWidgetManager::class.java)
            if(manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this,BitcoinWidget::class.java),null,null)
            else message("Touch and hold your home screen → Widgets → Bitcoin Obsessed.")
        }
        button("Choose your wallpaper photo") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) },10)
        }
        button("Set live wallpaper") {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,ComponentName(this,BitcoinWallpaper::class.java)))
        }
        section("02","Always in sight")
        val notification=Switch(this).apply { text="Silent price notification"; setTextColor(Color.WHITE); isChecked=prefs.getBoolean("priceNotification",false); setPadding(0,dp(12),0,dp(12)) }
        notification.setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean("priceNotification",checked).apply(); if(checked)permission(); Notifications.price(this) }
        content.addView(notification)
        text("Updates about once a minute while the app or wallpaper is visible. Background updates may be delayed by Android. The last update time is always shown.",13,Color.LTGRAY)
        section("03","Smart alerts")
        alertsPanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(alertsPanel)
        renderAlerts()
        text("Your wallpaper photo stays on your phone. Prices refer to a Bitcoin perpetual market. The displayed quote is the mid price.",12,Color.GRAY)
        text("FREE · NO ADS · v${BuildConfig.VERSION_NAME}",11,Chart.orange,true)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            prefs.edit().putString("fcmToken",token).apply()
            if(AccountSession.available(this)) { Enrollment.queue(this); syncQuietly() }
        }
        if(AccountSession.auth.currentUser!=null)checkAccount()
    }

    private fun renderAlerts(note:String?=null) {
        alertsPanel.removeAllViews()
        val root=content; content=alertsPanel
        try {
            val user=AccountSession.auth.currentUser
            text("RSI signals. Price targets. One timely nudge.",19,Color.WHITE,true)
            if(user==null) {
                text("All features are free, with no ads or subscriptions. Sign in with Google to connect your RSI and price alerts. Your wallpaper and widget work without an account.",14,Color.LTGRAY)
                button(if(accountBusy)"Signing in…" else "Sign in with Google",!accountBusy) { signIn() }
            } else {
                text(user.email ?: "Signed in",14,Color.LTGRAY)
                if(accountBusy)text("Connecting your account…",14,Chart.orange)
                else if(!AccountSession.available(this)) {
                    text("We couldn't connect your account. Check your connection and try again.",14,Color.LTGRAY)
                    button("Connect account") { checkAccount() }
                } else {
                    text("ALL FEATURES FREE",12,Chart.green,true)
                    text("RSI alerts use Wilder's RSI on closed 1h candles. One notification when entering overbought or oversold; the first check establishes a baseline.",13,Color.LTGRAY)
                    val enabled=switch("RSI alerts",prefs.getBoolean("alerts",false))
                    val period=number("RSI period",prefs.getInt("period",14).toString())
                    val low=number("Oversold level",prefs.getInt("low",30).toString())
                    val high=number("Overbought level",prefs.getInt("high",70).toString())
                    text("PRICE TARGET",12,Chart.orange,true)
                    val targetSwitch=switch("Price target alert",prefs.getBoolean("priceAlert",false))
                    val target=number("Target price ($)",prefs.getString("priceTarget","")!!,true)
                    text("Checked about once a minute. A crossing triggers one notification. A brief touch between checks can be missed. Saving an active target starts a new one-time alert.",13,Color.LTGRAY)
                    if(prefs.getBoolean("priceFired",false))text("Your last target was reached. Save a target to arm it again.",13,Chart.green)
                    button("Save alerts") {
                        val n=period.text.toString().toIntOrNull(); val l=low.text.toString().toIntOrNull(); val h=high.text.toString().toIntOrNull()
                        val price=target.text.toString().trim().toDoubleOrNull()
                        if(n==null||n !in 2..50||l==null||h==null||l !in 1..98||h !in 2..99||l>=h) { message("Use a period of 2–50 and levels where 0 < oversold < overbought < 100."); return@button }
                        if(targetSwitch.isChecked&&(price==null||!price.isFinite()||price<=0||price>1e9)) { message("Enter a target price greater than zero. Use a dot for decimals and no thousands separators."); return@button }
                        prefs.edit().putInt("period",n).putInt("low",l).putInt("high",h).putBoolean("alerts",enabled.isChecked)
                            .putBoolean("priceAlert",targetSwitch.isChecked).putString("priceTarget",price?.toString() ?: "")
                            .putString("priceRuleId",UUID.randomUUID().toString()).putBoolean("priceFired",false).apply()
                        if(enabled.isChecked||targetSwitch.isChecked)permission()
                        Enrollment.queue(this); syncQuietly(true)
                    }
                    button("Send test notification") {
                        if(!Notifications.allowed(this)) { permission(); message("Allow notifications, then try again."); return@button }
                        scope.launch {
                            val result=withContext(Dispatchers.IO) { runCatching {
                                Enrollment.queue(this@MainActivity); Enrollment.sync(this@MainActivity)
                                val id=prefs.getString("deviceId",null) ?: error("Save your alerts first.")
                                AccountSession.request(this@MainActivity,"/test",JSONObject().put("id",id),"POST")
                            } }
                            if(!isDestroyed)message(if(result.isSuccess)"Test sent. Check your notifications." else "Could not send the test. Check your connection and sign-in, then try again.")
                        }
                    }
                    button("Sound and vibration settings") {
                        startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,"rsi"))
                    }
                    prefs.getString("lastAlert",null)?.let { text(it,12,Color.LTGRAY) }
                }
                button("Sign out",!accountBusy) { signOut() }
            }
            (note ?: prefs.getString("syncStatus",null))?.let { text(it,13,Chart.orange) }
        } finally { content=root }
    }

    private fun signIn() {
        if(accountBusy)return
        val clientIdResource=resources.getIdentifier("default_web_client_id","string",packageName)
        if(clientIdResource==0) { message("Google sign-in setup is not ready. Please install the latest app build."); return }
        accountBusy=true; renderAlerts()
        scope.launch {
            var note:String?=null
            try {
                val option=GetSignInWithGoogleOption.Builder(getString(clientIdResource)).build()
                val credential=CredentialManager.create(this@MainActivity).getCredential(this@MainActivity,GetCredentialRequest.Builder().addCredentialOption(option).build())
                val google=GoogleIdTokenCredential.createFrom(credential.credential.data)
                withContext(Dispatchers.IO) {
                    Tasks.await(AccountSession.auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken,null)),30,TimeUnit.SECONDS)
                    AccountSession.refresh(this@MainActivity)
                }
            } catch(_:GetCredentialCancellationException) { note="Sign-in cancelled." }
            catch(_:Exception) { note="Could not complete Google sign-in. Check your connection and try again." }
            finally { accountBusy=false; if(!isDestroyed)renderAlerts(note) }
        }
    }
    private fun checkAccount() {
        if(accountBusy)return
        accountBusy=true; renderAlerts()
        scope.launch {
            val result=withContext(Dispatchers.IO) { runCatching {
                val connected=AccountSession.refresh(this@MainActivity)
                if(connected && !prefs.getBoolean("syncPending",false)) {
                    val id=prefs.getString("deviceId",null)
                    if(id!=null) {
                        val state=AccountSession.request(this@MainActivity,"/device?id=$id")
                        if(state.optString("price_rule_id")==prefs.getString("priceRuleId",null))prefs.edit().putBoolean("priceFired",state.optInt("price_fired")==1).apply()
                    }
                }
            } }
            accountBusy=false
            if(!isDestroyed)renderAlerts(if(result.isFailure)"Could not connect your account. Please try again." else null)
        }
    }
    private fun signOut() {
        accountBusy=true; renderAlerts()
        scope.launch {
            withContext(Dispatchers.IO) { AccountSession.signOut(this@MainActivity) }
            runCatching { CredentialManager.create(this@MainActivity).clearCredentialState(ClearCredentialStateRequest()) }
            accountBusy=false; if(!isDestroyed)renderAlerts()
        }
    }
    private fun syncQuietly(showResult:Boolean=false) {
        scope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { Enrollment.sync(this@MainActivity) } }
            if(showResult && !isDestroyed)renderAlerts(if(result.isSuccess)"Alerts saved." else "Save pending. Check your connection and try again.")
        }
    }
    override fun onResume() { super.onResume(); active=true; handler.post(tick) }
    override fun onPause() { active=false; handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { scope.cancel(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun refresh(force:Boolean=false) {
        Store.fetchAsync(this,force) { result -> runOnUiThread {
            if(isDestroyed)return@runOnUiThread
            preview.invalidate()
            status.text=result.fold({m->
                val closed=m.candles.filter {it.time+HOUR<=System.currentTimeMillis()}
                val value=runCatching {Indicators.rsi(closed.map {it.close},prefs.getInt("period",14))}.getOrNull()
                "RSI ${prefs.getInt("period",14)} · ${value?.let {String.format(java.util.Locale.US,"%.1f",it)} ?: "—"} · closed ${Chart.time(closed.last().time+HOUR)}"
            },{"Unable to update. Showing the last available price."})
        } }
    }
    private fun text(value:String,size:Int,color:Int=Color.WHITE,bold:Boolean=false):TextView=TextView(this).also {
        it.text=value; it.textSize=size.toFloat(); it.setTextColor(color); if(bold)it.setTypeface(null,Typeface.BOLD)
        it.setPadding(0,dp(5),0,dp(7)); content.addView(it)
    }
    private fun section(number:String,title:String) {text("$number  /  ${title.uppercase(java.util.Locale.ENGLISH)}",15,Chart.orange,true).setPadding(0,dp(32),0,dp(12))}
    private fun button(label:String,enabled:Boolean=true,action:()->Unit) {
        val b=Button(this).apply {text=label; isAllCaps=false; isEnabled=enabled; setTextColor(Color.WHITE); textSize=15f
            background=GradientDrawable().apply {setColor(Color.rgb(35,39,46)); cornerRadius=dp(14).toFloat(); setStroke(dp(1),Color.rgb(58,63,71))}
            setOnClickListener {action()}
        }
        content.addView(b,LinearLayout.LayoutParams(-1,dp(52)).apply {topMargin=dp(6); bottomMargin=dp(6)})
    }
    private fun switch(label:String,value:Boolean)=Switch(this).also {it.text=label; it.setTextColor(Color.WHITE); it.isChecked=value; it.setPadding(0,dp(12),0,dp(12)); content.addView(it)}
    private fun number(label:String,value:String,decimal:Boolean=false):EditText {
        text(label,13,Color.LTGRAY)
        return EditText(this).also {it.inputType=InputType.TYPE_CLASS_NUMBER or if(decimal)InputType.TYPE_NUMBER_FLAG_DECIMAL else 0; it.setSingleLine(); it.setText(value); it.setTextColor(Color.WHITE); it.contentDescription=label; content.addView(it)}
    }
    private fun permission() {if(Build.VERSION.SDK_INT>=33)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),20)}
    private fun message(s:String) {AlertDialog.Builder(this).setMessage(s).setPositiveButton("OK",null).show()}
    @Deprecated("Legacy activity result for single native activity")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=10||resultCode!=RESULT_OK)return
        val uri=data?.data ?: return
        Store.executor.execute {
            val result=runCatching {
                val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) {decoder,info,_->
                    val scale=minOf(1.0,2048.0/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1)); decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val temp=File(filesDir,"wallpaper.new")
                temp.outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,it))}
                bitmap.recycle(); check(temp.renameTo(File(filesDir,"wallpaper.jpg")))
            }
            runOnUiThread {if(!isDestroyed)message(if(result.isSuccess)"Photo saved. Tap Set live wallpaper to apply it." else "Could not open this photo. Please choose another image.")}
        }
    }
}
