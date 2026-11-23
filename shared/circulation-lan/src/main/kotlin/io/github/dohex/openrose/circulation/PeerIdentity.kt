package io.github.dohex.openrose.circulation

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x509.Validity
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal fun File.replaceContents(write: (OutputStream) -> Unit) {
    val temporary = Files.createTempFile(parentFile.toPath(), ".openrose-", ".tmp")
    try {
        Files.newOutputStream(temporary).use(write)
        Files.move(temporary, toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal class PeerIdentity(directory: File) {
    private val store = KeyStore.getInstance("PKCS12").apply {
        val file = File(directory, "identity.p12")
        if (file.exists()) {
            file.inputStream().use { load(it, PASSWORD) }
        } else {
            load(null, PASSWORD)
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            setKeyEntry("node", keys.private, PASSWORD, arrayOf(keys.nodeCertificate()))
            file.replaceContents { store(it, PASSWORD) }
        }
    }
    val id: String = fingerprint(store.getCertificate("node") as X509Certificate)
    val tls: SSLContext = SSLContext.getInstance("TLS").apply {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD) }
        init(keys.keyManagers, arrayOf(NodeCertificateTrust), null)
    }

    companion object {
        private val PASSWORD = "OpenRose".toCharArray()
        private fun fingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun peer(socket: SSLSocket): String {
            socket.startHandshake()
            return fingerprint(socket.session.peerCertificates.single() as X509Certificate)
        }
    }
}

private fun KeyPair.nodeCertificate(): X509Certificate {
    val subject = X500Name("CN=OpenRose")
    val now = System.currentTimeMillis()
    val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
    val certificateBody = V3TBSCertificateGenerator().apply {
        setSerialNumber(ASN1Integer(BigInteger(159, SecureRandom()).setBit(158)))
        setSubject(subject)
        setIssuer(subject)
        setSignature(algorithm)
        setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(public.encoded))
        setValidity(Validity(Time(Date(now - 86_400_000L)), Time(Date(now + 30L * 365 * 86_400_000))))
    }.generateTBSCertificate()
    val signature = Signature.getInstance("SHA256withECDSA").apply {
        initSign(private)
        update(certificateBody.encoded)
    }.sign()
    val certificate = Certificate(certificateBody, algorithm, DERBitString(signature))
    return CertificateFactory.getInstance("X.509").generateCertificate(certificate.encoded.inputStream()) as X509Certificate
}

private object NodeCertificateTrust : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = checkChain(chain)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = checkChain(chain)

    private fun checkChain(chain: Array<X509Certificate>) {
        val cert = chain.singleOrNull() ?: throw CertificateException("流转身份无效")
        cert.checkValidity()
        cert.verify(cert.publicKey)
    }
}
