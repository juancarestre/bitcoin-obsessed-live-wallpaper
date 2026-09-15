package com.bitcoinobsessed.livewallpaper

import android.content.SharedPreferences

/** Preserve Google-linked rules, but never enroll a legacy unowned device ID. */
fun migrateAccountPreferences(prefs: SharedPreferences): Boolean {
    val linkedUid = prefs.getString("accountUid", null) ?: prefs.getString("premiumUid", null)
    val firstAccountMigration = !prefs.getBoolean("accountMigrationV2", false)
    val edit = prefs.edit().remove("premium").remove("premiumUid")
    if (linkedUid != null) {
        edit.putString("accountUid", linkedUid)
    } else {
        // A code-only row has no verified owner and cannot safely be claimed server-side.
        edit.remove("deviceId")
    }
    if (firstAccountMigration) {
        edit.putBoolean("accountMigrationV2", true)
        if (linkedUid == null) {
            edit.putBoolean("alerts", false).putBoolean("priceAlert", false).putBoolean("syncPending", false)
                .remove("syncStatus").remove("lastAlert")
        }
        listOf("installCode", "wallChart", "wallX", "wallY", "textSize", "background", "opacity", "ink", "darkText", "padding")
            .forEach { edit.remove(it) }
    }
    edit.apply()
    return firstAccountMigration && linkedUid == null
}
