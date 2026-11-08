package com.tungsten.fcl.dfy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 大肥鱼启动器：API Key 落盘加密。
 *
 * 主密钥由 Android Keystore 在 TEE / StrongBox 内生成，密钥材料不出安全硬件，
 * 应用进程即使被调试也拿不到明文主密钥；落盘内容只有 AES-256-GCM 密文（IV + 密文 + tag）。
 * 清除应用数据或换机后密文无法再解开，只会重新要求填写 API Key（不会泄露旧值）。
 * 全程使用系统能力，不引入任何第三方加密库。
 */
object DfyAiCrypto {

    private const val ALIAS = "dfy_ai_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun masterKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密；失败时返回空串，调用方应按“未保存”处理，绝不回退成明文落盘 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(1 + iv.size + body.size)
            packed[0] = iv.size.toByte()
            System.arraycopy(iv, 0, packed, 1, iv.size)
            System.arraycopy(body, 0, packed, 1 + iv.size, body.size)
            Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /** 解密；失败（例如换设备、数据被迁移）时返回空串 */
    fun decrypt(data: String): String {
        if (data.isEmpty()) return ""
        return try {
            val packed = Base64.decode(data, Base64.NO_WRAP)
            val ivLength = packed[0].toInt()
            if (ivLength <= 0 || packed.size <= 1 + ivLength) return ""
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val body = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
