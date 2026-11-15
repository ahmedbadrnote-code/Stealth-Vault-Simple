package com.stealth.vaultnotes

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val WORD_SPLIT_REGEX = "\\s+".toRegex()

data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var htmlContent: String = "",
    var plainTextPreview: String = "",
    var drawingPathsJson: String = "",
    var isPinned: Boolean = false,
    var isTrashed: Boolean = false,
    var trashedAt: Long? = null,
    var reminderTime: Long? = null,
    var updatedAt: Long = System.currentTimeMillis(),
    var isSecret: Boolean = false,
    var textDirection: String = "rtl"
) {
    fun wordCount(): Int {
        val clean = (plainTextPreview as String?).orEmpty().trim()
        return if (clean.isEmpty()) 0 else clean.split(WORD_SPLIT_REGEX).size
    }

    fun charCount(): Int = (plainTextPreview as String?).orEmpty().length

    fun readingTimeSeconds(): Int {
        val words = wordCount()
        return if (words == 0) 0 else ((words / 200.0) * 60).toInt().coerceAtLeast(1)
    }
}

enum class VaultMediaType { PHOTO, VIDEO, DOCUMENT }

enum class SortOption { DATE_DESC, NAME_ASC, SIZE_DESC }

data class VaultFileItem(
    val id: String = UUID.randomUUID().toString(),
    val originalName: String,
    val encryptedFileName: String,
    val mimeType: String,
    val mediaType: VaultMediaType,
    val sizeBytes: Long,
    val addedAt: Long = System.currentTimeMillis(),
    var subFolder: String = "General",
    var isAlbumCover: Boolean = false,
    var originalParentTreeUri: String? = null,
    var isTrashed: Boolean = false,
    var trashedAt: Long? = null
)

object StorageRepository {
    private val gson = Gson()
    private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000L

    @Volatile private var publicNotesUnreadable = false
    @Volatile private var vaultIndexUnreadable = false
    @Volatile private var secretNotesUnreadable = false
    private val unreadableFolderIndexes = ConcurrentHashMap.newKeySet<VaultMediaType>()

    fun hasPublicNotesReadError(): Boolean = publicNotesUnreadable

    fun hasVaultStorageReadError(): Boolean =
        vaultIndexUnreadable || secretNotesUnreadable || unreadableFolderIndexes.isNotEmpty()

    private fun recoverAtomicBackup(target: File) {
        val parent = target.parentFile ?: return
        parent.listFiles()?.forEach { f ->
            if (f.name.startsWith(target.name + ".") && f.name.endsWith(".tmp")) {
                f.delete()
            }
        }
        val backup = File(parent, target.name + ".bak")
        if (!target.exists() && backup.exists()) backup.renameTo(target)
        else if (target.exists() && backup.exists()) backup.delete()
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = File(parent, target.name + "." + UUID.randomUUID() + ".tmp")
        val backup = File(parent, target.name + ".bak")
        return try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (target.exists()) {
                    if (backup.exists()) backup.delete()
                    check(target.renameTo(backup)) { "Unable to preserve previous storage file" }
                }
                try {
                    check(temp.renameTo(target)) { "Unable to finalize storage file" }
                    backup.delete()
                } catch (e: Exception) {
                    if (backup.exists()) backup.renameTo(target)
                    throw e
                }
            }
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private fun publicNotesFile(context: Context) = File(context.filesDir, "public_notes.json")
    private fun secretNotesFile(context: Context) = File(context.filesDir, "vault_secret_notes.enc")
    private fun vaultIndexFile(context: Context) = File(context.filesDir, "vault_index.enc")

    private fun sanitizeNote(note: NoteItem, forceSecret: Boolean? = null): NoteItem {
        return note.copy(
            id = (note.id as String?).takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString(),
            title = (note.title as String?).orEmpty(),
            htmlContent = (note.htmlContent as String?).orEmpty(),
            plainTextPreview = (note.plainTextPreview as String?).orEmpty(),
            drawingPathsJson = (note.drawingPathsJson as String?).orEmpty(),
            textDirection = if ((note.textDirection as String?) == "ltr") "ltr" else "rtl",
            isSecret = forceSecret ?: note.isSecret,
            reminderTime = if (forceSecret == true) null else note.reminderTime
        )
    }

