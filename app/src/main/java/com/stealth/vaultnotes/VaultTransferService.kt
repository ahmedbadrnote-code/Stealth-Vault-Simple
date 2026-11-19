package com.stealth.vaultnotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.crypto.SecretKey

internal data class VaultTransferEntry(
    var sourceUri: String? = null,
    var blobPrefix: String? = null,
    var itemId: String? = null,
    var targetUri: String? = null,
    var temporaryName: String? = null,
    var state: String = "PENDING"
)

internal data class VaultTransferJournal(
    val id: String,
    val operation: String,
    val createdAt: Long,
    val mediaType: String? = null,
    val subFolder: String? = null,
    val sourceUris: List<String> = emptyList(),
    val selectedItems: List<VaultFileItem> = emptyList(),
    val destinationTreeUri: String? = null,
    val entries: MutableList<VaultTransferEntry> = mutableListOf()
)

private class TransferCountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var bytesWritten: Long = 0L
    override fun write(value: Int) {
        out.write(value)
        bytesWritten++
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        bytesWritten += length
    }
}

object VaultTransferManager {
    private val gson = Gson()
    private const val JOURNAL_PREFIX = "vault_transfer_"

    fun startImport(
        context: Context,
        uris: List<Uri>,
        mediaType: VaultMediaType,
        subFolder: String
    ): Boolean {
        if (uris.isEmpty()) return false
        return start(context, VaultTransferJournal(
            id = UUID.randomUUID().toString(),
            operation = "IMPORT",
            createdAt = System.currentTimeMillis(),
            mediaType = mediaType.name,
            subFolder = subFolder,
            sourceUris = uris.map { it.toString() },
            entries = uris.mapIndexed { index, uri ->
                VaultTransferEntry(
                    sourceUri = uri.toString(),
                    blobPrefix = "xfer_${UUID.randomUUID().toString().take(8)}_${index}_"
                )
            }.toMutableList()
        ))
    }

    fun startExport(
        context: Context,
        items: List<VaultFileItem>,
        destinationTreeUri: Uri
    ): Boolean {
        if (items.isEmpty()) return false
        val id = UUID.randomUUID().toString()
        return start(context, VaultTransferJournal(
            id = id,
            operation = "EXPORT",
            createdAt = System.currentTimeMillis(),
            selectedItems = items,
            destinationTreeUri = destinationTreeUri.toString(),
            entries = items.mapIndexed { index, item ->
                VaultTransferEntry(
                    itemId = item.id,
                    temporaryName = ".svxfer_${id}_${index}.part"
                )
            }.toMutableList()
        ))
    }

    private fun start(context: Context, journal: VaultTransferJournal): Boolean {
        val key = try {
            SecurityCrypto.captureTransferKey(context, journal.id)
        } catch (_: Exception) {
            return false
        }
        val journalFile = journalFile(context, journal.id)
        return try {
            writeJournal(journalFile, journal, key)
            val intent = Intent(context, VaultTransferService::class.java)
                .putExtra(VaultTransferService.EXTRA_TRANSFER_ID, journal.id)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: Exception) {
            journalFile.delete()
            SecurityCrypto.finishTransferKey(journal.id)
            false
        }
    }

    internal fun journalFile(context: Context, id: String) =
        File(context.filesDir, JOURNAL_PREFIX + id + ".enc")

