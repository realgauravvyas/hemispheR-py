package com.canopyanalyzer.ui

import android.graphics.*
import com.canopyanalyzer.model.AnalysisResult
import com.canopyanalyzer.model.GapFractionData
import kotlin.math.*

/**
 * Builds fully-annotated bitmaps (title + axes + colorbar) for gallery saving.
 * Used by both VisualizationFragment and ResultsFragment.
 */
internal object ImageAnnotations {

    private enum class ColorbarType { GRAYSCALE, BINARY }

    fun buildImportAnnotated(result: AnalysisResult): Bitmap {
        val meta  = result.processedImage.meta
        val base  = result.processedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        return try {
            val scale = base.width.toFloat() / meta.width
            drawMaskOverlay(Canvas(base), meta.xc * scale, meta.yc * scale, meta.rc * scale, base.width, base.height)
            wrapWithFullAnnotations(
                base, meta.width, meta.height,
                "circular mask",
                "${meta.filename}  ${meta.width}×${meta.height} px  xc=${meta.xc.toInt()} yc=${meta.yc.toInt()} rc=${meta.rc.toInt()}",
                ColorbarType.GRAYSCALE
            )
        } finally {
            base.recycle()
        }
    }

    fun buildBinaryAnnotated(result: AnalysisResult): Bitmap {
        val meta    = result.processedImage.meta
        val binMeta = result.binaryImage.meta
        val base    = result.binaryBitmap.copy(Bitmap.Config.ARGB_8888, true)
        return try {
            val scale = base.width.toFloat() / binMeta.width
            drawMaskOverlay(Canvas(base), binMeta.xc * scale, binMeta.yc * scale, binMeta.rc * scale, base.width, base.height)
            val thresholds = result.binaryImage.thresholds
            val title1 = if (thresholds.size > 1)
                "Binarized (zonal): " + thresholds.joinToString(", ") { it.toInt().toString() }
            else
                "Binarized (threshold=${thresholds.firstOrNull()?.toInt() ?: "—"})"
            wrapWithFullAnnotations(base, binMeta.width, binMeta.height, title1, meta.filename, ColorbarType.BINARY)
        } finally {
            base.recycle()
        }
    }

    fun buildGapFracAnnotated(result: AnalysisResult): Bitmap {
        val meta    = result.processedImage.meta
        val binMeta = result.binaryImage.meta
        val gf      = result.gapFraction
        val base    = result.binaryBitmap.copy(Bitmap.Config.ARGB_8888, true)
        return try {
            val scale = base.width.toFloat() / binMeta.width
            drawRingsAndSegments(Canvas(base), gf, base.width, base.height, scale)
            wrapWithFullAnnotations(
                base, binMeta.width, binMeta.height,
                "Rings and Segments (${gf.lens})",
                "${meta.filename}  rings=${gf.nRings}  segments=${gf.nSegments}",
                ColorbarType.BINARY
            )
        } finally {
            base.recycle()
        }
    }

    // ─── Drawing helpers ──────────────────────────────────────────────────────

