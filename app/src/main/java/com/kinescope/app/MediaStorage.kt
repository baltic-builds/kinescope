package com.kinescope.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

object MediaStorage {

    /**
     * Publishes a completed private workspace file atomically through
     * MediaStore. The caller owns the workspace/source lifecycle: this method
     * never deletes [tempFile], so a late Pause/Stop or a failed commit can
     * still recover without re-downloading the media.
     */
    fun publish(
        context: Context,
        tempFile: File,
        preferredMimeType: String,
        subfolder: String,
        displayName: String = tempFile.name,
        onPendingUri: (Uri) -> Unit = {},
        shouldCancel: () -> Boolean = { false }
    ): Uri? {
        val resolver = context.contentResolver
        val mimeType = mimeTypeFor(displayName, preferredMimeType)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath(subfolder))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (itemUri == null) {
            AppLog.e("MediaStorage", "MediaStore insert returned null")
            return null
        }
        onPendingUri(itemUri)

        return try {
            if (shouldCancel()) throw PublishCancelled()
            val wrote = resolver.openOutputStream(itemUri)?.use { out ->
                tempFile.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (shouldCancel()) throw PublishCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
                true
            } ?: false
            if (!wrote) {
                resolver.delete(itemUri, null, null)
                AppLog.e("MediaStorage", "Could not open MediaStore output stream")
                return null
            }

            if (shouldCancel()) throw PublishCancelled()
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            val committed = resolver.update(itemUri, values, null, null) > 0
            if (!committed) {
                resolver.delete(itemUri, null, null)
                AppLog.e("MediaStorage", "MediaStore pending row could not be committed")
                return null
            }

            if (shouldCancel()) {
                resolver.delete(itemUri, null, null)
                return null
            }
            AppLog.i("MediaStorage", "Published media to Downloads/$subfolder")
            itemUri
        } catch (_: PublishCancelled) {
            runCatching { resolver.delete(itemUri, null, null) }
            AppLog.i("MediaStorage", "Publication cancelled before completion")
            null
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            AppLog.e("MediaStorage", "Failed to publish media", e)
            null
        }
    }

    /** Lists media from every Kinescope destination folder, newest first. */
    fun listPublished(context: Context): List<LibraryItem> {
        val resolver = context.contentResolver
        val items = linkedMapOf<String, LibraryItem>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.SIZE
        )

        Settings.getKnownDownloadSubfolders(context).forEach { subfolder ->
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.IS_PENDING} = 0"
            val selectionArgs = arrayOf(relativePath(subfolder))
            runCatching {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        items[uri.toString()] = LibraryItem(
                            uri = uri,
                            displayName = cursor.getString(nameCol) ?: context.getString(R.string.library_untitled),
                            mimeType = cursor.getString(mimeCol) ?: "*/*",
                            dateAddedSeconds = cursor.getLong(dateCol),
                            sizeBytes = cursor.getLong(sizeCol)
                        )
                    }
                }
            }.onFailure { AppLog.e("MediaStorage", "Library query failed", it) }
        }

        return items.values.sortedByDescending { it.dateAddedSeconds }
    }

    fun delete(context: Context, item: LibraryItem): Boolean {
        return try {
            val deleted = context.contentResolver.delete(item.uri, null, null) > 0
            if (!deleted) AppLog.w("MediaStorage", "Delete returned no rows")
            deleted
        } catch (e: Exception) {
            AppLog.e("MediaStorage", "Delete failed", e)
            false
        }
    }

    fun deletePending(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { AppLog.e("MediaStorage", "Could not clean stale pending row", it) }
    }

    fun deleteUri(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        AppLog.e("MediaStorage", "Could not remove a just-published row", e)
        false
    }

    private fun relativePath(subfolder: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$subfolder/"

    private fun mimeTypeFor(displayName: String, fallback: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
        return extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: fallback
    }

    private class PublishCancelled : RuntimeException()
}

data class LibraryItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAddedSeconds: Long,
    val sizeBytes: Long
)
