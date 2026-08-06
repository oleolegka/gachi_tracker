package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.CelebrationMode
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The gallery against the real file system and real SharedPreferences.
 *
 * What is worth testing here is not "does a list hold items" but the two things that only
 * show up with actual files: that adding a picture makes an INDEPENDENT COPY (the whole
 * reason the feature works this way), and that the index and the folder are put back into
 * agreement when they drift — which they will, because a copy landing on disk and the
 * index being written are not one operation.
 *
 * NOT covered: everything that needs a screen (the picker, the overlay, the animation).
 * There are no Compose test dependencies in this project, and a photo picker cannot be
 * driven from a JVM test at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Robolectric keeps files and preferences across the methods of one class
        File(context.filesDir, "celebration").deleteRecursively()
        context.getSharedPreferences("celebration", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = GalleryStore(context)

    private fun bytes(size: Int, fill: Byte = 7): ByteArray = ByteArray(size) { fill }

    @Test
    fun `adding a picture copies the bytes into the app's own folder`() {
        val store = store()
        val payload = bytes(2048, fill = 3)

        val outcome = store.copyIn(ByteArrayInputStream(payload))

        val picture = (outcome as AddOutcome.Added).picture
        val copy = store.fileOf(picture)
        assertTrue("the copy must exist", copy.exists())
        assertArrayEquals(payload, copy.readBytes())
        assertEquals(listOf(picture.id), store.pictures.value.map { it.id })
        assertFalse("a new picture is not starred", picture.forRecords)
        // the copy lives inside the app's own storage, not wherever it came from
        assertTrue(copy.absolutePath.startsWith(context.filesDir.absolutePath))
    }

    @Test
    fun `the copy outlives the original`() = runTest {
        val original = File(context.cacheDir, "original.png").apply { writeBytes(bytes(512, fill = 9)) }
        val store = store()

        val outcome = store.add(Uri.fromFile(original))
        val picture = (outcome as AddOutcome.Added).picture
        assertTrue(original.delete())

        assertTrue("deleting the original must not touch the copy", store.fileOf(picture).exists())
        assertArrayEquals(bytes(512, fill = 9), store.fileOf(picture).readBytes())
    }

    @Test
    fun `an unreadable uri is reported and leaves nothing behind`() = runTest {
        val store = store()

        val outcome = store.add(Uri.fromFile(File(context.cacheDir, "does-not-exist.png")))

        assertEquals(AddOutcome.Unreadable, outcome)
        assertTrue(store.pictures.value.isEmpty())
        assertEquals(0, store.dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a picture over the size limit is refused and its half-written copy removed`() {
        val store = store()

        val outcome = store.copyIn(ByteArrayInputStream(bytes((MAX_PICTURE_BYTES + 1024).toInt())))

        assertEquals(AddOutcome.TooBig, outcome)
        assertTrue(store.pictures.value.isEmpty())
        assertEquals("no partial copy may survive", 0, store.dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `an empty stream is not a picture`() {
        val store = store()

        assertEquals(AddOutcome.Unreadable, store.copyIn(ByteArrayInputStream(ByteArray(0))))
        assertTrue(store.pictures.value.isEmpty())
        assertEquals(0, store.dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `removing a picture deletes the copy as well as the entry`() {
        val store = store()
        val picture = (store.copyIn(ByteArrayInputStream(bytes(64))) as AddOutcome.Added).picture
        val copy = store.fileOf(picture)

        store.remove(picture.id)

        assertTrue(store.pictures.value.isEmpty())
        assertFalse("the copy must go with the entry", copy.exists())
    }

    @Test
    fun `the gallery, the star and the mode survive a restart`() {
        val first = store()
        val a = (first.copyIn(ByteArrayInputStream(bytes(64, fill = 1))) as AddOutcome.Added).picture
        val b = (first.copyIn(ByteArrayInputStream(bytes(64, fill = 2))) as AddOutcome.Added).picture
        first.setForRecords(b.id, true)
        first.setMode(CelebrationMode.EVERY_SET)
        first.completeOnboarding()

        val second = store() // a fresh process would see exactly this

        assertEquals(listOf(a.id, b.id), second.pictures.value.map { it.id })
        assertEquals(setOf(b.id), second.pictures.value.filter { it.forRecords }.map { it.id }.toSet())
        assertEquals(CelebrationMode.EVERY_SET, second.mode.value)
        assertTrue(second.onboardingDone.value)
    }

    @Test
    fun `the default is records only and onboarding not done`() {
        val store = store()
        assertEquals(CelebrationMode.RECORDS_ONLY, store.mode.value)
        assertFalse(store.onboardingDone.value)
    }

    @Test
    fun `an indexed picture whose file vanished is forgotten on the next start`() {
        val first = store()
        val kept = (first.copyIn(ByteArrayInputStream(bytes(64, fill = 1))) as AddOutcome.Added).picture
        val lost = (first.copyIn(ByteArrayInputStream(bytes(64, fill = 2))) as AddOutcome.Added).picture
        assertTrue(first.fileOf(lost).delete()) // as if the storage had been cleaned up under us

        val second = store()

        assertEquals(listOf(kept.id), second.pictures.value.map { it.id })
    }

    @Test
    fun `a copy that never made it into the index is deleted rather than left forever`() {
        val first = store()
        first.copyIn(ByteArrayInputStream(bytes(64)))
        // a copy that landed on disk while the process died before the index was written
        val orphan = File(first.dir, "orphan").apply { writeBytes(bytes(32)) }

        val second = store()

        assertFalse("an unreachable copy must not stay on disk", orphan.exists())
        assertEquals(1, second.pictures.value.size)
    }

    @Test
    fun `picking honours the star, and an empty gallery picks nothing`() {
        val store = store()
        assertNull(store.pick(isRecord = false))
        assertNull(store.pick(isRecord = true))

        val plain = (store.copyIn(ByteArrayInputStream(bytes(64, fill = 1))) as AddOutcome.Added).picture
        val starred = (store.copyIn(ByteArrayInputStream(bytes(64, fill = 2))) as AddOutcome.Added).picture
        store.setForRecords(starred.id, true)

        repeat(20) {
            assertEquals(starred.id, store.pick(isRecord = true)!!.id)
            assertEquals(plain.id, store.pick(isRecord = false)!!.id)
        }
    }
}