    private fun drawMaskOverlay(canvas: Canvas, xc: Float, yc: Float, rc: Float, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            color       = Color.RED
            strokeWidth = (maxOf(w, h) * 0.005f).coerceAtLeast(3f)
        }
        canvas.drawCircle(xc, yc, rc, paint)
        canvas.drawLine(xc, yc, xc + rc, yc, paint)
    }

    private fun drawRingsAndSegments(canvas: Canvas, gf: GapFractionData, w: Int, h: Int, scale: Float) {
        val xc      = gf.xc * scale
        val yc      = gf.yc * scale
        val strokeW = (maxOf(w, h) * 0.003f).coerceAtLeast(2f)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.YELLOW; strokeWidth = strokeW
        }
        val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.YELLOW; strokeWidth = strokeW * 0.7f
        }
        val maxR = (gf.rBounds.maxOrNull()?.toFloat() ?: return) * scale
        for (r in gf.rBounds) {
            if (r > 0) canvas.drawCircle(xc, yc, r.toFloat() * scale, ringPaint)
        }
        for (segAngle in gf.segBins) {
            val rad = Math.toRadians(segAngle.toDouble())
            canvas.drawLine(xc, yc, (xc + maxR * sin(rad)).toFloat(), (yc + maxR * cos(rad)).toFloat(), segPaint)
        }
    }

    private fun axisTicks(maxVal: Int): List<Int> {
        if (maxVal <= 0) return listOf(0)
        val rawStep = maxVal / 7.0
        val mag     = 10.0.pow(floor(log10(rawStep)))
        val norm    = rawStep / mag
        val step = when {
            norm < 1.5 -> mag.toInt()
            norm < 3.5 -> (2 * mag).toInt()
            norm < 7.5 -> (5 * mag).toInt()
            else       -> (10 * mag).toInt()
        }.coerceAtLeast(1)
        return (0..maxVal step step).toList()
    }

    // ─── Full annotation layout ───────────────────────────────────────────────

    /**
     * Wraps [src] with a centered 2-line title at the top, X/Y axes with tick
     * labels, and a colorbar on the right.  Matches Python matplotlib style.
     */
    private fun wrapWithFullAnnotations(
        src: Bitmap,
        origW: Int, origH: Int,
        titleLine1: String, titleLine2: String,
        colorbarType: ColorbarType
    ): Bitmap {
        val safeWidth = origW.coerceAtLeast(1)
        val safeHeight = origH.coerceAtLeast(1)
        val textSz     = 26f
        val titleSz    = 30f
        val subtitleSz = 22f
        val cbLabelSz  = 20f
        val leftPad   = 90
        val bottomPad = 60
        val topPad    = 90
        val cbGap     = 20f
        val cbW       = 28f
        val cbTickLen = 8f
        val rightPad  = 200

        val outW = src.width + leftPad + rightPad
        val outH = src.height + topPad + bottomPad
        val out    = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, leftPad.toFloat(), topPad.toFloat(), null)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = textSz
        }
        val cbLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = cbLabelSz
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = titleSz
            textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = subtitleSz; textAlign = Paint.Align.CENTER
        }

        val imgL    = leftPad.toFloat()
        val imgT    = topPad.toFloat()
        val imgR    = (leftPad + src.width).toFloat()
        val imgB    = (topPad + src.height).toFloat()
        val centerX = outW / 2f

        // ── Title ────────────────────────────────────────────────────────────
        canvas.drawText(titleLine1, centerX, 34f, titlePaint)
        canvas.drawText(titleLine2, centerX, 66f, subtitlePaint)

        // ── Axis borders ─────────────────────────────────────────────────────
        canvas.drawLine(imgL, imgB, imgR, imgB, linePaint)
        canvas.drawLine(imgL, imgT, imgL, imgB, linePaint)

        // ── X-axis ticks ─────────────────────────────────────────────────────
        labelPaint.textAlign = Paint.Align.CENTER
        for (v in axisTicks(safeWidth)) {
            val sx = imgL + v.toFloat() / safeWidth * src.width
            canvas.drawLine(sx, imgB, sx, imgB + 8f, linePaint)
            canvas.drawText(v.toString(), sx, imgB + 8f + textSz, labelPaint)
        }

        // ── Y-axis ticks ─────────────────────────────────────────────────────
        labelPaint.textAlign = Paint.Align.RIGHT
        for (v in axisTicks(safeHeight)) {
            val sy = imgB - v.toFloat() / safeHeight * src.height
            canvas.drawLine(imgL - 8f, sy, imgL, sy, linePaint)
            canvas.drawText(v.toString(), imgL - 11f, sy + textSz * 0.35f, labelPaint)
        }

        // ── Colorbar ─────────────────────────────────────────────────────────
        val cbLeft  = imgR + cbGap
        val cbRight = cbLeft + cbW
        val cbTop   = imgT
        val cbBot   = imgB

        when (colorbarType) {
            ColorbarType.GRAYSCALE -> {
                val shader = LinearGradient(
                    cbLeft, cbTop, cbLeft, cbBot,
                    intArrayOf(Color.WHITE, Color.BLACK),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawRect(cbLeft, cbTop, cbRight, cbBot, Paint().apply { setShader(shader) })
                canvas.drawRect(cbLeft, cbTop, cbRight, cbBot, linePaint)
                cbLabelPaint.textAlign = Paint.Align.LEFT
                for (v in listOf(0, 50, 100, 150, 200, 250)) {
                    val ty = cbBot - v.toFloat() / 250f * (cbBot - cbTop)
                    canvas.drawLine(cbRight, ty, cbRight + cbTickLen, ty, linePaint)
                    canvas.drawText(v.toString(), cbRight + cbTickLen + 4f, ty + cbLabelSz * 0.35f, cbLabelPaint)
                }
                // "Pixel Value" vertical label (reads bottom → top)
                val vlX = cbRight + cbTickLen + 62f
                val vlY = (cbTop + cbBot) / 2f
                canvas.save()
                canvas.rotate(-90f, vlX, vlY)
                canvas.drawText("Pixel Value", vlX, vlY + cbLabelSz * 0.35f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK; textSize = cbLabelSz; textAlign = Paint.Align.CENTER
                    })
                canvas.restore()
            }
            ColorbarType.BINARY -> {
                val cbMid = (cbTop + cbBot) / 2f
                canvas.drawRect(cbLeft, cbTop, cbRight, cbMid, Paint().apply { color = Color.WHITE })
                canvas.drawRect(cbLeft, cbMid, cbRight, cbBot, Paint().apply { color = Color.BLACK })
                canvas.drawRect(cbLeft, cbTop, cbRight, cbBot, linePaint)
                cbLabelPaint.textAlign = Paint.Align.LEFT
                canvas.drawLine(cbRight, cbTop, cbRight + cbTickLen, cbTop, linePaint)
                canvas.drawText("Gap (1)", cbRight + cbTickLen + 4f, cbTop + cbLabelSz * 0.35f, cbLabelPaint)
                canvas.drawLine(cbRight, cbBot, cbRight + cbTickLen, cbBot, linePaint)
                canvas.drawText("Canopy (0)", cbRight + cbTickLen + 4f, cbBot + cbLabelSz * 0.35f, cbLabelPaint)
            }
        }

        return out
    }
}
