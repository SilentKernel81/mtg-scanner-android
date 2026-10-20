package com.cardscanner.reading

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The 96-byte fingerprint of a card picture - a port of core/image_index.py in the scanner server's
 * repository (`prepare`, `hash_card`, `similarity`; descriptor `phash_rgb_v1`). It is ONE function for
 * Scryfall's pictures and for a capture: change a step here or there and every index built before is
 * wrong, so the two must stay the same.
 *
 * The recipe, per picture (RGB, the card filling it): cut the edge off (2.5% above and below, 3.5% left
 * and right), scale to 224 x 312 (INTER_AREA), stretch each colour's 2nd..98th percentile to 0..255,
 * scale to 64 x 64 (INTER_AREA), take each colour's DCT and its 16 x 16 lowest frequencies, and keep
 * one bit per frequency: above the median of the 255 that are not the first (0, 0) or not. 3 colours x
 * 256 bits = 96 bytes.
 *
 * The arithmetic that is not OpenCV (the stretch, the median and the packing) is in plain Kotlin so the
 * JVM tests can pin it against the Python code (FingerprintMathTest); the OpenCV steps are compared on
 * the emulator (FingerprintParityTest, instrumented). NOT USED BY THE APP YET - see Decide.kt.
 */
object Fingerprint {
    const val DESCRIPTOR = "phash_rgb_v1"
    const val HASH_BYTES = 96
    const val HASH_BITS = HASH_BYTES * 8

    /** The size a card picture is scaled to first: width, height (cv2's order) */
    const val CARD_W = 224
    const val CARD_H = 312

    /** The part kept: y from, y to, x from, x to (fractions) */
    private val EDGE = doubleArrayOf(0.025, 0.975, 0.035, 0.965)

    private const val SMALL = 64        // the picture the DCT is taken of: 64 x 64
    private const val LOW = 16          // its 16 x 16 lowest frequencies

    // ------------------------------------------------------------------------------------------
    // The arithmetic that is not OpenCV
    // ------------------------------------------------------------------------------------------

    /**
     * numpy.percentile(plane, q) of a plane of bytes, "linear" method, to the last bit: numpy's
     * (n - 1) * (q / 100) as the position between two neighbours of the sorted values, and its
     * interpolation, which counts from the upper neighbour when the fraction is 0.5 or more
     * (so the result does not depend on which end it is computed from). [histogram] counts each byte
     * value 0..255 over [count] values; counting instead of sorting keeps a 70,000-value plane cheap.
     */
    fun percentile(histogram: IntArray, count: Int, q: Double): Double {
        // The k-th smallest value (0-based) from the counts
        fun kth(k: Int): Int {
            var seen = 0
            for (value in 0..255) {
                seen += histogram[value]
                if (k < seen) return value
            }
            return 255
        }
        val position = (count - 1) * (q / 100.0)
        if (position >= count - 1) return kth(count - 1).toDouble()     // numpy takes the largest value
        val before = floor(position)
        val gamma = position - before
        val a = kth(before.toInt()).toDouble()
        val b = kth(before.toInt() + 1).toDouble()
        val difference = b - a
        return if (gamma >= 0.5) b - difference * (1 - gamma) else a + difference * gamma
    }

    /**
     * One colour of a picture with its 2nd to 98th percentile stretched to 0..255 (bytes in, bytes out):
     * clip((p - low) * 255 / max(high - low, 1), 0, 255) in doubles, then the fraction cut off - as the
     * Python code does (image_index.stretch_plane). A flat plane has high = low: the divisor is 1.
     */
    fun stretchPlane(plane: ByteArray): ByteArray {
        val histogram = IntArray(256)
        for (b in plane) histogram[b.toInt() and 0xFF]++
        val low = percentile(histogram, plane.size, 2.0)
        val high = percentile(histogram, plane.size, 98.0)
        val divisor = max(high - low, 1.0)
        val out = ByteArray(plane.size)
        for (i in plane.indices) {
            val stretched = ((plane[i].toInt() and 0xFF).toDouble() - low) * 255 / divisor
            // toInt() cuts the fraction off toward zero, like numpy's cast to uint8 of a value in 0..255
            out[i] = min(max(stretched, 0.0), 255.0).toInt().toByte()
        }
        return out
    }

