package com.helix

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import org.conscrypt.Conscrypt

// contact blaku64th on discord if you have any issues ^^
class AppAdbConnectionManager constructor(context: Context) : AbsAdbConnectionManager() {

    val privkey = File(context.filesDir, "adb_private.key")
    val certfile = File(context.filesDir, "adb_cert.der")

    lateinit var keyinternal: PrivateKey
    lateinit var certinternal: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        if (privkey.exists() && certfile.exists()) {
            loadExisting()
        } else {
            generateAndPersist()
        }
    }

    fun loadExisting() {
        val keyFactory = KeyFactory.getInstance("RSA")
        keyinternal = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privkey.readBytes()))
        val certFactory = CertificateFactory.getInstance("X.509")
        certinternal = certfile.inputStream().use { certFactory.generateCertificate(it) }
    }

    fun generateAndPersist() {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 1000L * 60 * 60 * 24)
        val notAfter = Date(now + 1000L * 60 * 60 * 24 * 3650)
        val serial = BigInteger.valueOf(now)
        val subject = X500Name("CN=Helix")

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA512withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        privkey.writeBytes(keyPair.private.encoded)
        certfile.writeBytes(cert.encoded)

        keyinternal = keyPair.private
        certinternal = cert
    }

    override fun getPrivateKey(): PrivateKey = keyinternal

    override fun getCertificate(): Certificate = certinternal

    override fun getDeviceName(): String = "Helix"

    fun resetIdentity() {
        privkey.delete()
        certfile.delete()
    }
    fun extractAsset(assetPath: String, outputName: String? = null): File? {
        val context = AppContext.app
        val outFile = File(context.filesDir, outputName ?: assetPath.substringAfterLast('/'))

        return try {
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setExecutable(true)
            outFile
        } catch (e: Exception) {
            null
        }
    }

    fun assetExists(assetPath: String): Boolean {
        return try {
            AppContext.app.assets.open(assetPath).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        var instance: AppAdbConnectionManager? = null

        fun getInstance(context: Context): AppAdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: AppAdbConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}