    internal fun writeJournal(file: File, journal: VaultTransferJournal, key: SecretKey) {
        val encrypted = SecurityCrypto.encryptBytesForTransfer(
            key,
            gson.toJson(journal).toByteArray(Charsets.UTF_8)
        )
        val temp = File(file.parentFile, file.name + ".tmp")
        val backup = File(file.parentFile, file.name + ".bak")
        temp.writeBytes(encrypted)
        FileOutputStreamSync.sync(temp)
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            backup.delete()
        } catch (_: Exception) {
            if (file.exists()) {
                if (backup.exists()) backup.delete()
                check(file.renameTo(backup)) { "Unable to preserve previous transfer journal" }
            }
            try {
                check(temp.renameTo(file)) { "Unable to finalize transfer journal" }
                backup.delete()
            } catch (e: Exception) {
                if (backup.exists()) backup.renameTo(file)
                throw e
            }
        }
    }

    internal fun readJournal(file: File, key: SecretKey): VaultTransferJournal {
        val json = String(
            SecurityCrypto.decryptBytesForTransfer(key, file.readBytes()),
            Charsets.UTF_8
        )
        return gson.fromJson(json, VaultTransferJournal::class.java)
            ?: throw IllegalStateException("Transfer journal is invalid")
    }

    /**
     * Reconciles interrupted transfers only after the user has unlocked the vault.
     * Incomplete imports are rolled back; completed imports keep their vault copy and
     * retry deletion of the original. Incomplete exports are removed from the target.
     */
    fun recoverPending(context: Context) {
        val key = SecurityCrypto.activeTransferKey(context)
        context.filesDir.listFiles()?.filter {
            it.name.startsWith(JOURNAL_PREFIX) && it.name.endsWith(".enc.bak")
        }?.forEach { backup ->
            val target = File(context.filesDir, backup.name.removeSuffix(".bak"))
            if (!target.exists()) backup.renameTo(target) else backup.delete()
        }
        val journalFiles = context.filesDir.listFiles()?.filter {
            it.name.startsWith(JOURNAL_PREFIX) && it.name.endsWith(".enc")
        }.orEmpty()
        // A missing index is not proof that an export committed. Preserve every
        // referenced blob and journal rather than risking deletion after index loss.
        if (!File(context.filesDir, "vault_index.enc").exists() && journalFiles.isNotEmpty()) return
        val index = StorageRepository.loadVaultFilesForTransfer(context, key)
            ?: throw IllegalStateException("Vault index could not be read safely")
        val blobDir = StorageRepository.getVaultBlobDir(context)
        journalFiles.forEach { file ->
            val journal = try {
                readJournal(file, key)
            } catch (_: Exception) {
                // An unreadable journal must not trigger destructive cleanup.
                return@forEach
            }
            if (SecurityCrypto.transferKey(journal.id) != null) return@forEach
            var cleanupComplete = true
            when (journal.operation) {
                "IMPORT" -> {
                    journal.entries.forEach { entry ->
                        val prefix = entry.blobPrefix
                        if (prefix != null) {
                            val committed = index.any { it.encryptedFileName.startsWith(prefix) }
                            if (committed) {
                                entry.sourceUri?.let { uri ->
                                    if (!SecurityCrypto.deleteOriginalDocument(context, Uri.parse(uri))) {
                                        cleanupComplete = false
                                    }
                                }
                            } else {
                                blobDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach {
                                    if (it.exists() && !it.delete() && it.exists()) cleanupComplete = false
                                }
                            }
                        }
                    }
                }
                "EXPORT" -> {
                    val destination = journal.destinationTreeUri?.let {
                        DocumentFile.fromTreeUri(context, Uri.parse(it))
                    }
                    journal.entries.forEach { entry ->
                        val item = journal.selectedItems.firstOrNull { it.id == entry.itemId }
                        when (entry.state) {
                            "COPYING", "PENDING", "FAILED" -> {
                                entry.targetUri?.let {
                                    if (!deleteDocument(context, Uri.parse(it))) cleanupComplete = false
                                }
                                entry.temporaryName?.let { name ->
                                    destination?.listFiles()?.filter { it.name == name }?.forEach {
                                        if (!it.delete() && it.exists()) cleanupComplete = false
                                    }
                                }
                            }
                            "OUTPUT_COMPLETE" -> {
                                if (item != null && index.none { it.id == item.id }) {
                                    val blob = File(blobDir, item.encryptedFileName)
                                    if (blob.exists() && !blob.delete() && blob.exists()) cleanupComplete = false
                                }
                            }
                            "COMMITTED" -> {
                                item?.let {
                                    val blob = File(blobDir, it.encryptedFileName)
                                    if (blob.exists() && !blob.delete() && blob.exists()) cleanupComplete = false
                                }
                            }
                        }
                    }
                    val knownTempNames = journal.entries.mapNotNull { it.temporaryName }.toSet()
                    destination?.listFiles()?.filter {
                        it.name?.startsWith(".svxfer_${journal.id}_") == true && it.name in knownTempNames
                    }?.forEach { if (!it.delete() && it.exists()) cleanupComplete = false }
                }
            }
            if (cleanupComplete) file.delete()
        }
        // Only temporary files belonging to a transfer are eligible for cleanup.
        blobDir.listFiles()?.filter { it.name.startsWith("xfer_") && it.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }

    private fun deleteDocument(context: Context, uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            DocumentFile.fromSingleUri(context, uri)?.delete() == true
        }
    }

    private object FileOutputStreamSync {
        fun sync(file: File) {
            java.io.FileOutputStream(file, true).use { it.fd.sync() }
        }
    }
}