    /**
     * The 32 bytes of one colour from its 16 x 16 lowest DCT frequencies ([low], row by row, 256 floats):
     * a bit for every frequency that lies above the median of all but the first, packed 8 to a byte, the
     * first of the eight in the high bit (numpy.packbits). The 255 values have a middle one, so the median
     * is that value itself; a value equal to it is not above it.
     */
    fun channelBits(low: FloatArray): ByteArray {
        require(low.size == LOW * LOW) { "16 x 16 frequencies expected, got ${low.size}" }
        val rest = low.copyOfRange(1, low.size).also { it.sort() }
        val median = rest[rest.size / 2]                      // 255 values: index 127
        val bytes = ByteArray(low.size / 8)
        for (i in low.indices) {
            if (low[i] > median) {
                bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
            }
        }
        return bytes
    }

    /** How many bits differ between two fingerprints */
    fun distance(a: ByteArray, b: ByteArray): Int {
        require(a.size == b.size) { "fingerprints of different sizes: ${a.size} and ${b.size}" }
        var bits = 0
        for (i in a.indices) bits += Integer.bitCount((a[i].toInt() xor b[i].toInt()) and 0xFF)
        return bits
    }

    /** -1..1 from a Hamming distance: 1 = the same fingerprint (the server's image_index.similarity) */
    fun similarity(distance: Int): Double = 1.0 - 2.0 * distance / HASH_BITS

    // ------------------------------------------------------------------------------------------
    // The OpenCV steps
    // ------------------------------------------------------------------------------------------

    /**
     * The fingerprint of a card picture: [picture] is RGB (3 channels, in that order - the three colours'
     * bytes are concatenated in order, so BGR would be another fingerprint) or RGBA (the alpha is dropped).
     * The card must fill the picture, as the server's captures do.
     */
    fun hash(picture: Mat): ByteArray {
        val rgb = if (picture.channels() == 4) Mat().also { Imgproc.cvtColor(picture, it, Imgproc.COLOR_RGBA2RGB) } else picture
        require(rgb.type() == CvType.CV_8UC3) { "an 8-bit RGB picture expected" }
        val toRelease = ArrayList<Mat>()
        try {
            if (rgb !== picture) toRelease += rgb
            // The part kept. The bounds are the Python int(height * 0.025) etc.: a double product, cut toward zero; the end is exclusive
            val height = rgb.rows()
            val width = rgb.cols()
            val y0 = (height * EDGE[0]).toInt()
            val y1 = (height * EDGE[1]).toInt()
            val x0 = (width * EDGE[2]).toInt()
            val x1 = (width * EDGE[3]).toInt()
            val cut = rgb.submat(y0, y1, x0, x1).also { toRelease += it }
            val card = Mat().also { toRelease += it }
            Imgproc.resize(cut, card, Size(CARD_W.toDouble(), CARD_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

            // Each colour stretched (in plain Kotlin), then the three put together again
            val planes = ArrayList<Mat>()
            Core.split(card, planes)
            toRelease += planes
            val stretched = planes.map { plane ->
                val bytes = ByteArray(CARD_W * CARD_H)
                plane.get(0, 0, bytes)
                Mat(CARD_H, CARD_W, CvType.CV_8UC1).also { it.put(0, 0, stretchPlane(bytes)); toRelease += it }
            }
            val merged = Mat().also { toRelease += it }
            Core.merge(stretched, merged)
            val small = Mat().also { toRelease += it }
            Imgproc.resize(merged, small, Size(SMALL.toDouble(), SMALL.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

            // Per colour: the DCT (float32) and the bits of its 16 x 16 lowest frequencies
            val smallPlanes = ArrayList<Mat>()
            Core.split(small, smallPlanes)
            toRelease += smallPlanes
            val out = ByteArray(HASH_BYTES)
            for ((channel, plane) in smallPlanes.withIndex()) {
                val floats = Mat().also { toRelease += it }
                plane.convertTo(floats, CvType.CV_32F)
                val frequencies = Mat().also { toRelease += it }
                Core.dct(floats, frequencies)
                // clone(): a sub-matrix is not one block of memory; the copy is, and reads row by row
                val block = frequencies.submat(0, LOW, 0, LOW).clone().also { toRelease += it }
                val low = FloatArray(LOW * LOW)
                block.get(0, 0, low)
                channelBits(low).copyInto(out, channel * (HASH_BYTES / 3))
            }
            return out
        } finally {
            toRelease.forEach { it.release() }
        }
    }
}
