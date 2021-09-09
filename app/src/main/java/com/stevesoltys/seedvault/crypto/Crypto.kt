package com.stevesoltys.seedvault.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.MAX_SEGMENT_LENGTH
import com.stevesoltys.seedvault.header.MAX_VERSION_HEADER_SIZE
import com.stevesoltys.seedvault.header.SegmentHeader
import com.stevesoltys.seedvault.header.VersionHeader
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.crypto.StreamCrypto.deriveStreamKey
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

/**
 * A version 1 backup stream uses [AesGcmHkdfStreaming] from the tink library.
 *
 * A version 0 backup stream starts with a version byte followed by an encrypted [VersionHeader].
 *
 * The header will be encrypted with AES/GCM to provide authentication.
 * It can be read using [decryptHeader] which throws a [SecurityException],
 * if the expected version and package name do not match the encrypted header.
 *
 * After the header, follows one or more data segments.
 * Each segment begins with a clear-text [SegmentHeader]
 * that contains the length of the segment
 * and a nonce acting as the initialization vector for the encryption.
 * The segment can be read using [decryptSegment] which throws a [SecurityException],
 * if the length of the segment is specified larger than allowed.
 */
internal interface Crypto {

    /**
     * Returns a [AesGcmHkdfStreaming] encrypting stream
     * that gets encrypted and authenticated the given associated data.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun newEncryptingStream(
        outputStream: OutputStream,
        associatedData: ByteArray
    ): OutputStream

    /**
     * Returns a [AesGcmHkdfStreaming] decrypting stream
     * that gets decrypted and authenticated the given associated data.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun newDecryptingStream(
        inputStream: InputStream,
        associatedData: ByteArray
    ): InputStream

    /**
     * Reads and decrypts a [VersionHeader] from the given [InputStream]
     * and ensures that the expected version, package name and key match
     * what is found in the header.
     * If a mismatch is found, a [SecurityException] is thrown.
     *
     * @return The read [VersionHeader] present in the beginning of the given [InputStream].
     */
    @Deprecated("Use newDecryptingStream instead")
    @Throws(IOException::class, SecurityException::class)
    fun decryptHeader(
        inputStream: InputStream,
        expectedVersion: Byte,
        expectedPackageName: String,
        expectedKey: String? = null
    ): VersionHeader

    /**
     * Reads and decrypts a segment from the given [InputStream].
     *
     * @return The decrypted segment payload.
     */
    @Deprecated("Use newDecryptingStream instead")
    @Throws(EOFException::class, IOException::class, SecurityException::class)
    fun decryptSegment(inputStream: InputStream): ByteArray

    /**
     * Like [decryptSegment], but decrypts multiple segments and does not throw [EOFException].
     */
    @Deprecated("Use newDecryptingStream instead")
    @Throws(IOException::class, SecurityException::class)
    fun decryptMultipleSegments(inputStream: InputStream): ByteArray

    /**
     * Verify that the stored backup key was created from the given seed.
     *
     * @return true if the key was created from given seed, false otherwise.
     */
    fun verifyBackupKey(seed: ByteArray): Boolean
}

internal const val TYPE_METADATA: Byte = 0x00
internal const val TYPE_BACKUP_KV: Byte = 0x01
internal const val TYPE_BACKUP_FULL: Byte = 0x02

internal class CryptoImpl(
    private val keyManager: KeyManager,
    private val cipherFactory: CipherFactory,
    private val headerReader: HeaderReader
) : Crypto {

    private val key: ByteArray by lazy {
        deriveStreamKey(keyManager.getMainKey(), "app data key".toByteArray())
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    override fun newEncryptingStream(
        outputStream: OutputStream,
        associatedData: ByteArray
    ): OutputStream {
        return StreamCrypto.newEncryptingStream(key, outputStream, associatedData)
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    override fun newDecryptingStream(
        inputStream: InputStream,
        associatedData: ByteArray
    ): InputStream {
        return StreamCrypto.newDecryptingStream(key, inputStream, associatedData)
    }

    @Throws(IOException::class, SecurityException::class)
    @Deprecated("Use newDecryptingStream instead")
    override fun decryptHeader(
        inputStream: InputStream,
        expectedVersion: Byte,
        expectedPackageName: String,
        expectedKey: String?
    ): VersionHeader {
        val decrypted = decryptSegment(inputStream, MAX_VERSION_HEADER_SIZE)
        val header = headerReader.getVersionHeader(decrypted)

        if (header.version != expectedVersion) {
            throw SecurityException(
                "Invalid version '${header.version.toInt()}' in header, " +
                    "expected '${expectedVersion.toInt()}'."
            )
        }
        if (header.packageName != expectedPackageName) {
            throw SecurityException(
                "Invalid package name '${header.packageName}' in header, " +
                    "expected '$expectedPackageName'."
            )
        }
        if (header.key != expectedKey) throw SecurityException(
            "Invalid key '${header.key}' in header, expected '$expectedKey'."
        )

        return header
    }

    @Deprecated("Use newDecryptingStream instead")
    @Throws(EOFException::class, IOException::class, SecurityException::class)
    override fun decryptSegment(inputStream: InputStream): ByteArray {
        return decryptSegment(inputStream, MAX_SEGMENT_LENGTH)
    }

    @Deprecated("Use newDecryptingStream instead")
    @Throws(IOException::class, SecurityException::class)
    override fun decryptMultipleSegments(inputStream: InputStream): ByteArray {
        var result = ByteArray(0)
        while (true) {
            try {
                result += decryptSegment(inputStream, MAX_SEGMENT_LENGTH)
            } catch (e: EOFException) {
                if (result.isEmpty()) throw IOException(e)
                return result
            }
        }
    }

    @Throws(EOFException::class, IOException::class, SecurityException::class)
    private fun decryptSegment(inputStream: InputStream, maxSegmentLength: Int): ByteArray {
        val segmentHeader = headerReader.readSegmentHeader(inputStream)
        if (segmentHeader.segmentLength > maxSegmentLength) throw SecurityException(
            "Segment length too long: ${segmentHeader.segmentLength} > $maxSegmentLength"
        )

        val buffer = ByteArray(segmentHeader.segmentLength.toInt())
        val bytesRead = inputStream.read(buffer)
        if (bytesRead == -1) throw EOFException()
        if (bytesRead != buffer.size) throw IOException()
        val cipher = cipherFactory.createDecryptionCipher(segmentHeader.nonce)

        return cipher.doFinal(buffer)
    }

    override fun verifyBackupKey(seed: ByteArray): Boolean {
        // encrypt with stored backup key
        val toEncrypt = "Recovery Code Verification".toByteArray()
        val cipher = cipherFactory.createEncryptionCipher()
        val encrypted = cipher.doFinal(toEncrypt) as ByteArray

        // encrypt with input key cipher
        val secretKeySpec = SecretKeySpec(seed, 0, KEY_SIZE_BYTES, "AES")
        val inputCipher = cipherFactory.createEncryptionTestCipher(secretKeySpec, cipher.iv)
        val inputEncrypted = inputCipher.doFinal(toEncrypt)

        // keys match if encrypted result is the same
        return encrypted.contentEquals(inputEncrypted)
    }

}
