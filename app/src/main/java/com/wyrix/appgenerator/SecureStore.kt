package com.wyrix.appgenerator

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API keys aur model list phone mein encrypted rehti hain. Kisi server par nahi jaati. */
class SecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wyrix_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun key(provider: String): String = prefs.getString("key_$provider", "") ?: ""

    fun setKey(provider: String, value: String) {
        prefs.edit().putString("key_$provider", value.trim()).apply()
    }

    fun models(): String = prefs.getString("models", null) ?: DefaultModels.TEXT.trim()

    fun setModels(value: String) {
        prefs.edit().putString("models", value).apply()
    }
}