    @Synchronized
    fun loadPublicNotes(context: Context): MutableList<NoteItem> {
        val file = publicNotesFile(context)
        recoverAtomicBackup(file)
        if (!file.exists()) {
            publicNotesUnreadable = File(file.parentFile, file.name + ".bak").exists()
            return mutableListOf()
        }
        return try {
            val type = object : TypeToken<MutableList<NoteItem>>() {}.type
            val rawList: MutableList<NoteItem> = gson.fromJson(file.readText(Charsets.UTF_8), type)
                ?: throw IllegalStateException("Public notes file is empty or invalid")
            publicNotesUnreadable = false
            val list = rawList.map { sanitizeNote(it, forceSecret = false) }.toMutableList()
            val now = System.currentTimeMillis()
            var migrated = false
            list.forEach {
                if (it.isTrashed && it.trashedAt == null) {
                    it.trashedAt = now
                    migrated = true
                }
            }
            val expired = list.filter { it.isTrashed && it.trashedAt != null && (now - it.trashedAt!!) > THIRTY_DAYS_MS }
            val active = list.filterNot { it in expired }.toMutableList()
            if (expired.isNotEmpty() || migrated) {
                expired.forEach { ReminderReceiver.cancelReminder(context, it.id) }
                savePublicNotes(context, active)
            }
            active
        } catch (_: Exception) {
            publicNotesUnreadable = true
            mutableListOf()
        }
    }

    @Synchronized
    fun savePublicNotes(context: Context, notes: List<NoteItem>): Boolean {
        if (publicNotesUnreadable) return false
        val clean = notes.map { sanitizeNote(it, forceSecret = false) }
        val saved = writeAtomically(publicNotesFile(context), gson.toJson(clean).toByteArray(Charsets.UTF_8))
        if (saved) publicNotesUnreadable = false
        return saved
    }

    @Synchronized
    fun exportPublicNotesJson(context: Context): String {
        val loadedNotes = loadPublicNotes(context)
        check(!publicNotesUnreadable) { "Public notes storage could not be read; export was cancelled to protect your data" }
        val notes = loadedNotes.filter { !it.isTrashed }
        return gson.toJson(notes)
    }

