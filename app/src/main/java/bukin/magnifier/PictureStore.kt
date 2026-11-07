package bukin.magnifier

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where saved and shared pictures go.
 *
 * - **Save:** a JPEG in the phone's Pictures folder, `Pictures/Bukin Magnifier`, through
 *   MediaStore. Android 10 and later need no permission for that. Android 7-9 need
 *   `WRITE_EXTERNAL_STORAGE`, asked for only when the user saves (the manifest limits it to
 *   those versions with maxSdkVersion 28).
 * - **Share:** the same JPEG from the app's cache (`cache/shared/`), handed to the share sheet
 *   through a FileProvider with a read grant. Old ones are deleted on later launches.
 *
 * Neither is ever in the app's backup: backups hold only the settings file.
 */
object PictureStore {
    const val FOLDER = "Bukin Magnifier"
    const val MIME_TYPE = "image/jpeg"

    /** The folder under the phone's storage, as MediaStore's RELATIVE_PATH (Android 10+). */
    val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$FOLDER"

    /** The cache subfolder the FileProvider exposes (res/xml/file_paths.xml), and nothing else. */
    const val SHARE_DIR = "shared"

    /** Shared pictures older than this are deleted when the app starts. */
    const val SHARE_MAX_AGE_MS = 10 * 60 * 1000L

    /** Saving to Pictures needs the storage permission up to Android 9 (API 28). */
    fun needsWritePermission(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk <= Build.VERSION_CODES.P

    /** "BukinMagnifier_20261107_153012.jpg", in Western digits whatever the language. */
    fun fileName(millis: Long): String =
        "BukinMagnifier_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(millis)) + ".jpg"

    /**
     * The MediaStore row for a new picture. Android 10+: by relative path, pending until the
     * bytes are written. Older: by the file's full path ([legacyFile]), written beforehand.
     */
    fun contentValues(name: String, takenMillis: Long, sdk: Int, legacyFile: File? = null): ContentValues =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.DATE_TAKEN, takenMillis)
            if (sdk >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                requireNotNull(legacyFile) { "Android 9 and older save by file path" }
                @Suppress("DEPRECATION") // The way to name the file before Android 10.
                put(MediaStore.Images.Media.DATA, legacyFile.absolutePath)
            }
        }

    /** The collection new pictures go into. */
    fun collection(sdk: Int): Uri = if (sdk >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    /** The public Pictures/Bukin Magnifier folder, for Android 9 and older. */
    @Suppress("DEPRECATION") // Only used before Android 10, where it is the way.
    fun legacyFolder(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)

    /** A file name in [dir] that isn't taken yet: name.jpg, name_1.jpg, ... */
    fun freeFile(dir: File, name: String): File {
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "jpg")
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "${stem}_${n++}.$ext")
        return file
    }

    /**
     * Copies the finished JPEG [source] into Pictures/Bukin Magnifier as [name], and returns its
     * MediaStore address. On Android 9 and older the caller has the storage permission.
     */
    @Throws(IOException::class)
    fun save(
        resolver: ContentResolver,
        source: File,
        name: String,
        takenMillis: Long,
        sdk: Int = Build.VERSION.SDK_INT,
        legacyDir: () -> File = ::legacyFolder,
    ): Uri {
        if (sdk >= Build.VERSION_CODES.Q) {
            val uri = resolver.insert(collection(sdk), contentValues(name, takenMillis, sdk))
                ?: throw IOException("MediaStore refused the new picture")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("can't write $uri")).use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = legacyDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't make $dir")
        val file = freeFile(dir, name)
        try {
            source.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            return resolver.insert(collection(sdk), contentValues(file.name, takenMillis, sdk, file))
                ?: throw IOException("MediaStore refused $file")
        } catch (e: IOException) {
            file.delete()
            throw e
        }
    }

    fun shareDir(context: Context): File = File(context.cacheDir, SHARE_DIR)

    /** The content:// address the share sheet gets for [file] (which must be in [shareDir]). */
    fun shareUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * Deletes the pictures in [dir] last written more than [maxAgeMs] before [now] (an app that
     * was just shared to may still be reading a newer one). Returns how many went.
     */
    fun cleanShareDir(dir: File, now: Long, maxAgeMs: Long = SHARE_MAX_AGE_MS): Int {
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (file.isFile && now - file.lastModified() > maxAgeMs && file.delete()) deleted++
        }
        return deleted
    }
}
