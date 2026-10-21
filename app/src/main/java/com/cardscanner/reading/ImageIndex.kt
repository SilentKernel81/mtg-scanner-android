package com.cardscanner.reading

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The picture index on the phone: the 96-byte fingerprint of every printing (the server's core/image_index.py `ImageIndex`), with the little table the server
 * serves beside it (image_index.printings_table: set, number, oracle id, finishes, name of each printing). Downloaded from the server (IndexDownload.kt) into a
 * folder laid out as the server's own: the two immutable files of a generation and manifest.json naming them with their SHA-256 - plus printings.tsv.gz.
 *
 * [load] checks what the server's loader checks: the descriptor (another fingerprint would make every distance wrong), the sizes, and both checksums - a
 * folder that fails any of them is no index (null), never a partial one. [distances] is the server's `ImageIndex.distances`: the Hamming distance in bits from
 * one fingerprint to every row, by XOR and popcount over 64-bit words (12 per row). The bytes are packed into the words the same way for the rows and for
 * the query, so the popcount of their XOR is the same as the server's byte-wise one.
 */
class ImageIndex private constructor(
    /** The generation the server published (manifest.json): a new build is a new generation */
    val generation: String,
    /** When the server built it, as its manifest says */
    val built: String?,
    /** The printing (Scryfall id) of each row */
    val ids: Array<String>,
    private val words: LongArray,
    /** What the decision needs of each printing, by id (rows the server's card data no longer has are missing from it - joined by id, never by position) */
    val facts: Map<String, Facts>,
) {
    /**
     * A printing as the server's printings table describes it. [releasedAt] and [frame] (in the table since 2026-10-21; null from an older one) say whether it
     * prints the foil marker at all - [printsAMarker]
     */
    data class Facts(val set: String, val number: String, val oracleId: String?, val finishes: List<String>, val name: String,
                     val releasedAt: String? = null, val frame: String? = null) {
        /**
         * printing_check.prints_a_marker: released from [MARKER_FROM] on, in the [MARKER_FRAME] frame - the printings the foil network was measured on.
         * False for anything missing (a table from before these columns: no marker reading, the finish is decided as before)
         */
        val printsAMarker get() = (releasedAt ?: "") >= MARKER_FROM && frame == MARKER_FRAME
    }

    private val rows: Map<String, Int> by lazy { HashMap<String, Int>(ids.size * 2).also { map -> ids.forEachIndexed { row, id -> map[id] = row } } }

    val count get() = ids.size

    /** The row of a printing, or null when the index does not have it */
    fun row(id: String): Int? = rows[id]

    /** The distance in bits from [fingerprint] (Fingerprint.hash: 96 bytes) to every row, in row order */
    fun distances(fingerprint: ByteArray): IntArray {
        require(fingerprint.size == HASH_BYTES) { "a fingerprint of $HASH_BYTES bytes expected" }
        val query = toWords(fingerprint)
        val out = IntArray(count)
        var w = 0
        for (row in 0 until count) {
            var bits = 0
            for (i in 0 until WORDS) bits += java.lang.Long.bitCount(words[w + i] xor query[i])
            out[row] = bits
            w += WORDS
        }
        return out
    }

    companion object {
        /** The fingerprint this code makes (image_index.DESCRIPTOR on the server): an index of another one is refused */
        const val DESCRIPTOR = "phash_rgb_v1"
        const val HASH_BYTES = 96
        private const val WORDS = HASH_BYTES / 8
        const val MANIFEST = "manifest.json"
        const val PRINTINGS = "printings.tsv.gz"
        /** The printings table's columns (image_index.TABLE_COLUMNS), read BY NAME: the first six are required, the last two (2026-10-21) optional */
        val TABLE_COLUMNS = listOf("id", "set", "number", "oracle_id", "finishes", "name", "released_at", "frame")
        private val REQUIRED_COLUMNS = TABLE_COLUMNS.take(6)

        /** printing_check.MARKER_FROM / MARKER_FRAME: the earliest set the OCR reads a marker on (The Lord of the Rings), in the modern frame only */
        const val MARKER_FROM = "2023-06-23"
        const val MARKER_FRAME = "2015"

        private fun toWords(bytes: ByteArray): LongArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
            return LongArray(buffer.remaining()).also { buffer.get(it) }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * The index in [folder], or null when there is none or it does not hold together (descriptor, sizes, checksums, the table): the caller then reads as if
         * there were no index - never with part of one. [open] says why.
         */
        fun load(folder: File): ImageIndex? = open(folder).index

        /** [index] or, when there is none, [why] (for Settings: "no index", "the checksum of ... does not hold", ...) */
        data class Opened(val index: ImageIndex?, val why: String?)

        /**
         * [manifest]: the manifest file to read - [MANIFEST], the published one, or a candidate a download checks BEFORE it is published (IndexDownload: a
         * download that does not load must leave the index that works in place)
         */
        fun open(folder: File, manifest: String = MANIFEST): Opened {
            fun no(why: String) = Opened(null, why)
            val manifestFile = File(folder, manifest)
            if (!manifestFile.isFile) return no("no picture index downloaded")
            return try {
                val manifest = JSONObject(manifestFile.readText())
                if (manifest.optString("descriptor") != DESCRIPTOR || manifest.optInt("hash_bytes") != HASH_BYTES)
                    return no("the index is of another fingerprint (${manifest.optString("descriptor")}): download it again")
                // file names only, never paths (as the server's loader requires)
                val hashesName = manifest.getString("hashes").takeIf { File(it).name == it } ?: return no("the manifest names a path")
                val idsName = manifest.getString("ids").takeIf { File(it).name == it } ?: return no("the manifest names a path")
                val hashes = File(folder, hashesName).readBytes()
                val idsBytes = File(folder, idsName).readBytes()
                if (sha256(hashes) != manifest.getString("hashes_sha256")) return no("the fingerprints' checksum does not hold")
                if (sha256(idsBytes) != manifest.getString("ids_sha256")) return no("the ids' checksum does not hold")
                val ids = String(idsBytes, Charsets.UTF_8).split(Regex("\\s+")).filter { it.isNotEmpty() }.toTypedArray()
                val count = manifest.getInt("count")
                if (ids.size != count || hashes.size != count * HASH_BYTES) return no("the index's sizes do not match its manifest")
                // the table of this download (IndexDownload names it per version, so a new download never rewrites the file a reader has open); a folder made
                // by hand (the parity test's) has the plain name
                val tableName = manifest.optString("printings").ifEmpty { PRINTINGS }.takeIf { File(it).name == it } ?: return no("the manifest names a path")
                val facts = readTable(File(folder, tableName)) ?: return no("the printings table is missing or not readable")
                Opened(ImageIndex(manifest.getString("generation"), manifest.optString("built").ifEmpty { null }, ids, toWords(hashes), facts), null)
            } catch (e: Exception) {
                no("the index could not be read (${e.javaClass.simpleName}: ${e.message})")
            }
        }

        /** The printings table (gzipped, tab-separated, a header line of TABLE_COLUMNS), or null when it is missing or not that table */
        fun readTable(file: File): Map<String, Facts>? {
            if (!file.isFile) return null
            val facts = HashMap<String, Facts>()
            GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext()) return null
                val header = iterator.next().split('\t')
                if (!header.containsAll(REQUIRED_COLUMNS)) return null
                val at = TABLE_COLUMNS.associateWith { header.indexOf(it) }          // -1: a column this table does not have
                for (line in iterator) {
                    if (line.isEmpty()) continue
                    val f = line.split('\t')
                    if (f.size != header.size) continue
                    fun column(name: String) = at.getValue(name).let { if (it < 0) null else f[it] }
                    facts[f[at.getValue("id")]] = Facts(set = column("set")!!, number = column("number")!!, oracleId = column("oracle_id")!!.ifEmpty { null },
                        finishes = column("finishes")!!.split(',').filter { it.isNotEmpty() }, name = column("name")!!,
                        releasedAt = column("released_at")?.ifEmpty { null }, frame = column("frame")?.ifEmpty { null })
                }
            }
            return facts
        }
    }
}
