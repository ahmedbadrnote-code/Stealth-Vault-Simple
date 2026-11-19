package com.stealth.vaultnotes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap

object SecurityCrypto {
    private const val PREFS_NAME = "stealth_vault_prefs"
    private const val KEY_PASS_HASH = "pass_hash"
    private const val KEY_AES_RAW = "aes_key_hex"
    private const val KEY_AES_WRAPPED = "aes_key_wrapped"
    private const val KEYSTORE_ALIAS = "StealthVaultMasterKey"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_BITS = 256

    @Volatile
    private var cachedAesKey: SecretKey? = null

    @Volatile
    private var vaultSessionUnlocked = false

    @Volatile
    private var sessionGeneration = 0L

    private val sessionLock = Any()
    private val transferKeys = ConcurrentHashMap<String, SecretKey>()

    /**
     * Captures a narrowly scoped in-memory key reference for a transfer explicitly
     * started while the vault is unlocked. Locking the UI still clears the normal
     * session key; only this already-authorized transfer retains its own reference.
     */
    internal fun captureTransferKey(context: Context, transferId: String): SecretKey {
        require(transferId.isNotBlank())
        val key = synchronized(sessionLock) {
            check(vaultSessionUnlocked) { "Vault session is locked" }
            cachedAesKey ?: throw IllegalStateException("Vault key is not ready for transfer")
        }
        check(transferKeys.putIfAbsent(transferId, key) == null) {
            "Transfer key already exists"
        }
        return key
    }

    internal fun transferKey(transferId: String): SecretKey? = transferKeys[transferId]

    internal fun finishTransferKey(transferId: String) {
        transferKeys.remove(transferId)
    }

    fun hasActiveTransfers(): Boolean = transferKeys.isNotEmpty()

    fun isVaultSessionUnlocked(): Boolean = synchronized(sessionLock) {
        vaultSessionUnlocked
    }

    internal fun activeTransferKey(context: Context): SecretKey = synchronized(sessionLock) {
        check(vaultSessionUnlocked) { "Vault session is locked" }
        cachedAesKey ?: throw IllegalStateException("Vault key is not ready")
    }

