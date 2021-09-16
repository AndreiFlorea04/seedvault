package com.stevesoltys.seedvault.transport.backup

import android.app.backup.RestoreSet
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

interface BackupPlugin {

    @Deprecated("Use methods in this interface instead")
    val kvBackupPlugin: KVBackupPlugin

    @Deprecated("Use methods in this interface instead")
    val fullBackupPlugin: FullBackupPlugin

    /**
     * Start a new [RestoreSet] with the given token.
     *
     * This is typically followed by a call to [initializeDevice].
     */
    @Throws(IOException::class)
    suspend fun startNewRestoreSet(token: Long)

    /**
     * Initialize the storage for this device, erasing all stored data in the current [RestoreSet].
     */
    @Throws(IOException::class)
    suspend fun initializeDevice()

    /**
     * Return true if there is data stored for the given name.
     */
    @Throws(IOException::class)
    suspend fun hasData(token: Long, name: String): Boolean

    /**
     * Return a raw byte stream for writing data for the given name.
     */
    @Throws(IOException::class)
    suspend fun getOutputStream(token: Long, name: String): OutputStream

    /**
     * Return a raw byte stream with data for the given name.
     */
    @Throws(IOException::class)
    suspend fun getInputStream(token: Long, name: String): InputStream

    /**
     * Remove all data associated with the given name.
     */
    @Throws(IOException::class)
    suspend fun removeData(token: Long, name: String)

    /**
     * Returns an [OutputStream] for writing backup metadata.
     */
    @Throws(IOException::class)
    @Deprecated("use getOutputStream() instead")
    suspend fun getMetadataOutputStream(token: Long): OutputStream

    /**
     * Searches if there's really a backup available in the given location.
     * Returns true if at least one was found and false otherwise.
     *
     * FIXME: Passing a Uri is too plugin-specific and should be handled differently
     */
    @WorkerThread
    @Throws(IOException::class)
    suspend fun hasBackup(uri: Uri): Boolean

    /**
     * Get the set of all backups currently available for restore.
     *
     * @return metadata for the set of restore images available,
     * or null if an error occurred (the attempt should be rescheduled).
     **/
    suspend fun getAvailableBackups(): Sequence<EncryptedMetadata>?

    /**
     * Returns an [OutputStream] for writing an APK to be backed up.
     */
    @Throws(IOException::class)
    @Deprecated("Use getOutputStream() instead")
    suspend fun getApkOutputStream(packageInfo: PackageInfo, suffix: String): OutputStream

    /**
     * Returns the package name of the app that provides the backend storage
     * which is used for the current backup location.
     *
     * Plugins are advised to cache this as it will be requested frequently.
     *
     * @return null if no package name could be found
     */
    val providerPackageName: String?

}

class EncryptedMetadata(val token: Long, val inputStreamRetriever: () -> InputStream)
