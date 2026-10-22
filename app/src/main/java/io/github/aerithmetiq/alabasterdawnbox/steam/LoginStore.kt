package io.github.aerithmetiq.alabasterdawnbox.steam

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The saved Steam login and profile. The refresh token and Steam Guard data are secrets, so they are
 * encrypted with an AES key that lives in the Android Keystore and never leaves it; the rest (account
 * and persona name, avatar hash) is plain. If the key is gone (a restore onto a new device, a wiped
 * keystore), the login simply reads as absent and the user signs in again.
 */
class LoginStore(context: Context) {

    private val prefs = context.getSharedPreferences("steam_login", Context.MODE_PRIVATE)

    fun load(): SteamLogin? {
        val account = prefs.getString(KEY_ACCOUNT, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.let { decrypt(it) } ?: return null
        val guard = prefs.getString(KEY_GUARD, null)?.let { decrypt(it) }
        return SteamLogin(account, token, guard)
    }

    fun save(login: SteamLogin) {
        val edit = prefs.edit()
            .putString(KEY_ACCOUNT, login.accountName)
            .putString(KEY_TOKEN, encrypt(login.refreshToken))
        val guard = login.guardData
        if (guard != null) edit.putString(KEY_GUARD, encrypt(guard)) else edit.remove(KEY_GUARD)
        edit.apply()
    }

    /** Signs out, but keeps the Steam Guard data so the next password sign-in skips the email code. */
    fun clearLogin() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_PERSONA).remove(KEY_AVATAR).apply()
    }

    /** The last account name, to prefill the username field after a sign-out. */
    val lastAccount: String? get() = prefs.getString(KEY_ACCOUNT, null)

    /** Steam Guard data from an earlier sign-in of [account], if any. */
    fun guardDataFor(account: String): String? =
        if (prefs.getString(KEY_ACCOUNT, null).equals(account, ignoreCase = true)) {
            prefs.getString(KEY_GUARD, null)?.let { decrypt(it) }
        } else null

    var persona: String?
        get() = prefs.getString(KEY_PERSONA, null)
        set(v) = prefs.edit().putString(KEY_PERSONA, v).apply()

    var avatarHash: String?
        get() = prefs.getString(KEY_AVATAR, null)
        set(v) = prefs.edit().putString(KEY_AVATAR, v).apply()

    /* ---------------------------------------------------------------------- crypto ---- */

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    /** `base64(iv || ciphertext)`; the IV is the 12 bytes the Keystore picks. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "alabasterdawnbox-steam-login"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12

        const val KEY_ACCOUNT = "account"
        const val KEY_TOKEN = "token"
        const val KEY_GUARD = "guard"
        const val KEY_PERSONA = "persona"
        const val KEY_AVATAR = "avatar"
    }
}
