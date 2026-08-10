package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The exercise picture store against the real file system — the same shape of test
 * [GalleryStoreTest] runs for the celebration gallery, minus everything that exists there only
 * because of the gallery's own index (the star, the mode, `reconcile`): this store keeps none
 * of that, the owning [xyz.oleolegka.gachimuchi.data.db.ExerciseEntity] row already is the index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExercisePictureStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        File(context.filesDir, "exercise_pictures").deleteRecursively()
    }

    private fun store() = ExercisePictureStore(context)

    private fun bytes(size: Int, fill: Byte = 7): ByteArray = ByteArray(size) { fill }

    @Test
    fun `adding a picture copies the bytes into the app's own folder`() {
        val store = store()
        val payload = bytes(2048, fill = 3)

        val outcome = store.copyIn(ByteArrayInputStream(payload))

        val id = (outcome as ExercisePictureOutcome.Added).pictureId
        val copy = store.fileOf(id)
        assertTrue("the copy must exist", copy.exists())
        assertArrayEquals(payload, copy.readBytes())
        assertTrue(copy.absolutePath.startsWith(context.filesDir.absolutePath))
    }

    @Test
    fun `the copy outlives the original`() = runTest {
        val original = File(context.cacheDir, "machine.jpg").apply { writeBytes(bytes(512, fill = 9)) }
        val store = store()

        val outcome = store.add(Uri.fromFile(original))
        val id = (outcome as ExercisePictureOutcome.Added).pictureId
        assertTrue(original.delete())

        assertTrue("deleting the original must not touch the copy", store.fileOf(id).exists())
        assertArrayEquals(bytes(512, fill = 9), store.fileOf(id).readBytes())
    }

    @Test
    fun `an unreadable uri is reported and leaves nothing behind`() = runTest {
        val store = store()

        val outcome = store.add(Uri.fromFile(File(context.cacheDir, "does-not-exist.jpg")))

        assertEquals(ExercisePictureOutcome.Unreadable, outcome)
        assertEquals(0, store.dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a picture over the size limit is refused and its half-written copy removed`() {
        val store = store()

        val outcome = store.copyIn(ByteArrayInputStream(bytes((MAX_PICTURE_BYTES + 1024).toInt())))

        assertEquals(ExercisePictureOutcome.TooBig, outcome)
        assertEquals("no partial copy may survive", 0, store.dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `an empty stream is not a picture`() {
        val store = store()

        assertEquals(ExercisePictureOutcome.Unreadable, store.copyIn(ByteArrayInputStream(ByteArray(0))))
        assertEquals(0, store.dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `removing a picture deletes its file`() {
        val store = store()
        val id = (store.copyIn(ByteArrayInputStream(bytes(64))) as ExercisePictureOutcome.Added).pictureId
        val copy = store.fileOf(id)

        store.remove(id)

        assertFalse("the file must go", copy.exists())
    }

    @Test
    fun `two pictures added in a row do not collide`() {
        val store = store()

        val a = (store.copyIn(ByteArrayInputStream(bytes(64, fill = 1))) as ExercisePictureOutcome.Added).pictureId
        val b = (store.copyIn(ByteArrayInputStream(bytes(64, fill = 2))) as ExercisePictureOutcome.Added).pictureId

        assertTrue(a != b)
        assertArrayEquals(bytes(64, fill = 1), store.fileOf(a).readBytes())
        assertArrayEquals(bytes(64, fill = 2), store.fileOf(b).readBytes())
    }
}
