package com.canopyanalyzer.core

import android.graphics.Bitmap
import android.util.Log
import com.canopyanalyzer.BuildConfig
import com.canopyanalyzer.model.*
import kotlin.math.*

private const val DTAG = "CanopyDebug"

// Named constants for magic numbers used throughout the pipeline
private const val DISPLAY_MAX_DIM = 1024
private const val DEFAULT_MAX_VZA = 90.0
// Minimum gap fraction substitute — prevents ln(0) in LAI computation.
// Value from reference Python implementation: smallest representable gap.
private const val MIN_GAP_FRACTION = 0.00004530

// Logs min / max / mean of non-NaN values from a FloatArray for comparison with Python
private fun logStats(label: String, arr: FloatArray) {
    if (!BuildConfig.DEBUG) return
    var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var sum = 0.0; var count = 0
    for (v in arr) { if (v.isNaN()) continue; if (v < mn) mn = v; if (v > mx) mx = v; sum += v; count++ }
    Log.d(DTAG, "$label → count=$count  min=$mn  max=$mx  mean=${"%.4f".format(if (count > 0) sum/count else 0.0)}")
}

/**
 * Core scientific computation engine — exact Kotlin port of the reference Python
 * implementation (pil.py / app.py).
 *
 * Pipeline:
 *  1. import_fisheye  — channel extraction, gamma, stretch, circular mask
 *  2. binarize_fisheye — Otsu (global / zonal N/W/S/E) or manual threshold
 *  3. gapfrac_fisheye  — radial rings × azimuth segments gap fraction
 *  4. canopy_fisheye / _calculate_canopy_metrics — Le, L, LX, LXG1, LXG2,
 *                                                   DIFN, MTA.ell, x
 */
object CanopyProcessor {

    // No scaling for processing per user request.
    fun prepareBitmap(original: Bitmap): Bitmap = original

