package com.kinescope.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * Everything related to getting a finished download out of the app's
 * private cache and into the public Downloads collection, so it
 * shows up in any file manager / gallery / media player — not just
 * inside this app.
 *
 * Requires minSdk 29 (MediaStore.Downloads didn't exist before
 * Android 10). minSdk was bumped in Phase 3 for exactly this reason
 * — see ROADMAP.md.
 */
object MediaStorage {

    /**
     * Copies [tempFile] into the public Downloads/&lt;subfolder&gt; folder
     * via MediaStore and deletes the temp copy. [subfolder] defaults to
     * whatever's saved in Settings (see Phase 6). [displayName] defaults
     * to the temp file's own name, but callers can override it — see
     * DownloadService.runJob(), which passes a humanized title instead
     * of the raw job-id-tagged temp filename (ROADMAP.md Step 4).
     * Returns the resulting content Uri, or null on failure.
     */
    fun publish(
        context: Context,
        tempFile: java.io.File,
        mimeType: String,
        subfolder: String = Settings.getDownloadSubfolder(context),
        displayName: String = tempFile.name
    ): Uri? {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            // ROADMAP.md Step 4 [MEDIUM, fixed]: trailing slash must
            // match listPublished()'s query exactly below -- relying
            // on MediaStore to normalize a missing one on insert the
            // same way across every OEM is exactly the kind of
            // assumption this project has already been burned by
            // (see the Compose BOM fix in patch 01).
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subfolder/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            val opened = resolver.openOutputStream(itemUri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            }
            if (opened == null) {
                resolver.delete(itemUri, null, null)
                return null
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)

            tempFile.delete()
            itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    /** Lists items this app has previously published, newest first. */
    fun listPublished(
        context: Context,
        subfolder: String = Settings.getDownloadSubfolder(context)
    ): List<LibraryItem> {
        val resolver = context.contentResolver
        val items = mutableListOf<LibraryItem>()

        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$subfolder/")
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"

        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                items += LibraryItem(
                    uri = uri,
                    displayName = cursor.getString(nameCol) ?: "(untitled)",
                    mimeType = cursor.getString(mimeCol) ?: "*/*"
                )
            }
        }
        return items
    }

    /**
     * Deletes a previously published item from MediaStore. Returns
     * true on success. ROADMAP.md Step 6.5: backs the Library row's
     * overflow-menu Delete action -- no RecoverableSecurityException
     * handling needed since these are rows this app itself inserted,
     * and apps always have delete permission for their own rows on
     * API 29+.
     */
    fun delete(context: Context, item: LibraryItem): Boolean {
        return try {
            context.contentResolver.delete(item.uri, null, null) > 0
        } catch (e: SecurityException) {
            false
        }
    }
}

data class LibraryItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String
)
