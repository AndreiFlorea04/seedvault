package com.stevesoltys.backup.transport.component.provider.restore;

import android.app.backup.BackupDataOutput;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.ContentResolver;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;
import com.android.internal.util.Preconditions;
import com.stevesoltys.backup.transport.component.RestoreComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;
import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.*;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static android.app.backup.BackupTransport.*;
import static android.app.backup.RestoreDescription.TYPE_FULL_STREAM;
import static android.app.backup.RestoreDescription.TYPE_KEY_VALUE;
import static com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration.FULL_BACKUP_DIRECTORY;
import static com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration.INCREMENTAL_BACKUP_DIRECTORY;

/**
 * @author Steve Soltys
 */
public class ContentProviderRestoreComponent implements RestoreComponent {

    private static final String TAG = ContentProviderRestoreComponent.class.getName();

    private static final int DEFAULT_RESTORE_SET = 1;

    private static final int DEFAULT_BUFFER_SIZE = 2048;

    private ContentProviderBackupConfiguration configuration;

    private ContentProviderRestoreState restoreState;

    public ContentProviderRestoreComponent(ContentProviderBackupConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public int startRestore(long token, PackageInfo[] packages) {
        restoreState = new ContentProviderRestoreState();
        restoreState.setPackages(packages);
        restoreState.setPackageIndex(-1);
        return TRANSPORT_OK;
    }

    @Override
    public RestoreDescription nextRestorePackage() {
        Preconditions.checkNotNull(restoreState, "startRestore() not called");

        int packageIndex = restoreState.getPackageIndex();
        PackageInfo[] packages = restoreState.getPackages();

        while (++packageIndex < packages.length) {
            restoreState.setPackageIndex(packageIndex);
            String name = packages[packageIndex].packageName;

            try {
                if (containsPackageFile(INCREMENTAL_BACKUP_DIRECTORY + name)) {
                    restoreState.setRestoreType(TYPE_KEY_VALUE);
                    return new RestoreDescription(name, restoreState.getRestoreType());

                } else if (containsPackageFile(FULL_BACKUP_DIRECTORY + name)) {
                    restoreState.setRestoreType(TYPE_FULL_STREAM);
                    return new RestoreDescription(name, restoreState.getRestoreType());
                }

            } catch (IOException | InvalidKeyException | InvalidAlgorithmParameterException ex) {
                Log.e(TAG, "Error choosing package  " + name + "  at index " + packageIndex + "failed selection:", ex);
            }
        }
        return RestoreDescription.NO_MORE_PACKAGES;
    }

    private boolean containsPackageFile(String fileName) throws IOException, InvalidKeyException,
            InvalidAlgorithmParameterException {
        ParcelFileDescriptor inputFileDescriptor = buildInputFileDescriptor();
        ZipInputStream inputStream = buildInputStream(inputFileDescriptor);

        Optional<ZipEntry> zipEntry = seekToEntry(inputStream, fileName);
        IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
        return zipEntry.isPresent();
    }

    private ParcelFileDescriptor buildInputFileDescriptor() throws FileNotFoundException {
        ContentResolver contentResolver = configuration.getContext().getContentResolver();
        return contentResolver.openFileDescriptor(configuration.getUri(), "r");
    }

    private ZipInputStream buildInputStream(ParcelFileDescriptor inputFileDescriptor) throws FileNotFoundException {
        FileInputStream fileInputStream = new FileInputStream(inputFileDescriptor.getFileDescriptor());
        return new ZipInputStream(fileInputStream);
    }

    private Optional<ZipEntry> seekToEntry(ZipInputStream inputStream, String entryPath) throws IOException {
        ZipEntry zipEntry;
        while ((zipEntry = inputStream.getNextEntry()) != null) {

            if (zipEntry.getName().startsWith(entryPath)) {
                return Optional.of(zipEntry);
            }
            inputStream.closeEntry();
        }

        return Optional.empty();
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outputFileDescriptor) {
        Preconditions.checkState(restoreState != null, "startRestore() not called");
        Preconditions.checkState(restoreState.getPackageIndex() >= 0, "nextRestorePackage() not called");
        Preconditions.checkState(restoreState.getRestoreType() == TYPE_KEY_VALUE,
                "getRestoreData() for non-key/value dataset");

        PackageInfo packageInfo = restoreState.getPackages()[restoreState.getPackageIndex()];
        BackupDataOutput backupDataOutput = new BackupDataOutput(outputFileDescriptor.getFileDescriptor());

        try {
            return transferIncrementalRestoreData(packageInfo.packageName, backupDataOutput);

        } catch (Exception ex) {
            Log.e(TAG, "Unable to read backup records: ", ex);
            return TRANSPORT_ERROR;
        }
    }

    private int transferIncrementalRestoreData(String packageName, BackupDataOutput backupDataOutput)
            throws IOException, InvalidAlgorithmParameterException, InvalidKeyException {
        ParcelFileDescriptor inputFileDescriptor = buildInputFileDescriptor();
        ZipInputStream inputStream = buildInputStream(inputFileDescriptor);

        Optional<ZipEntry> zipEntryOptional = seekToEntry(inputStream, INCREMENTAL_BACKUP_DIRECTORY + packageName);
        while (zipEntryOptional.isPresent()) {
            String fileName = new File(zipEntryOptional.get().getName()).getName();
            String blobKey = new String(Base64.decode(fileName, Base64.DEFAULT));

            byte[] backupData = Streams.readFullyNoClose(inputStream);
            backupDataOutput.writeEntityHeader(blobKey, backupData.length);
            backupDataOutput.writeEntityData(backupData, backupData.length);
            inputStream.closeEntry();

            zipEntryOptional = seekToEntry(inputStream, INCREMENTAL_BACKUP_DIRECTORY + packageName);
        }

        IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
        return TRANSPORT_OK;
    }

    @Override
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor fileDescriptor) {
        Preconditions.checkState(restoreState.getRestoreType() == TYPE_FULL_STREAM,
                "Asked for full restore data for non-stream package");

        ParcelFileDescriptor inputFileDescriptor = restoreState.getInputFileDescriptor();
        ZipInputStream inputStream = restoreState.getInputStream();

        if (inputFileDescriptor == null) {
            String name = restoreState.getPackages()[restoreState.getPackageIndex()].packageName;

            try {
                inputFileDescriptor = buildInputFileDescriptor();
                restoreState.setInputFileDescriptor(inputFileDescriptor);

                inputStream = buildInputStream(inputFileDescriptor);
                restoreState.setInputStream(inputStream);

                if (!seekToEntry(inputStream, FULL_BACKUP_DIRECTORY + name).isPresent()) {
                    IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
                    return TRANSPORT_PACKAGE_REJECTED;
                }

            } catch (IOException ex) {
                Log.e(TAG, "Unable to read archive for " + name, ex);

                if (inputFileDescriptor != null) {
                    IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
                }
                return TRANSPORT_PACKAGE_REJECTED;
            }
        }

        if (restoreState.getOutputStream() == null) {
            restoreState.setOutputStream(new FileOutputStream(fileDescriptor.getFileDescriptor()));
        }

        OutputStream outputStream = restoreState.getOutputStream();

        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int bytesRead = NO_MORE_DATA;

        try {
            bytesRead = inputStream.read(buffer);

            if (bytesRead <= 0) {
                bytesRead = NO_MORE_DATA;
            } else {
                outputStream.write(buffer, 0, bytesRead);
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception while streaming restore data: ", e);
            return TRANSPORT_ERROR;

        } finally {
            if (bytesRead == NO_MORE_DATA) {
                if (inputFileDescriptor != null) {
                    IoUtils.closeQuietly(inputFileDescriptor.getFileDescriptor());
                }
                IoUtils.closeQuietly(fileDescriptor.getFileDescriptor());

                restoreState.setInputFileDescriptor(null);
                restoreState.setInputStream(null);
                restoreState.setOutputStream(null);
            }
        }

        return bytesRead;
    }

    @Override
    public int abortFullRestore() {
        resetFullRestoreState();
        return TRANSPORT_OK;
    }

    @Override
    public long getCurrentRestoreSet() {
        return DEFAULT_RESTORE_SET;
    }

    @Override
    public void finishRestore() {
        if (restoreState.getRestoreType() == TYPE_FULL_STREAM) {
            resetFullRestoreState();
        }

        restoreState = null;
    }

    @Override
    public RestoreSet[] getAvailableRestoreSets() {
        return new RestoreSet[]{new RestoreSet("Local disk image", "flash", DEFAULT_RESTORE_SET)};
    }

    private void resetFullRestoreState() {
        Preconditions.checkNotNull(restoreState);
        Preconditions.checkState(restoreState.getRestoreType() == TYPE_FULL_STREAM);

        IoUtils.closeQuietly(restoreState.getInputFileDescriptor());
        IoUtils.closeQuietly(restoreState.getOutputStream());
        restoreState = null;
    }
}