class VaultTransferService : Service() {
    companion object {
    const val EXTRA_TRANSFER_ID = "vault_transfer_id"
    const val ACTION_FINISHED = "com.stealth.vaultnotes.VAULT_TRANSFER_FINISHED"
    private const val CHANNEL_ID = "vault_background_transfer"
    private const val NOTIFICATION_ID = 7314
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferWakeLock: PowerManager.WakeLock? = null
    @Volatile private var activeTransferId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transferId = intent?.getStringExtra(EXTRA_TRANSFER_ID)
        if (transferId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (AppLanguage.getLanguage(this) == AppLanguage.ARABIC) "نشاط في الخلفية" else "Background activity")
            .setContentText(if (AppLanguage.getLanguage(this) == AppLanguage.ARABIC) "جارٍ تنفيذ عملية في الخلفية" else "Background task in progress")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            SecurityCrypto.finishTransferKey(transferId)
            sendTransferFinished(false, "TRANSFER")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val key = SecurityCrypto.transferKey(transferId)
        if (key == null) {
            sendTransferFinished(false, "TRANSFER")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            transferWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StealthVault:BackgroundTransfer"
            ).apply {
                setReferenceCounted(false)
                acquire(6L * 60L * 60L * 1000L)
            }
        } catch (_: Exception) {
            SecurityCrypto.finishTransferKey(transferId)
            sendTransferFinished(false, "TRANSFER")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeTransferId = transferId

        serviceScope.launch {
            var success = false
            var operation = "TRANSFER"
            try {
                val file = VaultTransferManager.journalFile(this@VaultTransferService, transferId)
                val journal = VaultTransferManager.readJournal(file, key)
                operation = journal.operation
                success = when (journal.operation) {
                    "IMPORT" -> runImport(journal, file, key)
                    "EXPORT" -> runExport(journal, file, key)
                    else -> false
                }
                if (success) file.delete()
            } catch (_: Exception) {
                success = false
            } finally {
                if (activeTransferId == transferId) {
                    activeTransferId = null
                    SecurityCrypto.finishTransferKey(transferId)
                    releaseTransferWakeLock()
                    sendTransferFinished(success, operation)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runImport(
        journal: VaultTransferJournal,
        journalFile: File,
        key: SecretKey
    ): Boolean {
        // Read once before touching sources. A corrupt/unreadable index must never
        // be replaced by a newly constructed partial list.
        StorageRepository.loadVaultFilesForTransfer(this, key)
            ?: throw IllegalStateException("Vault index is unreadable")
        val type = VaultMediaType.valueOf(journal.mediaType ?: return false)
        var completed = 0
        var allOriginalsDeleted = true
        journal.entries.forEach entryLoop@ { entry ->
            val uri = entry.sourceUri?.let(Uri::parse) ?: return@entryLoop
            val prefix = entry.blobPrefix ?: return@entryLoop
            val item = SecurityCrypto.importAndMoveFileViaSAF(
                this, uri, type, journal.subFolder ?: "General", prefix
            ) ?: return@entryLoop

            // Merge into the latest index under StorageRepository's shared lock so
            // a concurrent app lifecycle transition cannot overwrite unrelated rows.
            val saved = StorageRepository.updateVaultFilesForTransfer(this, key) { current ->
                current.toMutableList().apply { add(item) }
            }
            if (!saved) {
                File(StorageRepository.getVaultBlobDir(this), item.encryptedFileName).delete()
                return@entryLoop
            }

            entry.itemId = item.id
            entry.state = "INDEXED"
            VaultTransferManager.writeJournal(journalFile, journal, key)
            val originalDeleted = SecurityCrypto.deleteOriginalDocument(this, uri)
            if (!originalDeleted) allOriginalsDeleted = false
            entry.state = if (originalDeleted) "ORIGINAL_DELETED" else "INDEXED_ORIGINAL_REMAINS"
            VaultTransferManager.writeJournal(journalFile, journal, key)
            completed++
        }
        return completed == journal.entries.size && allOriginalsDeleted
    }

    private fun runExport(
        journal: VaultTransferJournal,
        journalFile: File,
        key: SecretKey
    ): Boolean {
        val destinationUri = journal.destinationTreeUri?.let(Uri::parse) ?: return false
        val destination = DocumentFile.fromTreeUri(this, destinationUri) ?: return false
        StorageRepository.loadVaultFilesForTransfer(this, key)
            ?: throw IllegalStateException("Vault index is unreadable")
        var completed = 0
        journal.entries.forEachIndexed { index, entry ->
            val item = journal.selectedItems.firstOrNull { it.id == entry.itemId } ?: return@forEachIndexed
            val latestIndex = StorageRepository.loadVaultFilesForTransfer(this, key)
                ?: throw IllegalStateException("Vault index is unreadable")
            if (latestIndex.none { it.id == item.id }) {
                entry.state = "COMMITTED"
                VaultTransferManager.writeJournal(journalFile, journal, key)
                completed++
                return@forEachIndexed
            }

            val temporaryName = entry.temporaryName ?: return@forEachIndexed
            val created = destination.createFile(item.mimeType, temporaryName)
                ?: return@forEachIndexed
            entry.targetUri = created.uri.toString()
            entry.state = "COPYING"
            VaultTransferManager.writeJournal(journalFile, journal, key)
            try {
                val output = contentResolver.openOutputStream(created.uri)
                    ?: throw IllegalStateException("Unable to open destination")
                val counted = TransferCountingOutputStream(output)
                counted.use { SecurityCrypto.restoreFileToOutputStreamForTransfer(this, item, it, key) }
                check(item.sizeBytes <= 0L || counted.bytesWritten == item.sizeBytes) {
                    "Exported file size does not match the stored size"
                }
                check(created.renameTo(item.originalName)) { "Unable to finalize exported filename" }
                entry.state = "OUTPUT_COMPLETE"
                VaultTransferManager.writeJournal(journalFile, journal, key)

                // Remove only this selected item, merging against the latest index.
                // The blob is deleted only after the index commit succeeds.
                val indexSaved = StorageRepository.updateVaultFilesForTransfer(this, key) { current ->
                    current.filterNot { it.id == item.id }
                }
                if (indexSaved) {
                    val blob = File(StorageRepository.getVaultBlobDir(this), item.encryptedFileName)
                    if (blob.exists() && !blob.delete() && blob.exists()) {
                        entry.state = "OUTPUT_COMPLETE"
                        VaultTransferManager.writeJournal(journalFile, journal, key)
                    } else {
                        entry.state = "COMMITTED"
                        VaultTransferManager.writeJournal(journalFile, journal, key)
                        completed++
                    }
                }
            } catch (_: Exception) {
                if (entry.state == "COPYING") {
                    created.delete()
                    entry.state = "FAILED"
                    VaultTransferManager.writeJournal(journalFile, journal, key)
                }
            }
        }
        return completed == journal.entries.size
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Background activity",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                        enableLights(false)
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    }
                )
            }
        }
    }


    private fun sendTransferFinished(success: Boolean, operation: String) {
        sendBroadcast(
            Intent(ACTION_FINISHED)
                .setPackage(packageName)
                .putExtra("success", success)
                .putExtra("operation", operation)
        )
    }

    private fun releaseTransferWakeLock() {
        try {
            transferWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            transferWakeLock = null
        }
    }

    override fun onDestroy() {
        activeTransferId?.let { transferId ->
            activeTransferId = null
            SecurityCrypto.finishTransferKey(transferId)
            sendTransferFinished(false, "TRANSFER")
        }
        releaseTransferWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
