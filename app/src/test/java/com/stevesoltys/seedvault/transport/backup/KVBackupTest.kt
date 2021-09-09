package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupDataInput
import android.app.backup.BackupTransport.FLAG_DATA_NOT_CHANGED
import android.app.backup.BackupTransport.FLAG_INCREMENTAL
import android.app.backup.BackupTransport.FLAG_NON_INCREMENTAL
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.header.MAX_KEY_LENGTH_SIZE
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.getADForKV
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64
import kotlin.random.Random

@Suppress("BlockingMethodInNonBlockingContext")
internal class KVBackupTest : BackupTest() {

    private val plugin = mockk<KVBackupPlugin>()
    private val dataInput = mockk<BackupDataInput>()
    private val notificationManager = mockk<BackupNotificationManager>()

    private val backup = KVBackup(
        plugin = plugin,
        settingsManager = settingsManager,
        inputFactory = inputFactory,
        crypto = crypto,
        nm = notificationManager
    )

    private val key = getRandomString(MAX_KEY_LENGTH_SIZE)
    private val key64 = Base64.getEncoder().encodeToString(key.toByteArray(Utf8))
    private val dataValue = Random.nextBytes(23)

    @Test
    fun `has no initial state`() {
        assertFalse(backup.hasState())
    }

    @Test
    fun `simple backup with one record`() = runBlocking {
        singleRecordBackup()

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `@pm@ backup shows notification`() = runBlocking {
        // init plugin and give back two keys
        initPlugin(true, pmPackageInfo)
        createBackupDataInput()
        every { dataInput.readNextHeader() } returnsMany listOf(true, true, false)
        every { dataInput.key } returnsMany listOf("key1", "key2")
        // we don't care about values, so just use the same one always
        every { dataInput.dataSize } returns dataValue.size
        every { dataInput.readEntityData(any(), 0, dataValue.size) } returns dataValue.size

        // store first record and show notification for it
        every { notificationManager.onPmKvBackup("key1", 1, 2) } just Runs
        coEvery { plugin.getOutputStreamForRecord(pmPackageInfo, "a2V5MQ") } returns outputStream
        every { outputStream.write(ByteArray(1) { VERSION }) } just Runs

        // store second record and show notification for it
        every { notificationManager.onPmKvBackup("key2", 2, 2) } just Runs
        coEvery { plugin.getOutputStreamForRecord(pmPackageInfo, "a2V5Mg") } returns outputStream

        // encrypt to and close output stream
        every { crypto.newEncryptingStream(outputStream, any()) } returns encryptedOutputStream
        every { encryptedOutputStream.write(any<ByteArray>()) } just Runs
        every { encryptedOutputStream.flush() } just Runs
        every { encryptedOutputStream.close() } just Runs
        every { outputStream.flush() } just Runs
        every { outputStream.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(pmPackageInfo, data, 0))
        assertTrue(backup.hasState())

        every { plugin.packageFinished(pmPackageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())

        // verify that notifications were shown
        verifyOrder {
            notificationManager.onPmKvBackup("key1", 1, 2)
            notificationManager.onPmKvBackup("key2", 2, 2)
        }
    }

    @Test
    fun `incremental backup with no data gets rejected`() = runBlocking {
        coEvery { plugin.hasDataForPackage(packageInfo) } returns false
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(
            TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED,
            backup.performBackup(packageInfo, data, FLAG_INCREMENTAL)
        )
        assertFalse(backup.hasState())
    }

    @Test
    fun `check for existing data throws exception`() = runBlocking {
        coEvery { plugin.hasDataForPackage(packageInfo) } throws IOException()
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `non-incremental backup with data clears old data first`() = runBlocking {
        singleRecordBackup(true)
        coEvery { plugin.removeDataOfPackage(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `ignoring exception when clearing data when non-incremental backup has data`() =
        runBlocking {
            singleRecordBackup(true)
            coEvery { plugin.removeDataOfPackage(packageInfo) } throws IOException()

            assertEquals(
                TRANSPORT_OK,
                backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL)
            )
            assertTrue(backup.hasState())
            assertEquals(TRANSPORT_OK, backup.finishBackup())
            assertFalse(backup.hasState())
        }

    @Test
    fun `package with no new data comes back ok right away`() = runBlocking {
        every { data.close() } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, FLAG_DATA_NOT_CHANGED))
        assertTrue(backup.hasState())

        verify { data.close() }

        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while reading next header`() = runBlocking {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } throws IOException()
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while reading value`() = runBlocking {
        initPlugin(false)
        createBackupDataInput()
        every { dataInput.readNextHeader() } returns true
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns dataValue.size
        every { dataInput.readEntityData(any(), 0, dataValue.size) } throws IOException()
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())
    }

    @Test
    fun `no data records`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(false))
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    @Test
    fun `exception while writing version`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true))
        coEvery { plugin.getOutputStreamForRecord(packageInfo, key64) } returns outputStream
        every { outputStream.write(ByteArray(1) { VERSION }) } throws IOException()
        every { outputStream.close() } just Runs
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())

        verify { outputStream.close() }
    }

    @Test
    fun `exception while writing encrypted value to output stream`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true))
        writeVersionAndEncrypt()
        every { encryptedOutputStream.write(dataValue) } throws IOException()
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())

        verify { outputStream.close() }
    }

    @Test
    fun `exception while flushing output stream`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true))
        writeVersionAndEncrypt()
        every { encryptedOutputStream.write(dataValue) } just Runs
        every { encryptedOutputStream.flush() } throws IOException()
        every { encryptedOutputStream.close() } just Runs
        every { outputStream.close() } just Runs
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_ERROR, backup.performBackup(packageInfo, data, 0))
        assertFalse(backup.hasState())

        verify { outputStream.close() }
    }

    @Test
    fun `ignoring exception while closing output stream`() = runBlocking {
        initPlugin(false)
        getDataInput(listOf(true, false))
        writeVersionAndEncrypt()
        every { encryptedOutputStream.write(dataValue) } just Runs
        every { encryptedOutputStream.flush() } just Runs
        every { encryptedOutputStream.close() } just Runs
        every { outputStream.close() } just Runs
        every { plugin.packageFinished(packageInfo) } just Runs

        assertEquals(TRANSPORT_OK, backup.performBackup(packageInfo, data, 0))
        assertTrue(backup.hasState())
        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertFalse(backup.hasState())
    }

    private fun singleRecordBackup(hasDataForPackage: Boolean = false) {
        initPlugin(hasDataForPackage)
        getDataInput(listOf(true, false))
        writeVersionAndEncrypt()
        every { encryptedOutputStream.write(dataValue) } just Runs
        every { encryptedOutputStream.flush() } just Runs
        every { encryptedOutputStream.close() } just Runs
        every { outputStream.close() } just Runs
        every { plugin.packageFinished(packageInfo) } just Runs
    }

    private fun initPlugin(hasDataForPackage: Boolean = false, pi: PackageInfo = packageInfo) {
        coEvery { plugin.hasDataForPackage(pi) } returns hasDataForPackage
    }

    private fun createBackupDataInput() {
        every { inputFactory.getBackupDataInput(data) } returns dataInput
    }

    private fun getDataInput(returnValues: List<Boolean>) {
        createBackupDataInput()
        every { dataInput.readNextHeader() } returnsMany returnValues
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns dataValue.size
        val slot = CapturingSlot<ByteArray>()
        every { dataInput.readEntityData(capture(slot), 0, dataValue.size) } answers {
            dataValue.copyInto(slot.captured)
            dataValue.size
        }
    }

    private fun writeVersionAndEncrypt() {
        coEvery { plugin.getOutputStreamForRecord(packageInfo, key64) } returns outputStream
        every { outputStream.write(ByteArray(1) { VERSION }) } just Runs
        val ad = getADForKV(VERSION, packageInfo.packageName)
        every { crypto.newEncryptingStream(outputStream, ad) } returns encryptedOutputStream
    }

}
