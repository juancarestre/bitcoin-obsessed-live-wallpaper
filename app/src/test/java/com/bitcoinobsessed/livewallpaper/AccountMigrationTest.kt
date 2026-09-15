package com.bitcoinobsessed.livewallpaper

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AccountMigrationTest {
    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString", "putBoolean" -> { values[args[0] as String] = args[1]; editor }
                "remove" -> { values.remove(args[0] as String); editor }
                "apply" -> null
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean" -> values[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences operation: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun linkedUpgradePreservesEnabledRulesEvenWithoutMigrationFlag() {
        val data = mutableMapOf<String, Any>("premiumUid" to "google-user", "deviceId" to "owned-device", "alerts" to true, "priceAlert" to true, "syncPending" to true)
        assertFalse(migrateAccountPreferences(preferences(data)))
        assertEquals("google-user", data["accountUid"])
        assertEquals("owned-device", data["deviceId"])
        assertEquals(true, data["alerts"])
        assertEquals(true, data["priceAlert"])
        assertEquals(true, data["syncPending"])
        assertFalse(data.containsKey("premiumUid"))
    }

    @Test fun codeOnlyUpgradeDropsUnclaimableDeviceAndRequiresNewEnrollment() {
        val data = mutableMapOf<String, Any>("deviceId" to "unowned-device", "alerts" to true, "installCode" to "old-code")
        assertTrue(migrateAccountPreferences(preferences(data)))
        assertFalse(data.containsKey("deviceId"))
        assertFalse(data.containsKey("installCode"))
        assertEquals(false, data["alerts"])
    }

    @Test fun existingGoogleSessionIsIdempotent() {
        val data = mutableMapOf<String, Any>("accountUid" to "current-user", "deviceId" to "owned-device", "alerts" to true, "accountMigrationV2" to true)
        val before = data.toMap()
        val prefs = preferences(data)
        assertFalse(migrateAccountPreferences(prefs))
        assertFalse(migrateAccountPreferences(prefs))
        assertEquals(before, data)
    }
}
