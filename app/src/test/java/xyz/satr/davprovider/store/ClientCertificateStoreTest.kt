package xyz.satr.davprovider.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The archive handling underneath [ClientCertificateStoreImpl], on throwaway archives built here:
 * a file that is not a PKCS#12 is told apart from a passphrase that does not fit, and what the app
 * stores is not protected by the passphrase the user typed.
 *
 * Only [Pkcs12] is exercised, which is the half that decides those two things. The rest of the store
 * is account plumbing around an `android.accounts.Account` and a [CredentialStore], neither of which
 * a JVM unit test can stand up.
 */
class ClientCertificateStoreTest {

    @Test
    fun `a file that is not a DER sequence is not blamed on the passphrase`() {
        // The discrimination the user can act on: the wrong file, not a mistyped passphrase.
        assertEquals(
            Pkcs12Content.NotAPkcs12,
            Pkcs12.read("this is a text file, not a PKCS#12".toByteArray(), USER_PASSPHRASE.toCharArray()),
        )
        assertEquals(
            Pkcs12Content.NotAPkcs12,
            Pkcs12.read(ByteArray(0), USER_PASSPHRASE.toCharArray()),
        )
    }

    @Test
    fun `well-formed DER that will not open is the passphrase`() {
        val archive = archiveOf(USER_PASSPHRASE)

        // The structure is a DER SEQUENCE, so the only thing left that can be wrong is the
        // passphrase: PKCS#12 integrity is a MAC over it.
        assertEquals(
            Pkcs12Content.WrongPassphrase,
            Pkcs12.read(archive.bytes, "some-other-passphrase".toCharArray()),
        )
    }

    @Test
    fun `the user's passphrase yields the key and its chain`() {
        val archive = archiveOf(USER_PASSPHRASE)

        val content = Pkcs12.read(archive.bytes, USER_PASSPHRASE.toCharArray())

        assertTrue(content is Pkcs12Content.Identity)
        val stored = content as Pkcs12Content.Identity
        assertArrayEquals(archive.keyPair.private.encoded, stored.privateKey.encoded)
        assertArrayEquals(arrayOf(archive.certificate), stored.chain)
    }

    @Test
    fun `an archive of certificates alone has no private key`() {
        val keyPair = keyPair()
        val keyStore = newKeyStore(USER_PASSPHRASE)
        keyStore.setCertificateEntry(ALIAS, selfSigned(keyPair))

        assertEquals(
            Pkcs12Content.NoPrivateKey,
            Pkcs12.read(bytesOf(keyStore, USER_PASSPHRASE), USER_PASSPHRASE.toCharArray()),
        )
    }

    @Test
    fun `an archive is re-wrapped under a passphrase the user never typed`() {
        val archive = archiveOf(USER_PASSPHRASE)
        val content = Pkcs12.read(archive.bytes, USER_PASSPHRASE.toCharArray()) as Pkcs12Content.Identity

        val generated = Pkcs12.newPassphrase(SecureRandom())
        val rewrapped = Pkcs12.rewrap(content, generated)

        // Printable ASCII is a constraint of the PKCS#12 encoders, which refuse anything else, so a
        // passphrase outside it could not be written at all.
        assertTrue(generated.all { it in ' '..'~' })
        assertNotEquals(USER_PASSPHRASE, generated.concatToString())
        // The point of re-wrapping: the passphrase the user typed opens nothing this app stores.
        assertEquals(
            Pkcs12Content.WrongPassphrase,
            Pkcs12.read(rewrapped, USER_PASSPHRASE.toCharArray()),
        )

        val stored = Pkcs12.read(rewrapped, generated)
        assertTrue(stored is Pkcs12Content.Identity)
        assertArrayEquals(archive.keyPair.private.encoded, (stored as Pkcs12Content.Identity).privateKey.encoded)
        assertArrayEquals(arrayOf(archive.certificate), stored.chain)
    }

    @Test
    fun `an expired certificate is described as expired and still read`() {
        val now = System.currentTimeMillis()
        val archive = archiveOf(USER_PASSPHRASE, notBefore = now - 2 * DAY, notAfter = now - DAY)

        val content = Pkcs12.read(archive.bytes, USER_PASSPHRASE.toCharArray())

        // Only the server decides whether it still accepts the certificate, so an expired one is
        // reported and never refused here.
        assertTrue(content is Pkcs12Content.Identity)
        val info = Pkcs12.describe((content as Pkcs12Content.Identity).chain, now)
        assertTrue(info.expired)
        assertEquals(archive.certificate.notAfter.time, info.notAfter)
    }
}

