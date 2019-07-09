package com.stevesoltys.backup.transport.restore

import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import com.stevesoltys.backup.getRandomByteArray
import com.stevesoltys.backup.header.UnsupportedVersionException
import com.stevesoltys.backup.header.Utf8
import com.stevesoltys.backup.header.VERSION
import com.stevesoltys.backup.header.VersionHeader
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.util.Base64.getUrlEncoder
import kotlin.random.Random

internal class KVRestoreTest : RestoreTest() {

    private val plugin = mockk<KVRestorePlugin>()
    private val output = mockk<BackupDataOutput>()
    private val restore = KVRestore(plugin, outputFactory, headerReader, crypto)

    private val key = "Restore Key"
    private val key64 = getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Utf8))
    private val versionHeader = VersionHeader(VERSION, packageInfo.packageName, key)
    private val key2 = "Restore Key2"
    private val key264 = getUrlEncoder().withoutPadding().encodeToString(key2.toByteArray(Utf8))
    private val versionHeader2 = VersionHeader(VERSION, packageInfo.packageName, key2)

    @Test
    fun `hasDataForPackage() delegates to plugin`() {
        val result = Random.nextBoolean()

        every { plugin.hasDataForPackage(token, packageInfo) } returns result

        assertEquals(result, restore.hasDataForPackage(token, packageInfo))
    }

    @Test
    fun `getRestoreData() throws without initializing state`() {
        assertThrows(IllegalStateException::class.java) {
            restore.getRestoreData(fileDescriptor)
        }
    }

    @Test
    fun `listing records throws`() {
        restore.initializeState(token, packageInfo)

        every { plugin.listRecords(token, packageInfo) } throws IOException()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
    }

    @Test
    fun `reading VersionHeader with unsupported version throws`() {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } throws UnsupportedVersionException(unsupportedVersion)
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `error reading VersionHeader throws`() {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `decrypting segment throws`() {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName, key) } returns versionHeader
        every { crypto.decryptSegment(inputStream) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `decrypting header throws`() {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName, key) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `decrypting header throws security exception`() {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName, key) } throws SecurityException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `writing header throws`() {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName, key) } returns versionHeader
        every { crypto.decryptSegment(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `writing value throws`() {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName, key) } returns versionHeader
        every { crypto.decryptSegment(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } throws IOException()
        streamsGetClosed()

        assertEquals(TRANSPORT_ERROR, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `writing value succeeds`() {
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput()
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName, key) } returns versionHeader
        every { crypto.decryptSegment(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } returns data.size
        streamsGetClosed()

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
        verifyStreamWasClosed()
    }

    @Test
    fun `writing two values succeeds`() {
        val data2 = getRandomByteArray()
        val inputStream2 = mockk<InputStream>()
        restore.initializeState(token, packageInfo)

        getRecordsAndOutput(listOf(key64, key264))
        // first key/value
        every { plugin.getInputStreamForRecord(token, packageInfo, key64) } returns inputStream
        every { headerReader.readVersion(inputStream) } returns VERSION
        every { crypto.decryptHeader(inputStream, VERSION, packageInfo.packageName, key) } returns versionHeader
        every { crypto.decryptSegment(inputStream) } returns data
        every { output.writeEntityHeader(key, data.size) } returns 42
        every { output.writeEntityData(data, data.size) } returns data.size
        // second key/value
        every { plugin.getInputStreamForRecord(token, packageInfo, key264) } returns inputStream2
        every { headerReader.readVersion(inputStream2) } returns VERSION
        every { crypto.decryptHeader(inputStream2, VERSION, packageInfo.packageName, key2) } returns versionHeader2
        every { crypto.decryptSegment(inputStream2) } returns data2
        every { output.writeEntityHeader(key2, data2.size) } returns 42
        every { output.writeEntityData(data2, data2.size) } returns data2.size
        every { inputStream2.close() } just Runs
        streamsGetClosed()

        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))
    }

    private fun getRecordsAndOutput(recordKeys: List<String> = listOf(key64)) {
        every { plugin.listRecords(token, packageInfo) } returns recordKeys
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns output
    }

    private fun streamsGetClosed() {
        every { inputStream.close() } just Runs
        every { fileDescriptor.close() } just Runs
    }

    private fun verifyStreamWasClosed() {
        verifyAll {
            inputStream.close()
            fileDescriptor.close()
        }
    }

}
