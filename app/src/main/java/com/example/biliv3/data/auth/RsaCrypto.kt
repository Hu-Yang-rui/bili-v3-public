package com.example.biliv3.data.auth

import android.util.Base64
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * 密码登录用的 RSA 加密。
 *
 * ## 为什么必须加密
 *
 * B 站的密码登录接口要求提交 **RSA 加密后**的密码（Base64 编码），
 * 明文提交会被拒。公钥由服务端下发（`x/passport-login/web/key`），
 * 且**会轮换**，所以每次登录都要现取、不能缓存。
 *
 * ## 实现细节
 *
 * - 公钥是 PEM 格式（带 `-----BEGIN PUBLIC KEY-----` 头尾），
 *   需要剥掉头尾与换行再 Base64 解码
 * - 用 `RSA/ECB/PKCS1Padding`，与网页端 `jsencrypt` 默认一致
 * - 输出 Base64（标准，非 URL-safe）
 */
object RsaCrypto {

    /**
     * 加密密码。
     *
     * @param plain 明文密码
     * @param publicKeyPem 服务端下发的 PEM 公钥
     * @return Base64 密文；失败返回 null（由调用方转成用户可见错误）
     */
    fun encrypt(plain: String, publicKeyPem: String): String? = runCatching {
        val key = parsePublicKey(publicKeyPem) ?: return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }.getOrNull()

    /**
     * 解析 PEM 公钥。
     *
     * 输入形如：
     * ```
     * -----BEGIN PUBLIC KEY-----
     * MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQ...
     * -----END PUBLIC KEY-----
     * ```
     */
    private fun parsePublicKey(pem: String): java.security.PublicKey? = runCatching {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        val decoded = Base64.decode(body, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(decoded)
        KeyFactory.getInstance("RSA").generatePublic(spec)
    }.getOrNull()

    /**
     * `RSA/ECB/PKCS1Padding` —— 与网页端 jsencrypt 的默认填充一致。
     *
     * 用 OAEP 会导致服务端解密失败（两边填充方式必须匹配）。
     */
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"
}
