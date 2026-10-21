package com.cardscanner.detection

import org.opencv.core.Mat
import org.opencv.geometry.Geometry
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Every card in a photo, by outline: the contour search of [findCardOutline] (the same checks of
 * proportions, fill and the picture's border) kept for all contours instead of the
 * largest one. [MultiCard.cards] then drops the page, the sleeves' double outlines and fragments.
 * For photos only - the live camera keeps using [findCardOutline] / [CardFinder].
 *
 * Not a port of the server's code: the server reads one card per capture.
 */
object MultiCardFinder {
    /** Outlines of the cards in `photo` (RGB or RGBA), in the photo's pixels, in reading order */
    fun find(photo: Mat, workSize: Int = 1280, ratioTolerance: Double = 0.18): List<Corners> {
        val scale = workSize.toDouble() / max(photo.rows(), photo.cols())
        val small = Mat()
        Imgproc.resize(photo, small, Size((photo.cols() * scale).toInt().toDouble(), (photo.rows() * scale).toInt().toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, if (photo.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val med = median(gray)
        val edges = Mat(); val dilated = Mat()
        Imgproc.Canny(gray, edges, max(0.0, 0.5 * med).toInt().toDouble(), min(255.0, 1.3 * med).toInt().toDouble())
        val k3 = kernel(3)
        Imgproc.dilate(edges, dilated, k3, Point(-1.0, -1.0), 2)
        k3.release()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val minArea = 0.004 * small.rows() * small.cols()  // a card may be 1/100 of the photo (a 10x10 page)
            val found = ArrayList<Corners>()
            for (contour in contours) {
                val area = Geometry.contourArea(contour)
                if (area < minArea) continue
                val corners = boxCorners(Geometry.minAreaRect(MatOfPoint2f(*contour.toArray())))
                val sideW = (corners[1] - corners[0]).norm()
                val sideH = (corners[3] - corners[0]).norm()
                if (min(sideW, sideH) == 0.0) continue
                if (isWholePicture(corners, small.cols(), small.rows())) continue
                val ratio = max(sideW, sideH) / min(sideW, sideH)
                val fill = area / (sideW * sideH)
                if (abs(ratio - CARD_ASPECT_RATIO) / CARD_ASPECT_RATIO > ratioTolerance || fill < 0.85) continue
                // (no isInnerFrame: it assumes a light background, and binder pages are often black - a card's
                // inner frame lies inside its outline, and MultiCard.cards keeps the larger of the two)
                found += corners
            }
            return MultiCard.cards(found).map { it.scaled(1 / scale) }
        } finally {
            contours.forEach { it.release() }
            listOf(small, gray, edges, dilated, hierarchy).forEach { it.release() }
        }
    }
}
