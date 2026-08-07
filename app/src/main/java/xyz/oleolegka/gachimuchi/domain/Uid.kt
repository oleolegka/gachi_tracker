package xyz.oleolegka.gachimuchi.domain

import kotlin.random.Random

/**
 * The stable identity of a stored row: a UUIDv7, generated once and never reused.
 *
 * ── Why a row needs one at all ──────────────────────────────────────────────────
 * The journal's whole promise (docs/architecture.md) is that two devices merge by UNION of
 * immutable events rather than by resolving field conflicts. Immutable is not enough for
 * that: the rows also have to be SELF-IDENTIFYING. Every link between rows used to be a
 * local `INTEGER PRIMARY KEY AUTOINCREMENT`, which is a counter of how many rows this
 * particular phone has written — two phones hand out the same numbers to different training,
 * so a union of two journals silently welds unrelated rows together. There was no merge that
 * could have worked, only one that had not been attempted yet.
 *
 * The same applies to writing the journal out to a file, which is the next step after this
 * one: an export whose internal references are one device's autoincrements can only ever be
 * imported back into that same device.
 *
 * ── Why version 7 and not version 4 ─────────────────────────────────────────────
 * A v7 uuid begins with a 48-bit millisecond timestamp, so the plain lexicographic order of
 * the strings is the order the rows were created in. That buys two things that matter here:
 * a merged journal sorts into a sensible order with no extra column to sort by, and an index
 * over these keys appends at its right-hand edge instead of scattering writes across the
 * whole tree the way random v4 keys do.
 *
 * It is NOT a secret and NOT a clock anybody should read. The timestamp is coarse and can be
 * supplied by the caller (the migration seeds it from the row's own recorded time, so the
 * ids of old rows still sort like the rows do); the honest time of an event stays in its own
 * columns.
 */

/** The RFC 9562 version nibble that says "this is a v7 uuid". */
private const val VERSION_7 = 0x70

/** The RFC 9562 variant bits ("10xx") every modern uuid carries. */
private const val VARIANT_RFC = 0x80

/**
 * A fresh UUIDv7 as the canonical 8-4-4-4-12 lowercase string.
 *
 * [atMillis] is the instant to stamp into the leading 48 bits — normally now, and the row's
 * own recorded time when old rows are being given ids after the fact. [random] is injectable
 * so a test can pin the whole value; nothing about correctness depends on the randomness
 * being cryptographic, only on collisions being implausible, which 74 random bits deliver at
 * personal scale by an enormous margin.
 */
fun newUid(atMillis: Long = System.currentTimeMillis(), random: Random = Random.Default): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)

    // 48 bits of milliseconds, most significant byte first, so string order is time order
    for (i in 0 until 6) {
        bytes[i] = ((atMillis shr (8 * (5 - i))) and 0xFF).toByte()
    }
    bytes[6] = ((bytes[6].toInt() and 0x0F) or VERSION_7).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or VARIANT_RFC).toByte()

    val hex = StringBuilder(36)
    for ((index, b) in bytes.withIndex()) {
        if (index == 4 || index == 6 || index == 8 || index == 10) hex.append('-')
        hex.append(HEX[(b.toInt() shr 4) and 0x0F])
        hex.append(HEX[b.toInt() and 0x0F])
    }
    return hex.toString()
}

private const val HEX = "0123456789abcdef"

/** Whether a string is shaped like the uids this app writes. Cheap, and not a parser. */
fun isUid(value: String): Boolean = UID_SHAPE.matches(value)

private val UID_SHAPE =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