    @Synchronized
    fun importPublicNotesJson(context: Context, json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            val type = object : TypeToken<List<NoteItem>?>() {}.type
            val importedRaw: List<NoteItem> = gson.fromJson<List<NoteItem>?>(json, type) ?: return false
            val imported = importedRaw.map { sanitizeNote(it, forceSecret = false) }
            val current = loadPublicNotes(context)
            if (publicNotesUnreadable) return false
            val byId = current.associateBy { it.id }.toMutableMap()
            imported.forEach { incoming ->
                val existing = byId[incoming.id]
                if (existing == null || incoming.updatedAt > existing.updatedAt) {
                    byId[incoming.id] = incoming
                }
            }
            savePublicNotes(context, byId.values.toList())
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun loadSecretNotes(context: Context): MutableList<NoteItem> {
        val file = secretNotesFile(context)
        recoverAtomicBackup(file)
        if (!file.exists()) {
            secretNotesUnreadable = File(file.parentFile, file.name + ".bak").exists()
            return mutableListOf()
        }
        return try {
            val decryptedBytes = SecurityCrypto.decryptBytes(context, file.readBytes())
            val json = String(decryptedBytes, Charsets.UTF_8)
            val type = object : TypeToken<MutableList<NoteItem>>() {}.type
            val rawList: MutableList<NoteItem> = gson.fromJson(json, type)
                ?: throw IllegalStateException("Private notes index is empty or invalid")
            val list = rawList.map { sanitizeNote(it, forceSecret = true) }.toMutableList()
            // Secret notes never support reminders. Cancel any legacy alarms and remove
            // reminder timestamps left by older app versions.
            list.forEach { ReminderReceiver.cancelReminder(context, it.id) }
            val now = System.currentTimeMillis()
            secretNotesUnreadable = false
            var migrated = rawList.any { it.reminderTime != null }
            list.forEach {
                if (it.isTrashed && it.trashedAt == null) {
                    it.trashedAt = now
                    migrated = true
                }
            }
            val expired = list.filter { it.isTrashed && it.trashedAt != null && (now - it.trashedAt!!) > THIRTY_DAYS_MS }
            val active = list.filterNot { it in expired }.toMutableList()
            if (expired.isNotEmpty() || migrated) {
                saveSecretNotes(context, active)
            }
            active
        } catch (_: Exception) {
            secretNotesUnreadable = true
            mutableListOf()
        }
    }

    @Synchronized
    fun saveSecretNotes(context: Context, notes: List<NoteItem>): Boolean {
        if (secretNotesUnreadable) return false
        return try {
            val clean = notes.map { sanitizeNote(it, forceSecret = true) }
            val encrypted = SecurityCrypto.encryptBytes(context, gson.toJson(clean).toByteArray(Charsets.UTF_8))
            writeAtomically(secretNotesFile(context), encrypted)
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun loadVaultFiles(context: Context): MutableList<VaultFileItem> {
        val file = vaultIndexFile(context)
        recoverAtomicBackup(file)
        // Do not create a missing blob directory while loading: an existing index
        // with a missing directory must be treated as a storage error, not an empty vault.
        val blobDir = File(context.filesDir, "vault_blobs")
        if (!file.exists()) {
            vaultIndexUnreadable = File(file.parentFile, file.name + ".bak").exists()
            return mutableListOf()
        }
        return try {
            check(blobDir.exists() && blobDir.isDirectory && blobDir.canRead()) {
                "Vault blob directory is not readable"
            }
            val blobNames = blobDir.list()?.toSet()
                ?: throw IllegalStateException("Unable to list vault blob directory")
            val decryptedBytes = SecurityCrypto.decryptBytes(context, file.readBytes())
            val json = String(decryptedBytes, Charsets.UTF_8)
            val type = object : TypeToken<MutableList<VaultFileItem>>() {}.type
            val list: MutableList<VaultFileItem> = gson.fromJson(json, type)
                ?: throw IllegalStateException("Vault index is empty or invalid")

            vaultIndexUnreadable = false
            var needsResave = false

            // تنظيف السجلات التي فقدت ملفها المشفر بدلاً من تعطيل الخزنة للأبد
            val existingBlobs = list.filter { item ->
                val name = item.encryptedFileName
                name.isNotBlank() && !name.contains('/') && !name.contains('\\') &&
                    name in blobNames && File(blobDir, name).isFile
            }.toMutableList()
            if (existingBlobs.size != list.size) {
                needsResave = true
            }

            existingBlobs.forEach {
                if (it.subFolder == "العام" || it.subFolder.isBlank()) {
                    it.subFolder = "General"
                    needsResave = true
                }
            }

            val now = System.currentTimeMillis()
            existingBlobs.forEach {
                if (it.isTrashed && it.trashedAt == null) {
                    it.trashedAt = now
                    needsResave = true
                }
            }

            val expired = existingBlobs.filter { it.isTrashed && it.trashedAt != null && (now - it.trashedAt!!) > THIRTY_DAYS_MS }
            val active = existingBlobs.filterNot { it in expired }.toMutableList()

            if (expired.isNotEmpty() || needsResave) {
                if (saveVaultFiles(context, active)) {
                    expired.forEach { File(blobDir, it.encryptedFileName).delete() }
                }
            }
            active
        } catch (_: Exception) {
            vaultIndexUnreadable = true
            mutableListOf()
        }
    }

    @Synchronized
    fun saveVaultFiles(context: Context, files: List<VaultFileItem>): Boolean {
        if (vaultIndexUnreadable) return false
        return try {
            val encrypted = SecurityCrypto.encryptBytes(context, gson.toJson(files).toByteArray(Charsets.UTF_8))
            writeAtomically(vaultIndexFile(context), encrypted)
        } catch (_: Exception) {
            false
        }
    }

    private fun vaultFoldersFile(context: Context, mediaType: VaultMediaType): File =
        File(context.filesDir, "vault_folders_${mediaType.name.lowercase(Locale.ROOT)}.enc")

    @Synchronized
    fun loadVaultFolders(context: Context, mediaType: VaultMediaType): MutableList<String> {
        val file = vaultFoldersFile(context, mediaType)
        recoverAtomicBackup(file)
        if (!file.exists()) {
            if (File(file.parentFile, file.name + ".bak").exists()) {
                unreadableFolderIndexes.add(mediaType)
            } else {
                unreadableFolderIndexes.remove(mediaType)
            }
            return mutableListOf("General")
        }
        return try {
            val json = String(SecurityCrypto.decryptBytes(context, file.readBytes()), Charsets.UTF_8)
            val type = object : TypeToken<MutableList<String>>() {}.type
            val folders: MutableList<String> = gson.fromJson(json, type)
                ?: throw IllegalStateException("Folder index is empty or invalid")
            unreadableFolderIndexes.remove(mediaType)
            folders.map { if (it.trim() == "العام") "General" else it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .toMutableList()
        } catch (_: Exception) {
            unreadableFolderIndexes.add(mediaType)
            mutableListOf("General")
        }
    }

    @Synchronized
    fun saveVaultFolders(context: Context, mediaType: VaultMediaType, folders: List<String>): Boolean {
        if (mediaType in unreadableFolderIndexes) return false
        return try {
            val target = vaultFoldersFile(context, mediaType)
            val cleanList = folders
                .map { if (it.trim() == "العام") "General" else it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.ROOT) }
            val encrypted = SecurityCrypto.encryptBytes(context, gson.toJson(cleanList).toByteArray(Charsets.UTF_8))
            writeAtomically(target, encrypted)
        } catch (_: Exception) {
            false
        }
    }

    fun getVaultBlobDir(context: Context): File {
        val dir = File(context.filesDir, "vault_blobs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