    // ══════════════════════════════════════════════════════════════════
    // Step 1 – import_fisheye
    // ══════════════════════════════════════════════════════════════════
    fun processImage(
        bitmap: Bitmap,
        settings: AnalysisSettings,
        filename: String
    ): ProcessedImageData {
        val log = mutableListOf<String>()
        val width  = bitmap.width
        val height = bitmap.height
        val size   = width * height

        val argb  = IntArray(size)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val gamma = settings.gamma

        // Extract channels lazily — single-channel modes only allocate one array;
        // multi-channel modes allocate all three. Each lambda produces a fresh copy
        // so that applyGammaHemispheR can mutate it without aliasing.
        fun extractR() = FloatArray(size) { i -> ((argb[i] shr 16) and 0xFF).toFloat() }
        fun extractG() = FloatArray(size) { i -> ((argb[i] shr  8) and 0xFF).toFloat() }
        fun extractB() = FloatArray(size) { i -> ( argb[i]          and 0xFF).toFloat() }

        if (BuildConfig.DEBUG) Log.d(DTAG, "=== processImage START === file=$filename  ${width}x${height}  ch=${settings.channel.code}  gamma=${settings.gamma}")

        // Log raw channel BEFORE gamma so we can compare with Python's raw values
        if (settings.channel.code == "3" || settings.channel.code == "B") {
            val rawB = extractB()
            logStats("[A] Blue raw (before gamma)", rawB)
            // Print first 5 pixel values so we can compare byte-for-byte with Python:
            // Python equivalent: arr[0,0,2], arr[0,1,2], arr[0,2,2], arr[0,3,2], arr[0,4,2]
            if (BuildConfig.DEBUG) Log.d(DTAG, "[A-pixels] blue[0,0]=${rawB[0].toInt()}  [0,1]=${rawB[1].toInt()}  [0,2]=${rawB[2].toInt()}  [0,3]=${rawB[3].toInt()}  [0,4]=${rawB[4].toInt()}")
        }

        val imgValues: FloatArray = when (settings.channel.code) {
            // Single-channel modes: extract one channel, gamma-correct with its own range
            "1"     -> applyGammaHemispheR(extractR(), gamma)
            "2"     -> applyGammaHemispheR(extractG(), gamma)
            "3"     -> applyGammaHemispheR(extractB(), gamma)
            "B"     -> applyGammaHemispheR(extractB(), gamma)
            "first" -> applyGammaHemispheR(extractR(), gamma)

            // Multi-channel modes: Python applies gamma to R, G, B independently
            else -> {
                val R = applyGammaHemispheR(extractR(), gamma)
                val G = applyGammaHemispheR(extractG(), gamma)
                val B = applyGammaHemispheR(extractB(), gamma)
                when (settings.channel.code) {
                    "Luma"  -> FloatArray(size) { i -> 0.3f * R[i] + 0.59f * G[i] + 0.11f * B[i] }
                    "2BG"   -> FloatArray(size) { i -> -G[i] + 2f * B[i] }
                    "RGB"   -> FloatArray(size) { i -> (R[i] + G[i] + B[i]) / 3f }
                    "GLA"   -> FloatArray(size) { i ->
                        // Python: -(num/den) where num=-R+2G-B, den=R+2G+B
                        val num = -R[i] + 2f * G[i] - B[i]
                        val den =  R[i] + 2f * G[i] + B[i]
                        if (den != 0f) -(num / den) else 0f
                    }
                    "GEI"   -> FloatArray(size) { i -> R[i] - 2f * G[i] + B[i] }
                    "BtoRG" -> FloatArray(size) { i ->
                        // Python: denom=R+2B+G; term=(B-R)/denom; B*(1+term+B)/2
                        val denom = R[i] + 2f * B[i] + G[i]
                        val term  = if (denom != 0f) (B[i] - R[i]) / denom else 0f
                        B[i] * (1f + term + B[i]) / 2f
                    }
                    else -> B  // fallback to blue
                }
            }
        }

        logStats("[B] After gamma (full image, no mask)", imgValues)

        for (i in imgValues.indices) {
            if (imgValues[i].isNaN() || imgValues[i].isInfinite()) imgValues[i] = 0f
        }

        // Apply circular mask BEFORE stretch/normalization so that outside-mask
        // pixels are excluded from min/max — matches Python which applies the
        // mask first and then uses np.nanmin/nanmax (inside-mask only).
        val (xc, yc, rc) = resolveMask(settings, width, height, log)
        val fisheyeType = if (settings.circularFisheye) "circular" else "fullframe"
        log.add("It is a $fisheyeType fisheye, where xc, yc and radius are " +
                "${xc.toInt()}, ${yc.toInt()}, ${rc.toInt()}")

        val applyMask = settings.circularFisheye ||
                settings.maskMode == MaskMode.MANUAL ||
                settings.maskMode == MaskMode.CAMERA_PRESET
        if (applyMask && rc > 0) {
            val rc2 = rc * rc
            for (y in 0 until height) {
                val dy = (y - yc)
                val dy2 = dy * dy
                val offset = y * width
                for (x in 0 until width) {
                    val dx = (x - xc)
                    if (dx * dx + dy2 > rc2) imgValues[offset + x] = Float.NaN
                }
            }
        }

        logStats("[C] After mask applied (inside-mask only, NaN outside)", imgValues)

        // Percentile stretch on inside-mask pixels only (matches Python np.nanpercentile)
        if (settings.stretch) {
            val valid = imgValues.filter { !it.isNaN() }.sorted()
            val sz = valid.size
            if (sz > 0) {
                val p1  = valid[(sz * 0.01).toInt().coerceIn(0, sz - 1)]
                val p99 = valid[(sz * 0.99).toInt().coerceIn(0, sz - 1)]
                for (i in imgValues.indices) {
                    if (!imgValues[i].isNaN()) imgValues[i] = imgValues[i].coerceIn(p1, p99)
                }
            }
        }

        // Min-max normalization on inside-mask pixels only (matches Python np.nanmin/nanmax)
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in imgValues) {
            if (!v.isNaN()) {
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }
        if (mx > mn) {
            val range = mx - mn
            for (i in imgValues.indices) {
                if (!imgValues[i].isNaN()) {
                    // Match Python: np.round(img_values) after normalization
                    imgValues[i] = ((imgValues[i] - mn) / range * 255f).roundToInt().toFloat()
                }
            }
        } else {
            for (i in imgValues.indices) if (!imgValues[i].isNaN()) imgValues[i] = 0f
        }

        logStats("[D] After normalization (inside-mask, 0-255 rounded)", imgValues)

        val meta = ImageMeta(
            xc = xc, yc = yc, rc = rc,
            width = width, height = height,
            filename = filename,
            channel  = settings.channel.code,
            gamma    = gamma,
            stretch  = settings.stretch
        )
        return ProcessedImageData(imgValues, width, height, meta, log)
    }

