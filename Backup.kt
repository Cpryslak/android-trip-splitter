package com.tripsplit.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Off-device copies. The local file is always the real ledger; this makes a copy
 * you can send somewhere that isn't this tablet, which is the only thing that
 * survives losing it.
 *
 * A backup always contains every trip, so restoring one can never quietly cost
 * you a trip that wasn't in the file.
 */
object Backup {

    private const val AUTHORITY = "com.tripsplit.app.files"

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())

    fun dateLabel(millis: Long): String =
        if (millis <= 0L) "" else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

    private fun writeToCache(ctx: Context, library: Library): Uri {
        val dir = File(ctx.cacheDir, "backups")
        dir.mkdirs()
        val file = File(dir, "trip-split-backup-${stamp()}.json")
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
                putExtra(Intent.EXTRA_SUBJECT, "Trip Split backup " + stamp())
                putExtra(Intent.EXTRA_TEXT, body.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Send a backup"
        )
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
