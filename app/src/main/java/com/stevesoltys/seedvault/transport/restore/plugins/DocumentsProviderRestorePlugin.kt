package com.stevesoltys.seedvault.transport.restore.plugins

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.metadata.EncryptedBackupMetadata
import com.stevesoltys.seedvault.transport.backup.plugins.*
import com.stevesoltys.seedvault.transport.restore.FullRestorePlugin
import com.stevesoltys.seedvault.transport.restore.KVRestorePlugin
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import java.io.IOException

private val TAG = DocumentsProviderRestorePlugin::class.java.simpleName

class DocumentsProviderRestorePlugin(private val storage: DocumentsStorage) : RestorePlugin {

    override val kvRestorePlugin: KVRestorePlugin by lazy {
        DocumentsProviderKVRestorePlugin(storage)
    }

    override val fullRestorePlugin: FullRestorePlugin by lazy {
        DocumentsProviderFullRestorePlugin(storage)
    }

    override fun getAvailableBackups(context: Context): Sequence<EncryptedBackupMetadata>? {
        val rootDir = storage.rootBackupDir ?: return null
        val backupSets = getBackups(context, rootDir)
        val iterator = backupSets.iterator()
        return generateSequence {
            if (!iterator.hasNext()) return@generateSequence null  // end sequence
            val backupSet = iterator.next()
            try {
                val stream = storage.getInputStream(backupSet.metadataFile)
                EncryptedBackupMetadata(backupSet.token, stream)
            } catch (e: IOException) {
                Log.e(TAG, "Error getting InputStream for backup metadata.", e)
                EncryptedBackupMetadata(backupSet.token)
            }
        }
    }

    companion object {
        @WorkerThread
        fun getBackups(context: Context, rootDir: DocumentFile): List<BackupSet> {
            val backupSets = ArrayList<BackupSet>()
            val files = try {
                // block until the DocumentsProvider has results
                rootDir.listFilesBlocking(context)
            } catch (e: IOException) {
                Log.e(TAG, "Error loading backups from storage", e)
                return backupSets
            }
            for (set in files) {
                if (!set.isDirectory || set.name == null) {
                    if (set.name != FILE_NO_MEDIA) {
                        Log.w(TAG, "Found invalid backup set folder: ${set.name}")
                    }
                    continue
                }
                val token = try {
                    set.name!!.toLong()
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Found invalid backup set folder: ${set.name}")
                    continue
                }
                // block until children of set are available
                val metadata = set.findFileBlocking(context, FILE_BACKUP_METADATA)
                if (metadata == null) {
                    Log.w(TAG, "Missing metadata file in backup set folder: ${set.name}")
                } else {
                    backupSets.add(BackupSet(token, metadata))
                }
            }
            return backupSets
        }
    }

}

class BackupSet(val token: Long, val metadataFile: DocumentFile)
