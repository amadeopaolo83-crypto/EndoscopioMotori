package com.ispezioni.endoscopio

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Salvataggio e lettura di foto/video nella cartella "Ispezioni Motori" della galleria. */
object MediaRepo {
    const val FOLDER = "Ispezioni Motori"

    data class Item(val uri: Uri, val name: String, val isVideo: Boolean, val dateAdded: Long)

    /** ISP_20261106_064100[_nota].ext */
    fun buildName(note: String, ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        val clean = note.trim()
            .replace(Regex("[^A-Za-z0-9À-ÿ]+"), "_")
            .trim('_')
            .take(40)
        return if (clean.isEmpty()) "ISP_$ts.$ext" else "ISP_${ts}_$clean.$ext"
    }

    /** Valore EXIF per rotazione (oraria) + specchio orizzontale applicato prima. */
    private fun exifOrientation(rotation: Int, mirror: Boolean): Int = when {
        !mirror && rotation == 0 -> ExifInterface.ORIENTATION_NORMAL
        !mirror && rotation == 90 -> ExifInterface.ORIENTATION_ROTATE_90
        !mirror && rotation == 180 -> ExifInterface.ORIENTATION_ROTATE_180
        !mirror && rotation == 270 -> ExifInterface.ORIENTATION_ROTATE_270
        mirror && rotation == 0 -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
        mirror && rotation == 90 -> ExifInterface.ORIENTATION_TRANSVERSE
        mirror && rotation == 180 -> ExifInterface.ORIENTATION_FLIP_VERTICAL
        else -> ExifInterface.ORIENTATION_TRANSPOSE
    }

    /** Scrive la rotazione/specchio nei metadati del JPG (senza ricomprimere). */
    fun writeExifOrientation(file: File, rotation: Int, mirror: Boolean) {
        val o = exifOrientation(rotation, mirror)
        if (o == ExifInterface.ORIENTATION_NORMAL) return
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, o.toString())
        exif.saveAttributes()
    }

    /** Copia il file nella galleria. Ritorna l'Uri oppure null se fallisce. */
    @Suppress("DEPRECATION")
    fun saveToGallery(ctx: Context, src: File, displayName: String, isVideo: Boolean): Uri? {
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val resolver = ctx.contentResolver
        val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (isVideo)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            return try {
                resolver.openOutputStream(uri)!!.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), FOLDER)
            dir.mkdirs()
            var dest = File(dir, displayName)
            var n = 1
            while (dest.exists()) {
                dest = File(dir, displayName.substringBeforeLast('.') + "_$n." + displayName.substringAfterLast('.'))
                n++
            }
            return try {
                src.copyTo(dest, overwrite = false)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, dest.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, dest.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                }
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                resolver.insert(collection, values)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Elenco di foto e video nella cartella, dal più recente. */
    @Suppress("DEPRECATION")
    fun list(ctx: Context): List<Item> {
        val files = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED
        )
        val mediaTypes = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND $mediaTypes"
            args = arrayOf("${Environment.DIRECTORY_PICTURES}/$FOLDER%", "${Environment.DIRECTORY_MOVIES}/$FOLDER%")
        } else {
            selection = "${MediaStore.MediaColumns.DATA} LIKE ? AND $mediaTypes"
            args = arrayOf("%/$FOLDER/%")
        }
        val out = ArrayList<Item>()
        ctx.contentResolver.query(
            files, proj, selection, args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: "?"
                val video = c.getInt(2) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out.add(Item(ContentUris.withAppendedId(base, id), name, video, c.getLong(3)))
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    fun thumbnail(ctx: Context, item: Item, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.loadThumbnail(item.uri, android.util.Size(sizePx, sizePx), null)
        } else {
            val id = ContentUris.parseId(item.uri)
            if (item.isVideo)
                MediaStore.Video.Thumbnails.getThumbnail(
                    ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            else
                MediaStore.Images.Thumbnails.getThumbnail(
                    ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                )
        }
    } catch (e: Exception) {
        null
    }

    fun delete(ctx: Context, item: Item): Boolean = try {
        ctx.contentResolver.delete(item.uri, null, null) > 0
    } catch (e: Exception) {
        false
    }
}
