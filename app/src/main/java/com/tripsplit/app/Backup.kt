package com.tripsplit.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * Off-device copies. The local file is always the real ledger; this makes a copy
 * you can send somewhere that isn't this tablet, which is the only thing that
 * survives losing it.
 *
 * A backup always contains every trip, so restoring one can never quietly cost
 * you a trip that wasn't in the file.
 */
object Backup {

    private const val TAG = "TripSplit.Backup"
    private const val AUTHORITY = "com.tripsplit.app.files"

    fun suggestedFileName(): String = "trip-split-backup-" + Dates.fileStamp() + ".json"

    private fun writeToCache(ctx: Context, library: Library): Uri {
        val dir = File(ctx.cacheDir, "backups")
        dir.mkdirs()
        // Earlier copies have already been handed to whichever app they were
        // sent to; there's no reason to let them pile up.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, suggestedFileName())
        file.writeText(Store.toJsonText(library))
        return FileProvider.getUriForFile(ctx, AUTHORITY, file)
    }

    /**
     * Opens the share sheet with every trip as a .json attachment plus a readable
     * summary of the open one, so the same message is useful to a person and to
     * the app it restores into.
     */
    fun shareIntent(ctx: Context, library: Library): Intent {
        val uri = writeToCache(ctx, library)
        val active = library.active
        val body = StringBuilder()
        if (active != null) body.append(Settle.summary(active)).append("\n")
        body.append("---\n")
        body.append("The attached .json holds ")
            .append(library.trips.size)
            .append(if (library.trips.size == 1) " trip" else " trips")
            .append(" and restores in Trip Split under All trips, then Restore.")
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Trip Split backup " + Dates.fileStamp())
                putExtra(Intent.EXTRA_TEXT, body.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Send a backup"
        )
    }

    /** Writes a full backup into a document the user just picked. False if it couldn't. */
    fun writeTo(ctx: Context, uri: Uri, library: Library): Boolean = try {
        writeText(ctx, uri, Store.toJsonText(library))
        true
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't write backup to $uri", e)
        false
    }

    /**
     * Writes through a content Uri, truncating whatever was there. "wt" is the
     * mode that actually truncates on every provider; the fallbacks are for the
     * few that don't understand it.
     */
    internal fun writeText(ctx: Context, uri: Uri, text: String) {
        val resolver = ctx.contentResolver
        val out = openForWrite(ctx, uri, "wt")
            ?: openForWrite(ctx, uri, "rwt")
            ?: resolver.openOutputStream(uri, "w")
            ?: throw IOException("Couldn't open the file for writing")
        out.use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        }
    }

    private fun openForWrite(ctx: Context, uri: Uri, mode: String) = try {
        ctx.contentResolver.openOutputStream(uri, mode)
    } catch (e: Exception) {
        null
    }

    /** Reads a picked file. Null if it isn't a Trip Split backup. */
    fun readLibrary(ctx: Context, uri: Uri): Library? = try {
        val text = ctx.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().readText()
        }
        if (text.isNullOrBlank()) null else Store.fromJsonText(text)
    } catch (e: Exception) {
        null
    }
}

/**
 * Keeps a live copy of the whole library in a folder the user picked once —
 * typically one inside Google Drive, whose app then syncs it off the tablet —
 * rewritten a couple of seconds after every change.
 *
 * This goes through Android's document picker rather than the Drive API, so it
 * needs no Google sign-in, no cloud project, and works just as well with
 * OneDrive, Dropbox, an SD card or a USB stick. The folder permission is
 * persistent, so it survives reboots and app updates.
 */
object AutoBackup {

    private const val TAG = "TripSplit.AutoBackup"
    private const val PREFS = "autobackup"
    private const val KEY_TREE = "tree"
    private const val KEY_FILE = "file"
    private const val KEY_LABEL = "label"
    private const val KEY_LAST_OK = "lastOk"
    private const val KEY_LAST_TRY = "lastTry"
    private const val KEY_LAST_ERROR = "lastError"
    private const val URI_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    const val FILE_NAME = "trip-split-backup.json"

