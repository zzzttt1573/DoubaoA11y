package io.github.doubaoa11y.patch

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 本机持久化签名密钥：首次修补时在私有目录生成并保存，
 * 后续模块升级同步到豆包时复用同一把密钥，可直接覆盖安装、不丢词库和登录态。
 */
object LocalKeystore {
    private const val FILE_NAME = "local_patch.p12"
    private const val ALIAS = "doubaoa11y_local"
    private val STORE_PASS = "doubaoa11y_local_ks".toCharArray()

    data class Material(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
        val certSha256: ByteArray
    )

    private fun storeFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = storeFile(context).isFile

    @Synchronized
    fun loadOrCreate(context: Context): Material {
        val file = storeFile(context)
        if (!java.nio.file.Files.notExists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return try { load(file) } catch (failure: Exception) {
                throw IllegalStateException("已有本机签名密钥无法读取，已保留原文件", failure)
            }
        }
        val created = generate()
        save(file, created)
        return created
    }

    @Synchronized
    fun loadIfExists(context: Context): Material? {
        val file = storeFile(context)
        if (!file.isFile) return null
        return try {
            load(file)
        } catch (_: Throwable) {
            null
        }
    }

    private fun load(file: File): Material {
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, STORE_PASS) }
        val key = ks.getKey(ALIAS, STORE_PASS) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return Material(key, cert, sha256(cert.encoded))
    }

    private fun save(file: File, m: Material) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, m.privateKey, STORE_PASS, arrayOf(m.certificate))
        val tmp = File.createTempFile("local-key-", ".tmp", file.parentFile)
        try {
            tmp.outputStream().use { ks.store(it, STORE_PASS); it.fd.sync() }
            val verified = load(tmp)
            check(verified.certSha256.contentEquals(m.certSha256) &&
                verified.privateKey.encoded.contentEquals(m.privateKey.encoded)) { "签名密钥回读验证失败" }
            // 私有目录同文件系统原子重命名（Android SELinux 禁止 untrusted_app 使用 Files.createLink 硬链接）；
            // loadOrCreate 已加锁并校验目标不存在，此处再次确认不覆盖已有身份。
            check(java.nio.file.Files.notExists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "已有本机签名密钥，拒绝覆盖"
            }
            if (!tmp.renameTo(file)) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath())
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun generate(): Material {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val now = Date()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = now
            add(Calendar.DAY_OF_YEAR, -1) // 避免手机时钟略有偏差导致证书尚未生效
        }
        val notBefore = cal.time
        // 有效期 20 年（落在 UTCTime 范围 <=2049 年），兼顾旧版安卓 X.509 校验器
        cal.add(Calendar.YEAR, 20)
        val notAfter = cal.time

        val serial = BigInteger(63, SecureRandom()).abs().max(BigInteger.ONE)
        val certDer = buildSelfSignedX509(
            subjectCn = "DoubaoA11y Local Patch",
            serial = serial,
            notBefore = notBefore,
            notAfter = notAfter,
            publicKeySpkiDer = kp.public.encoded,
            privateKey = kp.private
        )
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        return Material(kp.private, cert, sha256(cert.encoded))
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // ---------------- 纯 Kotlin ASN.1 DER X.509 v3 自签名证书构造 ----------------

    // OID 1.2.840.113549.1.1.11 (sha256WithRSAEncryption)
    private val OID_SHA256_WITH_RSA = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
    )
    // OID 2.5.4.3 (commonName)
    private val OID_COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
    private val ASN1_NULL = byteArrayOf(0x05, 0x00)

    private fun buildSelfSignedX509(
        subjectCn: String,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
        publicKeySpkiDer: ByteArray,
        privateKey: PrivateKey
    ): ByteArray {
        val algId = seq(OID_SHA256_WITH_RSA, ASN1_NULL)
        val dn = seq(
            set(
                seq(
                    OID_COMMON_NAME,
                    utf8String(subjectCn)
                )
            )
        )
        val validity = seq(derTime(notBefore), derTime(notAfter))
        // [0] EXPLICIT Version ::= INTEGER { v3(2) }
        val versionV3 = tlv(0xA0, integer(BigInteger.valueOf(2)))

        val tbsCertificate = seq(
            versionV3,
            integer(serial),
            algId,
            dn, // issuer
            validity,
            dn, // subject
            publicKeySpkiDer // SubjectPublicKeyInfo (already DER encoded)
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(tbsCertificate)
        val sigBytes = signer.sign()

        return seq(
            tbsCertificate,
            algId,
            bitString(sigBytes)
        )
    }

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(*parts))
    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(*parts))

    private fun integer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())

    private fun utf8String(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun bitString(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size + 1)
        out[0] = 0x00 // unused bits = 0
        System.arraycopy(bytes, 0, out, 1, bytes.size)
        return tlv(0x03, out)
    }

    private fun derTime(date: Date): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }
        val year = cal.get(Calendar.YEAR)
        return if (year in 1950..2049) {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            tlv(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
        } else {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            tlv(0x18, fmt.format(date).toByteArray(Charsets.US_ASCII))
        }
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xFF -> {
                out.write(0x81)
                out.write(len)
            }
            len <= 0xFFFF -> {
                out.write(0x82)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }
}