    /**
     * Exact port of Python's apply_gamma_hemispheR().
     * Normalises by the array's own (max − min) range, applies the power,
     * then scales back — NOT by the fixed constant 255.
     *
     * Python:
     *   normalized = arr / (maxm - minm)
     *   gamma_corrected = np.power(normalized, g)
     *   return (maxm - minm) * gamma_corrected
     */
    private fun applyGammaHemispheR(arr: FloatArray, gamma: Float): FloatArray {
        if (gamma == 1f) return arr
        var minm = Float.MAX_VALUE
        var maxm = -Float.MAX_VALUE
        for (v in arr) {
            if (v < minm) minm = v
            if (v > maxm) maxm = v
        }
        if (maxm == minm) return arr
        val range = (maxm - minm).toDouble()
        for (i in arr.indices) {
            val normalized = arr[i].toDouble() / range
            arr[i] = (normalized.pow(gamma.toDouble()) * range).toFloat()
        }
        return arr
    }

    private fun resolveMask(
        settings: AnalysisSettings,
        width: Int, height: Int,
        log: MutableList<String>
    ): Triple<Float, Float, Float> {
        return when (settings.maskMode) {
            MaskMode.CAMERA_PRESET -> {
                val preset = settings.cameraPreset
                val xc = preset.xc.toFloat()
                val yc = preset.yc.toFloat()
                val rc = preset.rc.toFloat()
                log.add("Camera preset: ${preset.label}")
                validateAndLogMask(xc, yc, rc, width, height, log)
                Triple(xc, yc, rc)
            }
            MaskMode.MANUAL -> {
                val xc = settings.maskXc.toFloat()
                val yc = settings.maskYc.toFloat()
                val rc = settings.maskRc.toFloat()
                log.add("=== Validating Mask Parameters ===")
                log.add("Image dimensions: $width x $height pixels")
                log.add("Mask parameters: xc=${xc.toInt()}, yc=${yc.toInt()}, rc=${rc.toInt()}")
                validateAndLogMask(xc, yc, rc, width, height, log)
                Triple(xc, yc, rc)
            }
            MaskMode.AUTO -> {
                val xc = width  / 2f
                val yc = height / 2f
                val rc = if (settings.circularFisheye) minOf(xc, yc) - 2f
                         else sqrt(xc * xc + yc * yc) - 2f
                Triple(xc, yc, rc)
            }
        }
    }