private const val USER_PASSPHRASE = "the-passphrase-the-user-typed"
private const val ALIAS = "throwaway"
private const val DAY = 86_400_000L
private const val KEY_SIZE = 2048
private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"

/** A throwaway archive and the key pair in it, so a test can say what reading it should return. */
private class ThrowawayArchive(
    val bytes: ByteArray,
    val keyPair: KeyPair,
    val certificate: X509Certificate,
)

private fun archiveOf(
    passphrase: String,
    notBefore: Long = validFrom(),
    notAfter: Long = validUntil(),
): ThrowawayArchive {
    val keyPair = keyPair()
    val certificate = selfSigned(keyPair, notBefore, notAfter)
    val keyStore = newKeyStore(passphrase)
    keyStore.setKeyEntry(ALIAS, keyPair.private, passphrase.toCharArray(), arrayOf(certificate))
    return ThrowawayArchive(bytesOf(keyStore, passphrase), keyPair, certificate)
}

private fun newKeyStore(passphrase: String): KeyStore =
    KeyStore.getInstance("PKCS12").apply { load(null, passphrase.toCharArray()) }

private fun bytesOf(keyStore: KeyStore, passphrase: String): ByteArray =
    ByteArrayOutputStream().use { out ->
        keyStore.store(out, passphrase.toCharArray())
        out.toByteArray()
    }

private fun keyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }.generateKeyPair()

private fun validFrom(): Long = System.currentTimeMillis() - DAY

private fun validUntil(): Long = System.currentTimeMillis() + DAY

/**
 * A self-signed certificate, assembled as DER here because the JDK has no API that issues one and
 * a certificate authority is a large dependency for two tests. The signature is a real one: the
 * platform's own [CertificateFactory] is what reads the result back.
 */
private fun selfSigned(
    keyPair: KeyPair,
    notBefore: Long = validFrom(),
    notAfter: Long = validUntil(),
): X509Certificate {
    val algorithm = sequence(oid(SHA256_WITH_RSA), nullValue())
    val name = X500Principal("CN=throwaway").encoded
    val tbsCertificate = sequence(
        integer(BigInteger.ONE),
        algorithm,
        name,
        sequence(utcTime(notBefore), utcTime(notAfter)),
        name,
        keyPair.public.encoded,
    )
    val signature = Signature.getInstance("SHA256withRSA").apply {
        initSign(keyPair.private)
        update(tbsCertificate)
    }.sign()
    val der = sequence(tbsCertificate, algorithm, bitString(signature))
    return CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
}

private fun sequence(vararg parts: ByteArray): ByteArray = tagged(0x30, *parts)

private fun integer(value: BigInteger): ByteArray = tagged(0x02, value.toByteArray())

private fun bitString(value: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0), value)

private fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

private fun utcTime(millis: Long): ByteArray {
    val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return tagged(0x17, format.format(Date(millis)).toByteArray(Charsets.US_ASCII))
}

private fun oid(dotted: String): ByteArray {
    val arcs = dotted.split('.').map { it.toLong() }
    val body = ByteArrayOutputStream()
    body.write((arcs[0] * 40 + arcs[1]).toInt())
    for (arc in arcs.drop(2)) {
        var shift = 0
        while (arc shr (shift + 7) != 0L) shift += 7
        while (shift > 0) {
            body.write((((arc shr shift) and 0x7F).toInt() or 0x80))
            shift -= 7
        }
        body.write((arc and 0x7F).toInt())
    }
    return tagged(0x06, body.toByteArray())
}

private fun tagged(tag: Int, vararg parts: ByteArray): ByteArray {
    val size = parts.sumOf { it.size }
    val out = ByteArray(1 + length(size).size + size)
    out[0] = tag.toByte()
    length(size).copyInto(out, 1)
    var at = 1 + length(size).size
    for (part in parts) {
        part.copyInto(out, at)
        at += part.size
    }
    return out
}

private fun length(size: Int): ByteArray = when {
    size < 0x80 -> byteArrayOf(size.toByte())
    size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
    else -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
}
