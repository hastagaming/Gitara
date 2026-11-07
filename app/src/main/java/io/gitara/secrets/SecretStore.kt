package io.gitara.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "secrets")

    fun names(scope: String): List<String> = synchronized(LOCK) { load(scope).keys.sorted() }

    fun put(scope: String, name: String, value: String) {
        require(isValidName(name)) { "Use letters, digits and underscores, not starting with a digit or GITARA_." }
        require(value.isNotEmpty()) { "The secret value is empty." }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_VALUE_BYTES) { "A secret can be at most 48 KB." }
        synchronized(LOCK) {
            val map = load(scope)
            map[name] = value
            save(scope, map)
        }
    }

    fun remove(scope: String, name: String) {
        synchronized(LOCK) {
            val map = load(scope)
            if (map.remove(name) != null) save(scope, map)
        }
    }

    fun resolve(repoId: Int): Map<String, String> = synchronized(LOCK) {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(load(GLOBAL))
        merged.putAll(load(repoScope(repoId)))
        merged
    }

    private fun file(scope: String) = File(dir, "$scope.bin")

    private fun load(scope: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val file = file(scope)
        if (!file.isFile) return result
        try {
            val raw = file.readBytes()
            val ivLength = raw[0].toInt() and 0xFF
            val iv = raw.copyOfRange(1, 1 + ivLength)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(raw, 1 + ivLength, raw.size - 1 - ivLength)
            val json = JSONObject(String(plain, Charsets.UTF_8))
            val keys = json.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                result[name] = json.getString(name)
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not read the secrets for scope $scope")
        }
        return result
    }

    private fun save(scope: String, map: Map<String, String>) {
        dir.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(JSONObject(map).toString().toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + encrypted.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(encrypted, 0, out, 1 + iv.size, encrypted.size)
        val target = file(scope)
        val temp = File(dir, "$scope.tmp")
        temp.writeBytes(out)
        if (!temp.renameTo(target)) {
            target.writeBytes(out)
            temp.delete()
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val GLOBAL = "global"
        private const val ALIAS = "gitara_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_VALUE_BYTES = 48 * 1024
        private val LOCK = Any()
        private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        fun repoScope(repoId: Int): String = "repo-$repoId"

        fun isValidName(name: String): Boolean =
            NAME.matches(name) && !name.uppercase().startsWith("GITARA_")
    }
}