    data class Status(
        val linked: Boolean,
        val folderLabel: String,
        val lastOk: Long,
        val lastTry: Long,
        val lastError: String?
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(ctx: Context): Status {
        val p = prefs(ctx)
        return Status(
            linked = p.getString(KEY_TREE, null) != null,
            folderLabel = p.getString(KEY_LABEL, "") ?: "",
            lastOk = p.getLong(KEY_LAST_OK, 0L),
            lastTry = p.getLong(KEY_LAST_TRY, 0L),
            lastError = p.getString(KEY_LAST_ERROR, null)
        )
    }

    /** Remembers the folder and keeps the right to write to it across reboots. */
    fun link(ctx: Context, treeUri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(treeUri, URI_FLAGS)
        } catch (e: Exception) {
            Log.w(TAG, "Provider didn't grant a persistable permission", e)
        }
        prefs(ctx).edit()
            .putString(KEY_TREE, treeUri.toString())
            .putString(KEY_LABEL, describe(ctx, treeUri))
            .remove(KEY_FILE)
            .remove(KEY_LAST_OK)
            .remove(KEY_LAST_TRY)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun unlink(ctx: Context) {
        prefs(ctx).getString(KEY_TREE, null)?.let { tree ->
            try {
                ctx.contentResolver.releasePersistableUriPermission(Uri.parse(tree), URI_FLAGS)
            } catch (_: Exception) {}
        }
        prefs(ctx).edit().clear().apply()
    }

    /** "Google Drive › Trip Split", "This tablet › Documents", and so on. */
    private fun describe(ctx: Context, tree: Uri): String {
        val provider = when (tree.authority) {
            "com.google.android.apps.docs.storage" -> "Google Drive"
            "com.android.externalstorage.documents" -> "This tablet"
            "com.android.providers.downloads.documents" -> "Downloads"
            "com.microsoft.skydrive.content.StorageAccessProvider" -> "OneDrive"
            else -> tree.authority ?: "Folder"
        }
        val name = try {
            DocumentFile.fromTreeUri(ctx, tree)?.name
        } catch (e: Exception) {
            null
        }
        return if (name.isNullOrBlank()) provider else "$provider › $name"
    }

    /**
     * Writes the backup. Synchronous, so call it off the main thread. Returns
     * null on success, otherwise a short reason that is also kept for the
     * status line.
     */
    fun run(ctx: Context, library: Library): String? {
        val p = prefs(ctx)
        val treeText = p.getString(KEY_TREE, null) ?: return "Not linked to a folder"
        val tree = Uri.parse(treeText)
        val bytes = Store.toJsonText(library).toByteArray(Charsets.UTF_8)
        val now = System.currentTimeMillis()
        return try {
            var target = findOrCreate(ctx, tree, p.getString(KEY_FILE, null))
            Backup.writeText(ctx, target, String(bytes, Charsets.UTF_8))
            // A provider that ignored the truncate flag leaves the tail of the
            // old file behind. Start a fresh file rather than keep a broken one.
            val length = try { DocumentFile.fromSingleUri(ctx, target)?.length() ?: 0L } catch (_: Exception) { 0L }
            if (length > 0L && length != bytes.size.toLong()) {
                try { DocumentFile.fromSingleUri(ctx, target)?.delete() } catch (_: Exception) {}
                target = findOrCreate(ctx, tree, null)
                Backup.writeText(ctx, target, String(bytes, Charsets.UTF_8))
            }
            p.edit()
                .putString(KEY_FILE, target.toString())
                .putLong(KEY_LAST_OK, now)
                .putLong(KEY_LAST_TRY, now)
                .remove(KEY_LAST_ERROR)
                .apply()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Folder backup failed", e)
            val reason = (e.message ?: e.javaClass.simpleName).take(140)
            p.edit().putLong(KEY_LAST_TRY, now).putString(KEY_LAST_ERROR, reason).apply()
            reason
        }
    }

    /**
     * The file we wrote last time if it's still there, else one already in the
     * folder by that name, else a new one. Listing the folder only happens when
     * the remembered file is gone, so a huge Drive folder costs nothing per save.
     */
    private fun findOrCreate(ctx: Context, tree: Uri, remembered: String?): Uri {
        if (remembered != null) {
            val uri = Uri.parse(remembered)
            val doc = try { DocumentFile.fromSingleUri(ctx, uri) } catch (_: Exception) { null }
            if (doc != null && doc.exists()) return uri
        }
        val folder = DocumentFile.fromTreeUri(ctx, tree)
            ?: throw IOException("The folder is no longer reachable")
        if (!folder.exists()) throw IOException("The folder was moved or deleted")
        val existing = folder.findFile(FILE_NAME)
        if (existing != null) return existing.uri
        val created = folder.createFile("application/json", FILE_NAME)
            ?: throw IOException("The folder wouldn't accept a new file")
        return created.uri
    }
}
