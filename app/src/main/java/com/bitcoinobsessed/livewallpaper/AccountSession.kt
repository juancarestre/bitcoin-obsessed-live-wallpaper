package com.bitcoinobsessed.livewallpaper

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object AccountSession {
    val auth get() = FirebaseAuth.getInstance()
    fun available(c: Context): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        val p = Store.prefs(c)
        return p.getString("accountUid", null) == uid
    }
    fun request(c: Context, path: String, data: JSONObject? = null, method: String = "GET"): JSONObject {
        val user = auth.currentUser ?: error("Sign in to continue.")
        val token = Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).token ?: error("Please sign in again.")
        val result = JSONObject(Store.request("$BACKEND$path", data, method, token))
        check(auth.currentUser?.uid == user.uid) { "Your session changed. Please try again." }
        return result
    }
    @Synchronized fun refresh(c: Context): Boolean {
        val user = auth.currentUser ?: return false
        val result = request(c, "/account")
        val p = Store.prefs(c)
        check(result.getString("uid") == user.uid) { "Your session changed. Please try again." }
        if (p.getString("accountUid", null) != user.uid) {
            p.edit().putString("deviceId", UUID.randomUUID().toString()).putBoolean("alerts", false)
                .putBoolean("priceAlert", false).remove("priceRuleId").remove("syncStatus").remove("lastAlert").putBoolean("syncPending", false).apply()
        }
        p.edit().putString("accountUid", user.uid).apply()
        return true
    }
    fun signOut(c: Context) {
        val p = Store.prefs(c)
        // A network failure must not prevent local sign-out. Delete the push token as a fallback.
        p.getString("deviceId", null)?.let { id -> runCatching { request(c, "/device", JSONObject().put("id", id), "DELETE") } }
        auth.signOut()
        p.edit().putBoolean("alerts", false).putBoolean("priceAlert", false)
            .putBoolean("syncPending", false).remove("accountUid").remove("deviceId").remove("priceRuleId").remove("lastAlert").remove("syncStatus").remove("fcmToken").apply()
        runCatching { Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 20, TimeUnit.SECONDS) }
        android.app.NotificationManager::class.java.let { c.getSystemService(it).cancelAll() }
    }
}