    internal fun encryptBytesForTransfer(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    internal fun decryptBytesForTransfer(key: SecretKey, encrypted: ByteArray): ByteArray {
        require(encrypted.size > 12) { "Encrypted transfer data is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }

    fun isFirstRun(context: Context): Boolean =
        !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_PASS_HASH)

    @Synchronized
    fun setPasscode(context: Context, passcode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derivePasscode(passcode, salt, PBKDF2_ITERATIONS)
        val encoded = "pbkdf2\$" + PBKDF2_ITERATIONS + "\$" +
            Base64.encodeToString(salt, Base64.NO_WRAP) + "\$" +
            Base64.encodeToString(hash, Base64.NO_WRAP)
        synchronized(sessionLock) {
            vaultSessionUnlocked = true
            sessionGeneration++
        }
        try {
            ensureAesKey(context)
            check(prefs.edit().putString(KEY_PASS_HASH, encoded).commit()) { "Unable to save passcode" }
        } finally {
            synchronized(sessionLock) {
                vaultSessionUnlocked = false
                sessionGeneration++
                cachedAesKey = null
            }
        }
    }

    @Synchronized
    fun verifyPasscode(context: Context, input: String): Boolean {
        if (input.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_PASS_HASH, null) ?: return false
        val valid = if (!saved.startsWith("pbkdf2\$")) {
            val matches = MessageDigest.isEqual(saved.toByteArray(), sha256(input).toByteArray())
            if (matches) upgradeLegacyPasscode(prefs, input)
            matches
        } else {
            try {
                val parts = saved.split("\$")
                if (parts.size != 4) false else {
                    val iterations = parts[1].toInt()
                    if (iterations !in 50_000..2_000_000) false
                    else {
                        val expected = Base64.decode(parts[3], Base64.NO_WRAP)
                        val salt = Base64.decode(parts[2], Base64.NO_WRAP)
                        MessageDigest.isEqual(expected, derivePasscode(input, salt, iterations))
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
        if (valid) {
            synchronized(sessionLock) {
                vaultSessionUnlocked = true
                sessionGeneration++
            }
            try {
                ensureAesKey(context)
            } catch (e: Exception) {
                synchronized(sessionLock) {
                    vaultSessionUnlocked = false
                    sessionGeneration++
                    cachedAesKey = null
                }
                throw e
            }
        }
        return valid
    }

    private fun upgradeLegacyPasscode(prefs: android.content.SharedPreferences, input: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val encoded = "pbkdf2\$" + PBKDF2_ITERATIONS + "\$" +
            Base64.encodeToString(salt, Base64.NO_WRAP) + "\$" +
            Base64.encodeToString(derivePasscode(input, salt, PBKDF2_ITERATIONS), Base64.NO_WRAP)
        prefs.edit().putString(KEY_PASS_HASH, encoded).commit()
    }

    private fun derivePasscode(passcode: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, iterations, PBKDF2_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun getExistingKeystoreKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEYSTORE_ALIAS, null) as? SecretKey
    }

    private fun hasExistingEncryptedVaultData(context: Context): Boolean {
        val filesDir = context.filesDir
        val fixedFiles = listOf(
            "vault_index.enc",
            "vault_index.enc.bak",
            "vault_secret_notes.enc",
            "vault_secret_notes.enc.bak"
        )
        if (fixedFiles.any { File(filesDir, it).exists() }) return true
        return filesDir.listFiles()?.any { file ->
            file.name.startsWith("vault_folders_") &&
                (file.name.endsWith(".enc") || file.name.endsWith(".enc.bak"))
        } == true
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        getExistingKeystoreKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrapDataKey(master: SecretKey, raw: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, master)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(raw), Base64.NO_WRAP)
    }

    private fun unwrapDataKey(master: SecretKey, value: String): SecretKey {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return SecretKeySpec(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), "AES")
    }

    @Synchronized
    private fun ensureAesKey(context: Context): SecretKey {
        synchronized(sessionLock) {
            check(vaultSessionUnlocked) { "Vault session is locked" }
            cachedAesKey?.let { return it }
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_AES_WRAPPED, null)?.let { wrappedValue ->
            // Never replace a wrapped key automatically. If the Android Keystore key
            // is unavailable or the wrapped value is damaged, fail closed so existing
            // encrypted data is not silently made inaccessible by a new data key.
            val master = getExistingKeystoreKey()
                ?: throw IllegalStateException(
                    "Vault master key is missing; refusing to generate a replacement for existing encrypted data"
                )
            val unwrapped = unwrapDataKey(master, wrappedValue)
            synchronized(sessionLock) {
                check(vaultSessionUnlocked) { "Vault session was locked during key access" }
                cachedAesKey = unwrapped
            }
            return unwrapped
        }

        val legacy = prefs.getString(KEY_AES_RAW, null)
        val raw = if (legacy != null) {
            // A legacy key's presence means it may protect existing user data.
            // Reject malformed values without generating a replacement or deleting it.
            require(legacy.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Legacy vault key is invalid; refusing to replace the existing key"
            }
            legacy.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            check(!hasExistingEncryptedVaultData(context)) {
                "Vault encryption key is missing; refusing to generate a replacement for existing encrypted data"
            }
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
        val master = getOrCreateKeystoreKey()
        val wrapped = wrapDataKey(master, raw)
        check(prefs.edit().putString(KEY_AES_WRAPPED, wrapped).remove(KEY_AES_RAW).commit()) {
            "Unable to migrate vault key safely"
        }
        val secretKey = SecretKeySpec(raw, "AES")
        synchronized(sessionLock) {
            check(vaultSessionUnlocked) { "Vault session was locked during key creation" }
            cachedAesKey = secretKey
        }
        return secretKey
    }

    fun encryptBytes(context: Context, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, ensureAesKey(context))
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decryptBytes(context: Context, encrypted: ByteArray): ByteArray {
        require(encrypted.size > 12) { "Encrypted data is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            ensureAesKey(context),
            GCMParameterSpec(128, encrypted.copyOfRange(0, 12))
        )
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }

    private fun copyStreamToFile(input: java.io.InputStream, target: File): Long {
        var copied = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                copied += count
            }
            output.flush()
            output.fd.sync()
        }
        return copied
    }

    private fun encryptStreamToFile(context: Context, input: java.io.InputStream, target: File): Long {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, ensureAesKey(context))
        var plainSize = 0L
        FileOutputStream(target).use { fileOut ->
            fileOut.write(cipher.iv)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                plainSize += count
                val encrypted = cipher.update(buffer, 0, count)
                if (encrypted != null && encrypted.isNotEmpty()) fileOut.write(encrypted)
            }
            fileOut.write(cipher.doFinal())
            fileOut.flush()
            fileOut.fd.sync()
        }
        return plainSize
    }

