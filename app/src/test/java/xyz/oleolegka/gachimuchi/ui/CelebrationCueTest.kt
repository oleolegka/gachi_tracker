package xyz.oleolegka.gachimuchi.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.toRef
import xyz.oleolegka.gachimuchi.domain.CelebrationCue
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.bodyweightOf
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.timer.TimerController

/**
 * What the ViewModel says when a set is written down.
 *
 * The one thing here that is easy to get wrong and impossible to see afterwards is the
 * ORDER: the record has to be judged against the journal BEFORE the set joins it. Get it
 * backwards and every set is compared against itself, nothing is ever greater than itself,
 * and records quietly stop existing — the app would look fine and simply never celebrate
 * one. Hence the second set in each test, which is a record only if the order is right.
 *
 * Real time and a real Room executor rather than a virtual test scheduler: the write goes
 * through a background thread that a test scheduler does not control, so cues are awaited
 * over a channel with a timeout instead of being assumed to have arrived.
 *
 * Nothing here shows a picture: whether one appears, and which, belongs to the gallery and
 * the overlay (data/GalleryStoreTest, domain/CelebrationTest). A cue is only the fact that
 * a set happened and whether it beat anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CelebrationCueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var viewModel: MainViewModel

    private val day = "2026-08-06"

    @Before
    fun setUp() {
        // viewModelScope insists on a main dispatcher; unconfined keeps it on this thread
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ActivityRepository(db)
        viewModel = MainViewModel(repo, ProgramRepository(db), TimerController(context))
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun ref(name: String, form: ExerciseForm, work: Double? = null, rest: Double? = null) =
        repo.exercise(repo.ensureExercise(name, form, work, rest))!!.toRef()

    /** Subscribes before the first set: cues are not buffered for a late listener. */
    private fun CoroutineScope.collectCues(into: Channel<CelebrationCue>): Job =
        launch(Dispatchers.Unconfined) { viewModel.celebrations.collect { into.send(it) } }

    private suspend fun Channel<CelebrationCue>.next(): CelebrationCue =
        withTimeout(5_000) { receive() }

    @Test
    fun `every set gets a cue and the one that beat the others says so`() = runBlocking {
        val cues = Channel<CelebrationCue>(Channel.UNLIMITED)
        val job = collectCues(cues)
        val bench = ref("Bench press", ExerciseForm.STRENGTH)

        viewModel.addSet(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        val first = cues.next()
        // the first weighted set of an exercise is a baseline, not a record (domain/Records)
        assertFalse(first.isRecord)
        assertNull(first.text)

        viewModel.addSet(strengthSetOf(bench, day, reps = 5, weightKg = 65.0))
        val second = cues.next()
        assertTrue("beating 60 kg for 5 is a record", second.isRecord)
        assertTrue(second.text!!.contains("1RM"))

        assertNotEquals("two celebrations must be two events", first.serial, second.serial)
        job.cancel()
    }

    @Test
    fun `a hold set is a set too, and its record is the added weight`() = runBlocking {
        val cues = Channel<CelebrationCue>(Channel.UNLIMITED)
        val job = collectCues(cues)
        val hangs = ref("Hangs", ExerciseForm.HOLD, work = 7.0, rest = 3.0)

        viewModel.addSet(holdSetOf(hangs, day, addedKg = 8.0, reps = 5))
        assertFalse(cues.next().isRecord)

        viewModel.addSet(holdSetOf(hangs, day, addedKg = 10.0, reps = 5))
        val second = cues.next()
        assertTrue(second.isRecord)
        assertTrue(second.text!!.contains("added weight"))
        job.cancel()
    }

    @Test
    fun `a weigh-in is not a set and gets no cue`() = runBlocking {
        val cues = Channel<CelebrationCue>(Channel.UNLIMITED)
        val job = collectCues(cues)
        val bench = ref("Bench press", ExerciseForm.STRENGTH)

        viewModel.addSet(bodyweightOf(day, weightKg = 74.2))
        // the set that follows is what proves the weigh-in produced nothing: its cue is
        // the FIRST one to arrive, so nothing was emitted in between
        viewModel.addSet(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))

        assertFalse("stepping on the scales is not a set", cues.next().isRecord)
        assertTrue("and it emitted nothing of its own", cues.tryReceive().isFailure)
        assertEquals("it is still written down, though", 2, repo.eventCount())
        job.cancel()
    }
}
