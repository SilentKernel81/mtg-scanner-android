package com.cardscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.detection.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * Photo import: the decoder for API 26-27 (BitmapFactory + EXIF, ImageDecoder needs API 28) gives
 * the picture the API 28+ one does, and cards that could not be saved are handed back, not lost.
 * Run with: adb -s <device> shell am instrument -w -e class com.cardscanner.PhotoImportTest \
 *   com.cardscanner.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PhotoImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun openCv() { assertTrue(OpenCVLoader.initLocal()) }

    /** 600x400 landscape: left half red, right half blue, with the EXIF orientation given */
    private fun jpeg(orientation: Int): Uri {
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, 300f, 400f, Paint().apply { color = Color.RED })
            drawRect(300f, 0f, 600f, 400f, Paint().apply { color = Color.BLUE })
        }
        val file = File(context.cacheDir, "orientation_$orientation.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return Uri.fromFile(file)
    }

    private fun close(a: Int, b: Int) = (0..2).all { kotlin.math.abs(((a shr (it * 8)) and 0xFF) - ((b shr (it * 8)) and 0xFF)) < 40 }

    @Test fun bothDecodersTurnThePhotoTheSame() {
        val importer = PhotoImporter(context)
        for (orientation in listOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)) {
            val uri = jpeg(orientation)
            val modern = importer.decode(uri, useImageDecoder = true)
            val legacy = importer.decode(uri, useImageDecoder = false)
            assertEquals("size, orientation $orientation", modern.width to modern.height, legacy.width to legacy.height)
            for ((x, y) in listOf(0.1 to 0.1, 0.9 to 0.1, 0.1 to 0.9, 0.9 to 0.9)) {
                val px = (x * legacy.width).toInt(); val py = (y * legacy.height).toInt()
                assertTrue("pixel ($x, $y), orientation $orientation", close(modern.getPixel(px, py), legacy.getPixel(px, py)))
            }
        }
        // a quarter turn makes it portrait
        assertEquals(400 to 600, importer.decode(jpeg(ExifInterface.ORIENTATION_ROTATE_90), false).let { it.width to it.height })
    }

    @Test fun cardsThatCouldNotBeSavedAreHandedBack() {
        val photo = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.DKGRAY)
            drawRect(100f, 100f, 400f, 521f, Paint().apply { color = Color.WHITE })
            drawRect(600f, 100f, 900f, 521f, Paint().apply { color = Color.WHITE })
        }
        fun card(x: Double) = listOf(Pt(x, 100.0), Pt(x + 300, 100.0), Pt(x + 300, 521.0), Pt(x, 521.0))
        val first = card(100.0); val second = card(600.0); val third = card(350.0)
        var saves = 0
        val progress = ArrayList<Int>()
        val failed = PhotoImporter(context).importCards(photo, listOf(first, second, third),
            enqueue = {
                when (++saves) {
                    2 -> false  // the outbox could not save it
                    3 -> throw IllegalStateException("disk full")
                    else -> true
                }
            },
            onProgress = { progress += it })
        assertEquals("every card is gone through, and counted", listOf(1, 2, 3), progress)
        assertEquals("the cards that were not saved come back, in order", listOf(second, third), failed)
        assertEquals(3, saves)
    }
}
