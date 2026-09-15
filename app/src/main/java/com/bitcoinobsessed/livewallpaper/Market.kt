package com.bitcoinobsessed.livewallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

const val HOUR = 3_600_000L
data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double)
data class Market(val price: Double, val change: Double, val updated: Long, val candles: List<Candle>)

object Indicators {
    fun rsi(closes: List<Double>, period: Int = 14): Double {
        require(period >= 2 && closes.size > period && closes.all { it.isFinite() && it > 0 })
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) {
            val delta = closes[i] - closes[i - 1]
            gain += max(delta, 0.0) / period; loss += max(-delta, 0.0) / period
        }
        for (i in period + 1 until closes.size) {
            val delta = closes[i] - closes[i - 1]
            gain = (gain * (period - 1) + max(delta, 0.0)) / period
            loss = (loss * (period - 1) + max(-delta, 0.0)) / period
        }
        return if (loss == 0.0) { if (gain == 0.0) 50.0 else 100.0 } else 100 - 100 / (1 + gain / loss)
    }
}

object Store {
    val executor = Executors.newSingleThreadExecutor()
    fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun request(url: String, data: JSONObject? = null, method: String = "POST", token: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if(data != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { when(connection.responseCode) {401->"Please sign in again.";403->"This request is not allowed for your account.";else->"The service is unavailable. Please try again."} }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun parseCandles(array: JSONArray): List<Candle> = (0 until array.length()).map { i ->
        val j = array.getJSONObject(i)
        Candle(j.getLong("t"), j.getDouble("o"), j.getDouble("h"), j.getDouble("l"), j.getDouble("c"))
    }.sortedBy { it.time }

    fun cached(c: Context): Market? = runCatching {
        val p = c.getSharedPreferences("market", Context.MODE_PRIVATE)
        val j = JSONObject(p.getString("data", "")!!)
        Market(j.getDouble("price"), j.getDouble("change"), j.getLong("updated"), parseCandles(j.getJSONArray("candles")))
    }.getOrNull()

    @Synchronized fun refresh(c: Context, force: Boolean = false): Market {
        val old = cached(c); val now = System.currentTimeMillis()
        if (!force && old != null && now - old.updated < 45000) return old
        val endpoint = "https://api.hyperliquid.xyz/info"
        val raw = JSONArray(request(endpoint, JSONObject().put("type", "candleSnapshot").put("req", JSONObject().put("coin", "BTC").put("interval", "1h").put("startTime", now - 300 * HOUR).put("endTime", now))))
        val candles = parseCandles(raw)
        check(candles.size >= 200 && candles.last().time >= now / HOUR * HOUR - HOUR) { "Price history is incomplete or out of date." }
        candles.forEachIndexed { i, v ->
            check(listOf(v.open, v.high, v.low, v.close).all { it.isFinite() && it > 0 } && v.high >= maxOf(v.open, v.close) && v.low <= minOf(v.open, v.close)) { "Invalid candle data." }
            check(i == 0 || v.time == candles[i - 1].time + HOUR) { "Some candles are missing." }
        }
        // Mid and prevDayPx are from the same BTC perpetual market, not a spot conversion.
        val contexts = JSONArray(request(endpoint, JSONObject().put("type", "metaAndAssetCtxs")))
        val universe = contexts.getJSONObject(0).getJSONArray("universe")
        val index = (0 until universe.length()).first { universe.getJSONObject(it).getString("name") == "BTC" }
        val asset = contexts.getJSONArray(1).getJSONObject(index)
        val price = asset.getString("midPx").toDouble()
        val previous = asset.getString("prevDayPx").toDouble()
        check(price.isFinite() && price > 0 && previous.isFinite() && previous > 0)
        val change = (price / previous - 1) * 100
        val result = Market(price, change, now, candles)
        val saved = JSONObject().put("price", price).put("change", change).put("updated", now).put("candles", raw)
        c.getSharedPreferences("market", Context.MODE_PRIVATE).edit().putString("data", saved.toString()).apply()
        prefs(c).edit().remove("marketError").apply()
        return result
    }
    fun fetchAsync(c: Context, force: Boolean = false, callback: ((Result<Market>) -> Unit)? = null) {
        val app = c.applicationContext
        executor.execute {
            val result = runCatching { refresh(app, force) }
            result.exceptionOrNull()?.let { prefs(app).edit().putString("marketError", "Unable to update. Check your connection.").apply() }
            BitcoinWidget.renderAll(app)
            Notifications.price(app)
            callback?.invoke(result)
        }
    }
}
