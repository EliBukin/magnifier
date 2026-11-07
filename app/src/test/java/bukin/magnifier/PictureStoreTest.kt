package bukin.magnifier

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** Saving to Pictures (Android 10+ and older), sharing through the FileProvider, and cleaning up. */
@RunWith(AndroidJUnit4::class)
class PictureStoreTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var media: FakeMediaStore
    private lateinit var source: File
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())
    private var savedZone: TimeZone? = null

    /** MediaStore's provider, as far as saving needs it: records rows, writes files, applies updates. */
    class FakeMediaStore : ContentProvider() {
        val inserted = mutableListOf<Pair<Uri, ContentValues>>()
        val updated = mutableListOf<Pair<Uri, ContentValues>>()
        val deleted = mutableListOf<Uri>()
        val files = mutableMapOf<Uri, File>()

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val row = Uri.withAppendedPath(uri, (inserted.size + 1).toString())
            inserted += row to ContentValues(values)
            return row
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updated += uri to ContentValues(values)
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted += uri
            return 1
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = files.getOrPut(uri) { File.createTempFile("media", ".jpg") }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
    }

    @Before
    fun setUp() {
        media = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        source = File(app.cacheDir, "source.jpg").apply { writeBytes(jpeg) }
        savedZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun onlyAndroidNineAndOlderNeedThePermission() {
        for (sdk in 24..28) assertTrue("sdk $sdk", PictureStore.needsWritePermission(sdk))
        for (sdk in 29..36) assertFalse("sdk $sdk", PictureStore.needsWritePermission(sdk))
    }

    @Test
    fun fileNamesAreTheDateAndTimeInWesternDigits() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        // 2026-11-07 15:30:12 UTC.
        assertEquals("BukinMagnifier_20261107_153012.jpg", PictureStore.fileName(1_794_065_412_000L))
    }

    @Test
    fun androidTenAndLaterSaveByRelativePathPendingUntilWritten() {
        val values = PictureStore.contentValues("a.jpg", 1234L, sdk = 30)
        assertEquals("Pictures/Bukin Magnifier", values.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, values.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals("a.jpg", values.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("image/jpeg", values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals(1234L, values.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
        @Suppress("DEPRECATION")
        assertFalse(values.containsKey(MediaStore.Images.Media.DATA))
    }

    @Test
    fun androidNineAndOlderSaveByFilePath() {
        val file = File("/storage/emulated/0/Pictures/Bukin Magnifier/a.jpg")
        val values = PictureStore.contentValues("a.jpg", 1234L, sdk = 28, legacyFile = file)
        @Suppress("DEPRECATION")
        assertEquals(file.absolutePath, values.getAsString(MediaStore.Images.Media.DATA))
        assertFalse(values.containsKey(MediaStore.Images.Media.RELATIVE_PATH))
        assertFalse(values.containsKey(MediaStore.Images.Media.IS_PENDING))
    }

    @Test
    @Config(sdk = [30])
    fun savingOnAndroidElevenWritesTheBytesThenClearsPending() {
        val uri = PictureStore.save(app.contentResolver, source, "BukinMagnifier_x.jpg", 1234L, sdk = 30)
        assertEquals(1, media.inserted.size)
        val (row, values) = media.inserted.single()
        assertEquals(uri, row)
        assertEquals(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Uri.parse(row.toString().substringBeforeLast('/')))
        assertEquals("Pictures/Bukin Magnifier", values.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, values.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(jpeg.toList(), media.files.getValue(row).readBytes().toList())
        assertEquals(0, media.updated.single().second.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertTrue(media.deleted.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun savingOnAndroidNineCopiesIntoPicturesAndNamesTheFile() {
        val pictures = File(app.filesDir, "Pictures/Bukin Magnifier")
        val first = PictureStore.save(app.contentResolver, source, "same.jpg", 1L, sdk = 28) { pictures }
        val second = PictureStore.save(app.contentResolver, source, "same.jpg", 2L, sdk = 28) { pictures }
        assertTrue(first != second)
        assertEquals(jpeg.toList(), File(pictures, "same.jpg").readBytes().toList())
        assertEquals(jpeg.toList(), File(pictures, "same_1.jpg").readBytes().toList())
        @Suppress("DEPRECATION")
        val paths = media.inserted.map { it.second.getAsString(MediaStore.Images.Media.DATA) }
        assertEquals(listOf(File(pictures, "same.jpg").absolutePath, File(pictures, "same_1.jpg").absolutePath), paths)
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString(), media.inserted[0].first.toString().substringBeforeLast('/'))
        assertNull(media.inserted[0].second.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
    }

    @Test
    fun sharedPicturesGoThroughTheFileProviderFromCacheSharedOnly() {
        val dir = PictureStore.shareDir(app).apply { mkdirs() }
        assertEquals(File(app.cacheDir, "shared"), dir)
        val file = File(dir, "BukinMagnifier_1.jpg").apply { writeBytes(jpeg) }
        val uri = PictureStore.shareUri(app, file)
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.fileprovider", uri.authority)
        assertEquals("/shared/BukinMagnifier_1.jpg", uri.path)
        // Nothing outside cache/shared can be handed out.
        for (outside in listOf(source, File(app.filesDir, "x.jpg"), File(app.dataDir, "shared_prefs/magnifier_settings.xml"))) {
            try {
                PictureStore.shareUri(app, outside)
                fail("$outside was shareable")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun theFileProviderExposesOnlyTheSharedFolder() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/file_paths.xml"))
        val children = doc.documentElement.childNodes
        val paths = (0 until children.length).mapNotNull { children.item(it) as? Element }
            .map { "${it.tagName} ${it.getAttribute("name")} ${it.getAttribute("path")}" }
        assertEquals(listOf("cache-path shared shared/"), paths)
    }

    @Test
    fun laterLaunchesDeleteOldSharedPicturesOnly() {
        val dir = File(app.cacheDir, "clean-test").apply { mkdirs() }
        val now = 10_000_000L
        val old = File(dir, "old.jpg").apply { writeBytes(jpeg); setLastModified(now - PictureStore.SHARE_MAX_AGE_MS - 1000) }
        val recent = File(dir, "recent.jpg").apply { writeBytes(jpeg); setLastModified(now - 60_000) }
        assertEquals(1, PictureStore.cleanShareDir(dir, now))
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertEquals(0, PictureStore.cleanShareDir(File(app.cacheDir, "missing"), now))
    }

    @Test
    fun theManifestAsksForStorageOnlyUpToAndroidNine() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val perms = doc.getElementsByTagName("uses-permission")
        val asked = (0 until perms.length).map { perms.item(it) as Element }
            .filter { it.getAttribute("tools:node") != "remove" }
            .map { it.getAttribute("android:name") to it.getAttribute("android:maxSdkVersion") }
        assertEquals(
            listOf("android.permission.CAMERA" to "", "android.permission.WRITE_EXTERNAL_STORAGE" to "28"),
            asked,
        )
    }
}