    fun importAndMoveFileViaSAF(
        context: Context,
        uri: Uri,
        mediaType: VaultMediaType,
        subFolder: String,
        transferPrefix: String? = null
    ): VaultFileItem? {
        return try {
            val resolver = context.contentResolver
            var name = "file_" + System.currentTimeMillis()
            var queriedSize = 0L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = cursor.getString(ni)?.takeIf { it.isNotBlank() } ?: name
                    if (si >= 0 && !cursor.isNull(si)) queriedSize = cursor.getLong(si)
                }
            }
            val extension = name.substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
                .takeIf { it.length in 1..10 && it.all { ch -> ch.isLetterOrDigit() } } ?: "blob"
            val providerMime = resolver.getType(uri)?.takeIf { it.isNotBlank() }
            val extensionMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            val mime = when {
                providerMime != null &&
                    !providerMime.equals("application/octet-stream", ignoreCase = true) &&
                    !providerMime.equals("application/unknown", ignoreCase = true) -> providerMime
                extensionMime != null -> extensionMime
                else -> when (mediaType) {
                    VaultMediaType.PHOTO -> "image/jpeg"
                    VaultMediaType.VIDEO -> "video/mp4"
                    VaultMediaType.DOCUMENT -> "application/pdf"
                }
            }
            val filename = (transferPrefix ?: "") + UUID.randomUUID().toString() + "." + extension
            val dir = StorageRepository.getVaultBlobDir(context)
            check(dir.exists() || dir.mkdirs()) { "Unable to create vault storage" }
            val target = File(dir, filename)
            val temp = File(dir, "$filename.tmp")
            try {
                val input = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open selected file")
                val streamedSize = input.use { copyStreamToFile(it, temp) }
                check(temp.isFile) { "Imported file was not written" }
                check(queriedSize <= 0L || streamedSize == queriedSize) {
                    "Imported file size does not match the source file size"
                }
                check(temp.renameTo(target)) { "Unable to finalize vault file" }
                val finalSize = streamedSize
                VaultFileItem(
                    originalName = name,
                    encryptedFileName = filename,
                    mimeType = mime,
                    mediaType = mediaType,
                    sizeBytes = finalSize.coerceAtLeast(0L),
                    subFolder = subFolder.ifBlank { "General" },
                    originalParentTreeUri = uri.toString(),
                    isEncrypted = false
                )
            } catch (e: Exception) {
                temp.delete()
                target.delete()
                throw e
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteOriginalDocument(context: Context, uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            try {
                context.contentResolver.delete(uri, null, null) > 0
            } catch (_: Exception) {
                false
            }
        }

    fun decryptFileToOutputStream(context: Context, item: VaultFileItem, output: java.io.OutputStream) {
        val sessionToken = synchronized(sessionLock) {
            check(vaultSessionUnlocked) { "Vault session is locked" }
            sessionGeneration
        }
        val encryptedFile = File(StorageRepository.getVaultBlobDir(context), item.encryptedFileName)
        require(encryptedFile.isFile) { "Vault file is missing" }

        if (item.isEncrypted == false) {
            FileInputStream(encryptedFile).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    synchronized(sessionLock) {
                        check(vaultSessionUnlocked && sessionGeneration == sessionToken) {
                            "Vault session ended during restore"
                        }
                        output.write(buffer, 0, count)
                    }
                }
                synchronized(sessionLock) {
                    check(vaultSessionUnlocked && sessionGeneration == sessionToken) {
                        "Vault session ended during restore"
                    }
                    output.flush()
                }
            }
            return
        }
        require(encryptedFile.length() >= 28L) { "Encrypted vault file is incomplete" }

