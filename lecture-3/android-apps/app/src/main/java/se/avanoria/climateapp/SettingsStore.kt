package se.avanoria.climateapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "climate_app_settings",
        Context.MODE_PRIVATE
    )

    private val keyAlias = "climate_app_mqtt_key"

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(keyAlias, null) as? SecretKey

        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )

        return generator.generateKey()
    }

    fun load(): AppMqttSettings? {
        val encrypted = preferences.getString("mqtt", null) ?: return null
        val parts = encrypted.split(":")

        require(parts.size == 2) {
            "Sparade inställningar kunde inte läsas."
        }

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getKey(),
            GCMParameterSpec(128, iv)
        )

        val json = JSONObject(
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        )

        return AppMqttSettings(
            address = json.getString("address"),
            username = json.getString("username"),
            password = json.getString("password")
        )
    }

    fun save(settings: AppMqttSettings) {
        val json = JSONObject()
            .put("address", settings.address)
            .put("username", settings.username)
            .put("password", settings.password)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val encrypted = cipher.doFinal(
            json.toString().toByteArray(Charsets.UTF_8)
        )

        val value =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)

        check(preferences.edit().putString("mqtt", value).commit()) {
            "Appens inställningar kunde inte sparas."
        }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) {
            "Appens inställningar kunde inte återställas."
        }
    }
}