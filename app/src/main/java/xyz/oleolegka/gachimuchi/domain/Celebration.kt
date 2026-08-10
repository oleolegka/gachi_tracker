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
 * A [StrengthSet], a [HoldSet] and a [Tick] all qualify: each is a card being marked done —
 * a rep count, a hang, or a plain check mark for something with no metric — and from the
 * gym floor the three look the same, a thing the user just finished. [Bodyweight] is the
 * one form that does not: it is a step on the scales, not a card finished, and it is
 * already the one entry the app is careful to keep out of rest, records and volume alike
 * (see domain/Records.kt, domain/Analytics.kt) — a picture on top of it would be the odd
 * one out, not a fix.
 *
 * [Cardio] and [Duration] are left out too, unchanged from before this was written down: a
 * run or a stretch is filed as its own total rather than a set, and nobody has asked for a
 * picture on those yet. That is a narrower question than this one and is not decided here.
 *
 * This deliberately does not reuse [startsRest], which today covers only [StrengthSet] and
 * [HoldSet]: that one answers "does a rest begin now" — a check-in has nothing to rest
 * between — and the two questions are free to part ways.
 */
fun celebratedByPicture(form: ActivityForm): Boolean =
    form is StrengthSet || form is HoldSet || form is Tick

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