        FileInputStream(encryptedFile).use { input ->
            val iv = ByteArray(12)
            var ivOffset = 0
            while (ivOffset < iv.size) {
                val count = input.read(iv, ivOffset, iv.size - ivOffset)
                if (count <= 0) throw IllegalStateException("Encrypted file header is incomplete")
                ivOffset += count
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, ensureAesKey(context), GCMParameterSpec(128, iv))
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val plainChunk = cipher.update(buffer, 0, count)
                if (plainChunk != null && plainChunk.isNotEmpty()) {
                    synchronized(sessionLock) {
                        check(vaultSessionUnlocked && sessionGeneration == sessionToken) {
                            "Vault session ended during restore"
                        }
                        output.write(plainChunk)
                    }
                }
            }
            val finalChunk = cipher.doFinal()
            synchronized(sessionLock) {
                check(vaultSessionUnlocked && sessionGeneration == sessionToken) {
                    "Vault session ended during restore"
                }
                if (finalChunk.isNotEmpty()) output.write(finalChunk)
                output.flush()
            }
        }
    }


    /**
     * Restores one explicitly selected item for an already-authorized background
     * transfer. This intentionally does not consult the interactive vault session:
     * the service holds the transfer-scoped key captured before the UI was locked.
     */
    internal fun restoreFileToOutputStreamForTransfer(
        context: Context,
        item: VaultFileItem,
        output: java.io.OutputStream,
        key: SecretKey
    ) {
        val source = File(StorageRepository.getVaultBlobDir(context), item.encryptedFileName)
        require(source.isFile) { "Vault file is missing" }
        FileInputStream(source).use { input ->
            val buffer = ByteArray(64 * 1024)
            if (item.isEncrypted == false) {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                }
                output.flush()
                return
            }

            require(source.length() >= 28L) { "Encrypted vault file is incomplete" }
            val iv = ByteArray(12)
            java.io.DataInputStream(input).readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val plain = cipher.update(buffer, 0, count)
                if (plain != null && plain.isNotEmpty()) output.write(plain)
            }
            val finalBytes = cipher.doFinal()
            if (finalBytes.isNotEmpty()) output.write(finalBytes)
            output.flush()
        }
    }

    fun decryptFileToTempCache(context: Context, item: VaultFileItem): File? {
        return try {
            val sessionToken = synchronized(sessionLock) {
                check(vaultSessionUnlocked) { "Vault session is locked" }
                sessionGeneration
            }
            val source = File(StorageRepository.getVaultBlobDir(context), item.encryptedFileName)
            if (!source.isFile) return null
            // New files are already stored as ordinary files inside app-private storage.
            // Return them directly; callers must use deletePreviewIfTemporary() for cleanup.
            if (item.isEncrypted == false) return source
            if (source.length() < 28L) return null
            val dir = File(context.cacheDir, "unlocked_preview")
            check(dir.exists() || dir.mkdirs()) { "Unable to create preview cache" }
            val ext = item.originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                .takeIf { it.length in 1..6 && it.all { ch -> ch.isLetterOrDigit() } } ?: "bin"
            // Each caller owns a unique plaintext preview file, preventing viewers
            // from deleting or overwriting another operation's active file.
            val destination = File(dir, "preview_${item.id}_${UUID.randomUUID()}.$ext")
            val tempDest = File(dir, "preview_${item.id}_${UUID.randomUUID()}.tmp")
            try {
                FileInputStream(source).use { encryptedInput ->
                    val iv = ByteArray(12)
                    java.io.DataInputStream(encryptedInput).readFully(iv)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, ensureAesKey(context), GCMParameterSpec(128, iv))
                    FileOutputStream(tempDest).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = encryptedInput.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            val plain = cipher.update(buffer, 0, count)
                            if (plain != null && plain.isNotEmpty()) output.write(plain)
                        }
                        val finalBytes = cipher.doFinal()
                        if (finalBytes.isNotEmpty()) output.write(finalBytes)
                        output.flush()
                        output.fd.sync()
                    }
                }
                synchronized(sessionLock) {
                    check(vaultSessionUnlocked && sessionGeneration == sessionToken) {
                        "Vault session ended during preview decryption"
                    }
                    if (!tempDest.renameTo(destination)) {
                        tempDest.copyTo(destination, overwrite = false)
                        tempDest.delete()
                    }
                }
            } catch (e: Exception) {
                tempDest.delete()
                destination.delete()
                throw e
            }
            destination
        } catch (_: Exception) {
            null
        }
    }

    fun deletePreviewIfTemporary(context: Context, file: File?) {
        if (file == null) return
        val previewDir = File(context.cacheDir, "unlocked_preview").canonicalFile
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (candidate.parentFile == previewDir) candidate.delete()
    }

    fun clearDecryptedTempCache(context: Context) {
        synchronized(sessionLock) {
            // Invalidate any preview decryption still running so it cannot recreate
            // a plaintext cache file after this cleanup has completed.
            sessionGeneration++
            val dir = File(context.cacheDir, "unlocked_preview")
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    fun lockVaultSession(context: Context) {
        synchronized(sessionLock) {
            vaultSessionUnlocked = false
            sessionGeneration++
            cachedAesKey = null
            val dir = File(context.cacheDir, "unlocked_preview")
            if (dir.exists()) dir.deleteRecursively()
        }
    }
}
