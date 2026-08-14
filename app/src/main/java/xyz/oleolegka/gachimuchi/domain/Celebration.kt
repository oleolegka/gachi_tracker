package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Celebration: a picture the user added flashes up when a set is written down.
 *
 * ── What is stored and what is not ──────────────────────────────────────────────
 * NO PICTURE SHIPS WITH THE APP. The repository holds the mechanism and nothing else —
 * every image is one the user picked from their own phone, and the app keeps its own copy
 * of it (see data/GalleryStore.kt). That is a copyright decision as much as a technical
 * one, and it is why an empty gallery is a normal state rather than a broken one: with no
 * pictures the feature is simply silent, with no placeholder and no apology.
 *
 * ── Why the picking lives here ──────────────────────────────────────────────────
 * "Which picture, and should there be one at all" is pure arithmetic over a list, so it
 * sits in the domain with the rest of the reducers and is tested without Android.
 */

/** How often a picture is shown. The default is the quiet one. */
enum class CelebrationMode(val code: Int) {
    /** Never. The pictures stay, the app just says nothing. */
    OFF(0),

    /** Only when the set broke a record — the rare, loud case. */
    RECORDS_ONLY(1),

    /** Every set. */
    EVERY_SET(2),
    ;

    companion object {
        /** Unknown codes degrade to [RECORDS_ONLY] rather than throwing: this comes out of
         *  preferences written by an older build, and a decoration is never worth a crash. */
        fun fromCode(code: Int): CelebrationMode = entries.firstOrNull { it.code == code } ?: RECORDS_ONLY
    }
}

/**
 * One picture in the gallery.
 *
 * [id] is also the name of the file inside the app's own folder — the gallery has no
 * other identity, and nothing anywhere refers to the location the picture came from: the
 * original can be moved or deleted without the app noticing.
 *
 * [forRecords] is the user's mark for "save this one for a record". See [pickPicture] for
 * what the mark actually does.
 */
@Serializable
data class CelebrationPicture(
    @SerialName("id") val id: String,
    @SerialName("for_records") val forRecords: Boolean = false,
    @SerialName("added_at") val addedAt: String = "",
)

/** Whether a set that did or did not break a record deserves a picture under [mode]. */
fun shouldCelebrate(mode: CelebrationMode, isRecord: Boolean): Boolean = when (mode) {
    CelebrationMode.OFF -> false
    CelebrationMode.RECORDS_ONLY -> isRecord
    CelebrationMode.EVERY_SET -> true
}

/**
 * The picture to show, or null when there is nothing to show.
 *
 * The marked pictures are RESERVED for records: an ordinary set draws from the unmarked
 * ones, a record draws from the marked ones. Either pool falls back to the whole gallery
 * when it is empty, which is what makes marking optional — mark nothing and every set
 * draws from everything, mark everything and so does every record.
 */
fun pickPicture(
    pictures: List<CelebrationPicture>,
    isRecord: Boolean,
    random: Random = Random.Default,
): CelebrationPicture? {
    if (pictures.isEmpty()) return null
    val pool = pictures.filter { it.forRecords == isRecord }
    return (pool.ifEmpty { pictures }).random(random)
}

/**
 * Whether finishing this entry is worth a picture.
 *
 * Everything the user DOES qualifies: a rep count, a hang, a stretch, a run, or a plain check
 * mark for something with no metric. From the gym floor they all look the same — a thing just
 * finished — and that is the only question this asks.
 *
 * [Bodyweight] is the one form that does not: it is a step on the scales, not a thing done,
 * and it is already the one entry the app is careful to keep out of rest, records and volume
 * alike (see domain/Records.kt, domain/Analytics.kt). A picture on top of a weigh-in would be
 * the odd one out, not a fix — and it would land on the one number nobody wants cheered.
 *
 * ── [Duration] and [Cardio] used to be left out, and that was wrong ──────────────
 * The argument was that a run or a stretch is filed as its own total rather than a set, and
 * that nobody had asked. Reported from a phone, 2026-08-14: "при записи подхода на типе
 * duration не выдаёт ободряющую картинку". The distinction was one the model makes and the
 * person training does not — a stretch entered is a stretch done — so it is gone, and cardio
 * goes with it, having no ground to stand on that duration did not.
 *
 * This deliberately does not reuse [startsRest] (§13.9): that one answers "does a rest begin
 * now" — a check-in has nothing to rest between — and the two questions are free to part ways
 * even while they happen to name the same forms.
 */
fun celebratedByPicture(form: ActivityForm): Boolean = form !is Bodyweight

/**
 * The request to show something, as the ViewModel hands it to the screen.
 *
 * [serial] exists so that two identical celebrations in a row are still two events: the
 * overlay restarts its animation on a new serial, and without it a second record with the
 * same text would silently do nothing.
 */
data class CelebrationCue(
    val serial: Long,
    val isRecord: Boolean,
    val text: String? = null,
)
