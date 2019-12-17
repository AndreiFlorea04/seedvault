package com.stevesoltys.seedvault.metadata

import android.os.Build
import com.stevesoltys.seedvault.header.VERSION
import java.io.InputStream

data class BackupMetadata(
        internal val version: Byte = VERSION,
        internal val token: Long,
        internal val time: Long = System.currentTimeMillis(),
        internal val androidVersion: Int = Build.VERSION.SDK_INT,
        internal val androidIncremental: String = Build.VERSION.INCREMENTAL,
        internal val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        internal val packageMetadata: Map<String, PackageMetadata> = HashMap()
)

internal const val JSON_METADATA = "@meta@"
internal const val JSON_METADATA_VERSION = "version"
internal const val JSON_METADATA_TOKEN = "token"
internal const val JSON_METADATA_TIME = "time"
internal const val JSON_METADATA_SDK_INT = "sdk_int"
internal const val JSON_METADATA_INCREMENTAL = "incremental"
internal const val JSON_METADATA_NAME = "name"

data class PackageMetadata(
        internal val time: Long,
        internal val version: Long? = null,
        internal val installer: String? = null,
        internal val signatures: List<String>? = null
) {
    fun hasApk(): Boolean {
        return version != null && signatures != null
    }
}

internal const val JSON_PACKAGE_TIME = "time"
internal const val JSON_PACKAGE_VERSION = "version"
internal const val JSON_PACKAGE_INSTALLER = "installer"
internal const val JSON_PACKAGE_SIGNATURES = "signatures"

internal class DecryptionFailedException(cause: Throwable) : Exception(cause)

class EncryptedBackupMetadata private constructor(
        val token: Long,
        val inputStream: InputStream?,
        val error: Boolean) {

    constructor(token: Long, inputStream: InputStream) : this(token, inputStream, false)
    /**
     * Indicates that there was an error retrieving the encrypted backup metadata.
     */
    constructor(token: Long) : this(token, null, true)
}
