package com.canopyanalyzer.model

import android.graphics.Bitmap

data class ImageMeta(
    val xc: Float,
    val yc: Float,
    val rc: Float,
    val width: Int,
    val height: Int,
    val filename: String,
    val channel: String,
    val gamma: Float,
    val stretch: Boolean
)

data class ProcessedImageData(
    val pixels: FloatArray,      // NaN outside circular mask
    val width: Int,
    val height: Int,
    val meta: ImageMeta,
    val log: List<String>
) {
    override fun equals(other: Any?) = false
    override fun hashCode() = System.identityHashCode(this)
}

data class BinaryImageData(
    val pixels: FloatArray,      // 0=canopy, 1=gap, NaN=outside mask
    val width: Int,
    val height: Int,
    val meta: ImageMeta,
    val thresholds: List<Int>,
    val method: String,
    val zonal: Boolean,
    val log: List<String>
) {
    override fun equals(other: Any?) = false
    override fun hashCode() = System.identityHashCode(this)
}

data class GapFractionRecord(
    val ring: Float,   // center VZA in degrees
    val azId: Int,     // 1-based segment index
    val gf: Float      // gap fraction 0..1
)

data class GapFractionData(
    val records: List<GapFractionRecord>,
    val rBounds: List<Double>,
    val segBins: List<Float>,
    val xc: Float,
    val yc: Float,
    val nRings: Int,
    val nSegments: Int,
    val lens: String,
    val vzaBins: List<Float>,
    val log: List<String>
)

data class CanopyResult(
    val le: Double,      // Effective LAI
    val l: Double,       // True LAI (Miller's theorem)
    val lx: Double,      // Clumping index Le/L
    val lxg1: Double,    // Gap-size clumping index (Chen & Cihlar)
    val lxg2: Double,    // Gap-size clumping index (alternative)
    val difn: Double,    // Diffuse non-interceptance %
    val mtaEll: Double,  // Mean tilt angle from ellipsoidal distribution
    val x: Double,       // Ellipsoidal leaf angle distribution parameter
    val settings: AnalysisSettings,
    val meta: ImageMeta
)

data class AnalysisResult(
    val processedImage: ProcessedImageData,
    val binaryImage: BinaryImageData,
    val gapFraction: GapFractionData,
    val canopyResult: CanopyResult,
    val processedBitmap: Bitmap,
    val binaryBitmap: Bitmap
)