    private fun validateAndLogMask(
        xc: Float, yc: Float, rc: Float,
        width: Int, height: Int,
        log: MutableList<String>
    ) {
        var valid = true
        if (rc <= 0) {
            log.add("WARNING: Mask radius is zero or negative!")
            valid = false
        }
        if (xc + rc > width || xc - rc < 0) {
            log.add("WARNING: Mask xc\u00b1rc [${(xc - rc).toInt()}, ${(xc + rc).toInt()}] " +
                    "exceeds image width [0, $width]!")
            valid = false
        }
        if (yc + rc > height || yc - rc < 0) {
            log.add("WARNING: Mask yc\u00b1rc [${(yc - rc).toInt()}, ${(yc + rc).toInt()}] " +
                    "exceeds image height [0, $height]!")
            valid = false
        }
        if (valid) log.add("\u2713 Mask parameters are valid")
        else {
            log.add("\u2717 Mask extends outside image bounds.")
            log.add("  Proceeding anyway (may produce invalid results)...")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Step 2 – binarize_fisheye
    // ══════════════════════════════════════════════════════════════════
    fun binarize(processed: ProcessedImageData, settings: AnalysisSettings): BinaryImageData {
        val log       = mutableListOf("--- Binarizing Image ---")
        val img       = processed.pixels
        val width     = processed.width
        val height    = processed.height
        val binary    = FloatArray(img.size) { Float.NaN }
        val thresholds = mutableListOf<Int>()

        val useZonal  = settings.thresholdMethod == ThresholdMethod.ZONAL_OTSU
        val useManual = settings.thresholdMethod == ThresholdMethod.MANUAL

        when {
            useZonal -> {
                val xc    = processed.meta.xc
                val yc    = processed.meta.yc
                val masks = buildZonalMasks(width, height, xc, yc)
                for (zone in listOf("N", "W", "S", "E")) {
                    val zm = masks[zone]!!
                    val th = otsuThresholdZonal(img, zm)
                    thresholds.add(th.roundToInt())
                    log.add("Zone $zone Otsu threshold: ${th.roundToInt()}")
                    for (i in img.indices) {
                        if (zm[i] && !img[i].isNaN()) {
                            binary[i] = if (img[i] > th) 1f else 0f
                        }
                    }
                }
            }
            useManual -> {
                val th = settings.manualThreshold.toFloat()
                thresholds.add(th.roundToInt())
                log.add("Manual threshold: ${th.roundToInt()}")
                for (i in img.indices) if (!img[i].isNaN()) binary[i] = if (img[i] > th) 1f else 0f
            }
            else -> {   // Global Otsu
                val th = otsuThreshold(img)
                thresholds.add(th.roundToInt())
                log.add("Global Otsu threshold: ${th.roundToInt()}")
                if (BuildConfig.DEBUG) Log.d(DTAG, "[E] Global Otsu threshold = ${th.roundToInt()}")
                for (i in img.indices) if (!img[i].isNaN()) binary[i] = if (img[i] > th) 1f else 0f
            }
        }

        val method = when (settings.thresholdMethod) {
            ThresholdMethod.GLOBAL_OTSU -> "Otsu"
            ThresholdMethod.ZONAL_OTSU  -> "Zonal Otsu"
            ThresholdMethod.MANUAL      -> "manual"
        }
        return BinaryImageData(binary, width, height, processed.meta, thresholds, method, useZonal, log)
    }

    private fun otsuThreshold(arr: FloatArray): Float {
        val hist = LongArray(256)
        var count = 0L
        for (v in arr) {
            if (!v.isNaN()) {
                hist[v.coerceIn(0f, 255f).roundToInt().coerceIn(0, 255)]++
                count++
            }
        }
        return computeOtsuFromHist(hist, count)
    }

    private fun otsuThresholdZonal(arr: FloatArray, mask: BooleanArray): Float {
        val hist = LongArray(256)
        var count = 0L
        for (i in arr.indices) {
            if (mask[i] && !arr[i].isNaN()) {
                hist[arr[i].coerceIn(0f, 255f).roundToInt().coerceIn(0, 255)]++
                count++
            }
        }
        return computeOtsuFromHist(hist, count)
    }

    private fun computeOtsuFromHist(hist: LongArray, total: Long): Float {
        if (total == 0L) return 0f
        val sumTotal = (0..255).sumOf { it.toLong() * hist[it] }.toDouble()
        var sumBg = 0.0; var weightBg = 0L
        var maxVar = 0.0; var best = 0.0
        for (t in 0..255) {
            weightBg += hist[t]
            if (weightBg == 0L) continue
            val weightFg = total - weightBg
            if (weightFg == 0L) break
            sumBg += t * hist[t]
            val meanBg = sumBg / weightBg
            val meanFg = (sumTotal - sumBg) / weightFg
            val v = weightBg.toDouble() * weightFg.toDouble() * (meanBg - meanFg).pow(2)
            if (v > maxVar) { maxVar = v; best = t.toDouble() }
        }
        return best.toFloat()
    }

    private fun buildZonalMasks(
        width: Int, height: Int,
        xc: Float, yc: Float
    ): Map<String, BooleanArray> {
        val xmax = width.toFloat(); val ymax = height.toFloat()
        val size  = width * height
        val maskN = BooleanArray(size); val maskW = BooleanArray(size)
        val maskS = BooleanArray(size); val maskE = BooleanArray(size)
        for (y in 0 until height) {
            val fy = y.toFloat()
            val offset = y * width
            for (x in 0 until width) {
                val fx = x.toFloat(); val idx = offset + x
                maskN[idx] = inTriangle(fx, fy,  0f, ymax,   xc,   yc, xmax, ymax)
                maskW[idx] = inTriangle(fx, fy,  0f, ymax,   0f,   0f,   xc,   yc)
                maskS[idx] = inTriangle(fx, fy,  0f,   0f,   xc,   yc, xmax,   0f)
                maskE[idx] = inTriangle(fx, fy, xmax,  0f, xmax, ymax,   xc,   yc)
            }
        }
        return mapOf("N" to maskN, "W" to maskW, "S" to maskS, "E" to maskE)
    }

    private fun inTriangle(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Boolean {
        fun sign(p1x: Float, p1y: Float, p2x: Float, p2y: Float, p3x: Float, p3y: Float) =
            (p1x - p3x) * (p2y - p3y) - (p2x - p3x) * (p1y - p3y)
        val d1 = sign(px, py, ax, ay, bx, by)
        val d2 = sign(px, py, bx, by, cx, cy)
        val d3 = sign(px, py, cx, cy, ax, ay)
        return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))
    }

    // ══════════════════════════════════════════════════════════════════
    // Step 3 – gapfrac_fisheye
    // ══════════════════════════════════════════════════════════════════
    fun computeGapFraction(
        binary: BinaryImageData,
        settings: AnalysisSettings
    ): GapFractionData {
        val log    = mutableListOf<String>()
        val imgBw  = binary.pixels
        val width  = binary.width
        val height = binary.height
        val meta   = binary.meta
        val xc = meta.xc; val yc = meta.yc; val rc = meta.rc

        log.add("Used xc, yc, rc: ${xc.toInt()}, ${yc.toInt()}, ${rc.toInt()}")

        val maxVZA   = settings.maxVZA.toDouble()
        val startVZA = settings.startVZA.toDouble()
        val endVZA   = settings.endVZA.toDouble()
        val nRings   = settings.nRings
        val nSeg     = settings.nSegments
        val lens     = settings.lensType.label

        val vzaStep  = (endVZA - startVZA) / nRings
        val vzaBins  = DoubleArray(nRings + 1) { startVZA + it * vzaStep }
        // Match Python: get_radius() returns round(result) — integer pixel boundaries
        val rBounds  = vzaBins.map { kotlin.math.round(lensRadius(it, lens, rc.toDouble(), maxVZA)).toDouble() }

        val segStep = 360.0 / nSeg
        val segBins = FloatArray(nSeg + 1) { (it * segStep).toFloat() }

        val sums   = FloatArray(nRings * nSeg)
        val counts = IntArray(nRings * nSeg)

        for (y in 0 until height) {
            val dy  = (y - yc).toDouble()
            val dy2 = dy * dy
            val offset = y * width
            for (x in 0 until width) {
                val idx = offset + x
                val valBw = imgBw[idx]
                if (valBw.isNaN()) continue

                val dx = (x - xc).toDouble()
                val r = sqrt(dx * dx + dy2)
                
                var ringIdx = -1
                for (i in 0 until nRings) {
                    if (r >= rBounds[i] && r < rBounds[i+1]) {
                        ringIdx = i
                        break
                    }
                }
                if (ringIdx == -1) continue

                var theta = Math.toDegrees(atan2(dx, dy)).toFloat()
                if (theta < 0f) theta += 360f
                
                var segIdx = -1
                for (j in 0 until nSeg) {
                    if (theta >= segBins[j] && theta < segBins[j+1]) {
                        segIdx = j
                        break
                    }
                }
                if (segIdx == -1) continue

                val flatIdx = ringIdx * nSeg + segIdx
                sums[flatIdx] += valBw
                counts[flatIdx]++
            }
        }

        val records = mutableListOf<GapFractionRecord>()
        for (i in 0 until nRings) {
            val ringCenter = ((vzaBins[i] + vzaBins[i + 1]) / 2).toFloat()
            for (j in 0 until nSeg) {
                val flatIdx = i * nSeg + j
                val gf = if (counts[flatIdx] > 0) sums[flatIdx] / counts[flatIdx] else Float.NaN
                records.add(GapFractionRecord(ring = ringCenter, azId = j + 1, gf = gf))
            }
        }

        return GapFractionData(
            records   = records,
            rBounds   = rBounds,
            segBins   = segBins.toList(),
            xc = xc, yc = yc,
            nRings    = nRings,
            nSegments = nSeg,
            lens      = lens,
            vzaBins   = vzaBins.map { it.toFloat() },
            log       = log
        )
    }

    private fun lensRadius(vza: Double, lens: String, rc: Double, maxVZA: Double): Double {
        val x = vza / maxVZA
        return when (lens) {
            "equidistant" -> rc * x
            "FC-E8"       -> rc * (1.06 * x + 0.00498 * x.pow(2) - 0.0639 * x.pow(3))
            else          -> rc * x
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Step 4 – canopy_fisheye / _calculate_canopy_metrics
    // ══════════════════════════════════════════════════════════════════
    fun computeCanopyAttributes(
        gfData: GapFractionData,
        settings: AnalysisSettings,
        meta: ImageMeta
    ): CanopyResult {
        val nRings = gfData.nRings
        val nSeg   = gfData.nSegments

        val rings    = gfData.records.map { it.ring }.distinct().sorted()
        // Build an indexed lookup map: (ringValue, azId) → gf, avoiding O(n²) .find() calls
        val gfLookup = HashMap<Long, Float>(gfData.records.size)
        for (rec in gfData.records) {
            val key = rec.ring.toBits().toLong() shl 32 or rec.azId.toLong()
            gfLookup[key] = rec.gf
        }
        val gfMatrix = Array(nRings) { r ->
            val ring = rings[r]
            DoubleArray(nSeg) { s ->
                val key = ring.toBits().toLong() shl 32 or (s + 1).toLong()
                val gf = gfLookup[key]?.toDouble() ?: 0.0
                if (gf <= 0.0 || gf.isNaN()) MIN_GAP_FRACTION else gf
            }
        }

        val rads  = DoubleArray(nRings) { Math.toRadians(rings[it].toDouble()) }
        val sinth = DoubleArray(nRings) { sin(rads[it]) }
        val costh = DoubleArray(nRings) { cos(rads[it]) }

        val sumSinth      = sinth.sum()
        val sumSinthCosth = (0 until nRings).sumOf { sinth[it] * costh[it] }

        val w = DoubleArray(nRings) { sinth[it] / sumSinth }
        val W = DoubleArray(nRings) { (sinth[it] * costh[it]) / sumSinthCosth / 2.0 }
        val gapFr = DoubleArray(nRings) { r -> gfMatrix[r].average() }

        val le = 2.0 * (0 until nRings).sumOf { r -> -ln(gapFr[r]) * w[r] * costh[r] }
        val l = 2.0 * (0 until nRings).sumOf { r ->
            gfMatrix[r].map { -ln(it) }.average() * w[r] * costh[r]
        }
        val lx = if (l != 0.0) le / l else 0.0
        val difn = (0 until nRings).sumOf { r -> gapFr[r] * 2.0 * W[r] } * 100.0

        fun kB(zRad: DoubleArray, xp: Double): DoubleArray = DoubleArray(zRad.size) { i ->
            val num  = sqrt(xp.pow(2) + tan(zRad[i]).pow(2))
            val term = (((0.000509 * xp - 0.013) * xp + 0.1223) * xp + 0.45) * xp
            num / (1.47 + term)
        }

        var xParam = 1.0; val dxStep = 0.01
        var xMin   = 0.1; var xMax   = 10.0
        val logT1  = DoubleArray(nRings) { ln(gapFr[it]) }

        repeat(50) {
            if (xMax - xMin < dxStep) return@repeat
            val KB = kB(rads, xParam)
            val DK = DoubleArray(nRings) { kB(rads, xParam + dxStep)[it] - KB[it] }
            val S1 = (0 until nRings).sumOf { logT1[it] * KB[it] }
            val S2 = (0 until nRings).sumOf { KB[it].pow(2) }
            val S3 = (0 until nRings).sumOf { KB[it] * DK[it] }
            val S4 = (0 until nRings).sumOf { DK[it] * logT1[it] }
            val F  = S2 * S4 - S1 * S3
            if (F < 0) xMin = xParam else xMax = xParam
            xParam = (xMax + xMin) / 2.0
        }
        val mtaEll = 90.0 * (0.1 + 0.9 * exp(-0.5 * xParam))

        val w23 = DoubleArray(nSeg) { j -> (2.0 * (nSeg - j)) / (nSeg.toDouble() * (nSeg + 1)) }
        val w34Raw = DoubleArray(nSeg) { j -> (1.0 / (j + 1)) / nSeg }
        val w34    = DoubleArray(nSeg).also { arr ->
            var cum = 0.0
            for (j in nSeg - 1 downTo 0) { cum += w34Raw[j]; arr[j] = cum }
        }

        var lxg1Sum = 0.0; var lxg2Sum = 0.0
        for (r in 0 until nRings) {
            val vals   = gfMatrix[r].sortedDescending().toDoubleArray()
            val sumA23 = (0 until nSeg).sumOf { j -> vals[j] * w23[j] }
            val sumR23 = (0 until nSeg).sumOf { j -> vals[j] * w23[nSeg - 1 - j] }
            val sumA34 = (0 until nSeg).sumOf { j -> vals[j] * w34[j] }
            val sumR34 = (0 until nSeg).sumOf { j -> vals[j] * w34[nSeg - 1 - j] }

            val lxg1 = if (sumA23 > 0 && sumR23 > 0 && abs(sumR23 - 1) > 1e-9)
                ln(sumA23) / ln(sumR23) else 1.0
            val lxg2 = if (sumA34 > 0 && sumR34 > 0)
                ln(sumA34) / ln(sumR34) else 1.0

            lxg1Sum += lxg1 * w[r]
            lxg2Sum += lxg2 * w[r]
        }

        return CanopyResult(
            le = roundTo(le, 2), l = roundTo(l, 2), lx = roundTo(lx, 2),
            lxg1 = roundTo(lxg1Sum, 2), lxg2 = roundTo(lxg2Sum, 2),
            difn = roundTo(difn, 1), mtaEll = roundTo(mtaEll, 1), x = roundTo(xParam, 2),
            settings = settings, meta = meta
        )
    }

    private fun roundTo(v: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (v * factor).roundToLong() / factor
    }

    // ─── Visualiation helpers for high-res images ───────────────────
    fun processedToDisplayBitmap(data: ProcessedImageData, maxDim: Int = DISPLAY_MAX_DIM): Bitmap {
        val scale = maxDim.toFloat() / maxOf(data.width, data.height)
        val dw = (data.width * scale).toInt(); val dh = (data.height * scale).toInt()
        val bmp  = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val argb = IntArray(dw * dh)
        for (y in 0 until dh) {
            val sy = (y / scale).toInt().coerceAtMost(data.height - 1)
            for (x in 0 until dw) {
                val sx = (x / scale).toInt().coerceAtMost(data.width - 1)
                val pix = data.pixels[sy * data.width + sx]
                argb[y * dw + x] = if (pix.isNaN()) 0xFF303030.toInt()
                else {
                    val v = pix.coerceIn(0f, 255f).toInt()
                    -0x1000000 or (v shl 16) or (v shl 8) or v
                }
            }
        }
        bmp.setPixels(argb, 0, dw, 0, 0, dw, dh)
        return bmp
    }

    fun binaryToDisplayBitmap(data: BinaryImageData, maxDim: Int = DISPLAY_MAX_DIM): Bitmap {
        val scale = maxDim.toFloat() / maxOf(data.width, data.height)
        val dw = (data.width * scale).toInt(); val dh = (data.height * scale).toInt()
        val bmp  = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val argb = IntArray(dw * dh)
        for (y in 0 until dh) {
            val sy = (y / scale).toInt().coerceAtMost(data.height - 1)
            for (x in 0 until dw) {
                val sx = (x / scale).toInt().coerceAtMost(data.width - 1)
                val pix = data.pixels[sy * data.width + sx]
                argb[y * dw + x] = when {
                    pix.isNaN() -> 0xFF303030.toInt()
                    pix >= 0.5f -> 0xFFFFFFFF.toInt()
                    else        -> 0xFF000000.toInt()
                }
            }
        }
        bmp.setPixels(argb, 0, dw, 0, 0, dw, dh)
        return bmp
    }

    fun computeGapStats(binary: BinaryImageData): Pair<Float, Float> {
        var sum = 0f; var count = 0
        for (v in binary.pixels) if (!v.isNaN()) { sum += v; count++ }
        if (count == 0) return 0f to 0f
        val gapPct = 100f * sum / count
        return gapPct to (100f - gapPct)
    }
}
