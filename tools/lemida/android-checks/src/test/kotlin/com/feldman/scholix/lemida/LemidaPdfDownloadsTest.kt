package com.feldman.scholix.lemida

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LemidaPdfDownloadsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var source: File
    private lateinit var provider: DownloadsProvider

    @Before fun prepare() {
        source = File.createTempFile("lemida-source", ".pdf", context.cacheDir)
        source.writeBytes("%PDF-1.7\n".toByteArray() + ByteArray(100_000) { (it % 256).toByte() })
        provider = DownloadsProvider(File.createTempFile("lemida-saved", ".pdf", context.cacheDir))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }
    @After fun cleanup() { source.delete(); provider.target.delete() }

    @Test fun savedPdfSurvivesPreviewDeletionAndBecomesVisibleOnlyAfterCopy() = runBlocking {
        val original = source.readBytes()
        val uri = LemidaPdfDownloads.save(context, source, "תרגיל 1")
        source.delete()
        assertArrayEquals(original, provider.target.readBytes())
        assertEquals(provider.uri, uri)
        assertEquals("תרגיל 1.pdf", provider.inserted!!.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals("application/pdf", provider.inserted!!.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals("Download/Scholix", provider.inserted!!.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(original.size.toLong(), provider.bytesAtPublication)
        assertTrue(provider.published)
        assertFalse(provider.deleted)
    }

    @Test fun unavailableOutputRemovesPendingDownload() = runBlocking {
        provider.failOpen = true
        try { LemidaPdfDownloads.save(context, source, "Questions"); fail("Must report storage failure") }
        catch (_: IOException) { }
        assertTrue(provider.deleted)
        assertFalse(provider.published)
        assertTrue(source.exists())
    }

    @Test fun failedPublicationRemovesUnfinishedDownload() = runBlocking {
        provider.failUpdate = true
        try { LemidaPdfDownloads.save(context, source, "Questions"); fail("Must report publication failure") }
        catch (_: IOException) { }
        assertTrue(provider.deleted)
        assertFalse(provider.published)
    }

    @Test fun invalidPreviewDoesNotCreateADownload() = runBlocking {
        source.writeText("<html>Sign in</html>")
        try { LemidaPdfDownloads.save(context, source, "Questions"); fail("Must reject invalid PDF") }
        catch (_: IOException) { }
        assertNull(provider.inserted)
    }

    @Test fun cancellationRemovesPendingDownloadWithoutPublishing() = runBlocking {
        val saving = launch(start = CoroutineStart.LAZY) { LemidaPdfDownloads.save(context, source, "Questions") }
        provider.onOpen = { saving.cancel() }
        saving.start()
        saving.join()
        assertTrue(saving.isCancelled)
        assertTrue(provider.deleted)
        assertFalse(provider.published)
    }

    private class DownloadsProvider(val target: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/downloads/42")
        var inserted: ContentValues? = null
        var deleted = false
        var published = false
        var bytesAtPublication = 0L
        var failOpen = false
        var failUpdate = false
        var onOpen: () -> Unit = {}
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            return this.uri
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failOpen) throw FileNotFoundException("Storage unavailable")
            onOpen()
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failUpdate) return 0
            bytesAtPublication = target.length()
            published = values?.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted = true
            target.delete()
            return 1
        }
        override fun getType(uri: Uri) = "application/pdf"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
