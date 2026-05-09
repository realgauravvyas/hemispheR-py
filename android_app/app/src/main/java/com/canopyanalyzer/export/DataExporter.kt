package com.canopyanalyzer.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.canopyanalyzer.model.AnalysisResult
import com.canopyanalyzer.model.MaskMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object DataExporter {

    private val dateTag get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val dateDisplay get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun exportDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir

    private fun csv(value: Any?): String {
        val text = value?.toString().orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ')
        return "\"${text.replace("\"", "\"\"")}\""
    }

    // ─── CSV Export ────────────────────────────────────────────────────
    fun exportCsv(context: Context, result: AnalysisResult): File {
        val filename = "CanopyAnalyzer_$dateTag.csv"
        val file = File(exportDir(context), filename)
        file.parentFile?.mkdirs()

        FileWriter(file).use { w ->
            w.appendLine("# Canopy Analyzer – Results Export")
            w.appendLine("# Date,${csv(dateDisplay)}")
            w.appendLine("# Image,${csv(result.canopyResult.meta.filename)}")
            w.appendLine("")
            w.appendLine("# === CANOPY ATTRIBUTES ===")
            w.appendLine("Metric,Value,Description")
            val cr = result.canopyResult
            w.appendLine("${csv("Le")},${cr.le},${csv("Effective LAI")}")
            w.appendLine("${csv("L")},${cr.l},${csv("True LAI (Miller)")}")
            w.appendLine("${csv("LX")},${cr.lx},${csv("Lang & Xiang clumping index")}")
            w.appendLine("${csv("LXG1")},${cr.lxg1},${csv("Gap-size clumping index 1")}")
            w.appendLine("${csv("LXG2")},${cr.lxg2},${csv("Gap-size clumping index 2")}")
            w.appendLine("${csv("DIFN")},${cr.difn},${csv("Diffuse non-interceptance (%)")}")
            w.appendLine("${csv("MTA.ell")},${cr.mtaEll},${csv("Mean tilt angle (deg)")}")
            w.appendLine("${csv("x")},${cr.x},${csv("Ellipsoidal LAD parameter")}")
            w.appendLine("")

            // Settings
            w.appendLine("# === ANALYSIS SETTINGS ===")
            val s = cr.settings
            val m = cr.meta
            w.appendLine("${csv("Channel")},${csv(s.channel.label)}")
            w.appendLine("Gamma,${s.gamma}")
            w.appendLine("Stretch,${s.stretch}")
            w.appendLine("${csv("MaskMode")},${csv(s.maskMode.label)}")
            w.appendLine("Mask_xc,${m.xc.toInt()}")
            w.appendLine("Mask_yc,${m.yc.toInt()}")
            w.appendLine("Mask_rc,${m.rc.toInt()}")
            val thd = result.binaryImage.thresholds.joinToString("_")
            w.appendLine("${csv("Threshold_method")},${csv(s.thresholdMethod.label)}")
            w.appendLine("${csv("Thresholds")},${csv(thd)}")
            w.appendLine("${csv("Lens")},${csv(s.lensType.label)}")
            w.appendLine("MaxVZA,${s.maxVZA}")
            w.appendLine("StartVZA,${s.startVZA}")
            w.appendLine("EndVZA,${s.endVZA}")
            w.appendLine("nRings,${s.nRings}")
            w.appendLine("nSegments,${s.nSegments}")
            w.appendLine("")

            // Gap fraction table
            w.appendLine("# === GAP FRACTION TABLE ===")
            val gf = result.gapFraction
            val nSeg = gf.nSegments
            val segHeader = (1..nSeg).joinToString(",") { "GF_Seg$it" }
            w.appendLine("Ring_VZA_deg,$segHeader,GF_Mean")
            val rings = gf.records.map { it.ring }.distinct().sorted()
            // Indexed lookup to avoid O(n²) .find() per cell
            val gfMap = gf.records.associateBy { it.ring to it.azId }
            for (ring in rings) {
                val row = StringBuilder().append(ring)
                val gfVals = mutableListOf<Float>()
                for (seg in 1..nSeg) {
                    val v = gfMap[ring to seg]?.gf
                    row.append(",")
                    if (v != null && !v.isNaN()) { row.append(v); gfVals.add(v) }
                    else row.append("NA")
                }
                val mean = if (gfVals.isNotEmpty()) "%.4f".format(gfVals.average()) else "NA"
                row.append(",$mean")
                w.appendLine(row.toString())
            }
        }
        return file
    }

    // ─── JSON Export ───────────────────────────────────────────────────
    fun exportJson(context: Context, result: AnalysisResult): File {
        val filename = "CanopyAnalyzer_$dateTag.json"
        val file = File(exportDir(context), filename)
        file.parentFile?.mkdirs()

        val cr  = result.canopyResult
        val s   = cr.settings
        val m   = cr.meta
        val gf  = result.gapFraction
        val bin = result.binaryImage

        val root = JSONObject().apply {
            put("app", "Canopy Analyzer")
            put("export_date", dateDisplay)
            put("image_filename", m.filename)

            put("canopy_attributes", JSONObject().apply {
                put("Le",      cr.le)
                put("L",       cr.l)
                put("LX",      cr.lx)
                put("LXG1",    cr.lxg1)
                put("LXG2",    cr.lxg2)
                put("DIFN",    cr.difn)
                put("MTA_ell", cr.mtaEll)
                put("x",       cr.x)
            })

            put("settings", JSONObject().apply {
                put("channel",           s.channel.label)
                put("gamma",             s.gamma)
                put("stretch",           s.stretch)
                put("mask_mode",         s.maskMode.label)
                put("mask_xc",           m.xc.toInt())
                put("mask_yc",           m.yc.toInt())
                put("mask_rc",           m.rc.toInt())
                put("threshold_method",  s.thresholdMethod.label)
                put("thresholds",        bin.thresholds.joinToString("_"))
                put("lens",              s.lensType.label)
                put("max_vza",           s.maxVZA)
                put("start_vza",         s.startVZA)
                put("end_vza",           s.endVZA)
                put("n_rings",           s.nRings)
                put("n_segments",        s.nSegments)
            })

            put("image_info", JSONObject().apply {
                put("width",  m.width)
                put("height", m.height)
                put("xc",     m.xc.toInt())
                put("yc",     m.yc.toInt())
                put("rc",     m.rc.toInt())
            })

            val gfArray = JSONArray()
            val ringsJson = gf.records.map { it.ring }.distinct().sorted()
            val gfMapJson = gf.records.associateBy { it.ring to it.azId }
            for (ring in ringsJson) {
                val obj = JSONObject()
                obj.put("ring_vza", ring)
                for (seg in 1..gf.nSegments) {
                    val v = gfMapJson[ring to seg]?.gf
                    obj.put("GF_seg$seg", if (v != null && !v.isNaN()) v else JSONObject.NULL)
                }
                gfArray.put(obj)
            }
            put("gap_fraction_table", gfArray)
        }

        file.writeText(root.toString(2))
        return file
    }

    // ─── Save bitmap ───────────────────────────────────────────────────
    /**
     * Saves [bitmap] to the device's public Pictures gallery so it is immediately
     * visible in Photos/Gallery without any extra permission on API 29+.
     *
     * API 29+ : MediaStore.Images — no WRITE_EXTERNAL_STORAGE permission required.
     * API 26-28: app-private external dir + MediaScannerConnection so the gallery
     *            picks up the file on those older devices.
     */
    fun saveBitmap(context: Context, bitmap: Bitmap, label: String): File {
        val filename = "CanopyAnalyzer_${label}_$dateTag.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/CanopyAnalyzer")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not create MediaStore record")
            var writeSucceeded = false
            try {
                (resolver.openOutputStream(uri)
                    ?: throw Exception("Could not open output stream for image"))
                    .use { os ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)) {
                            throw Exception("Bitmap compression failed")
                        }
                    }
                writeSucceeded = true
            } finally {
                if (writeSucceeded) {
                    // Publish the file so it is visible in the gallery
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    // Delete the pending record — don't leave a phantom invisible file
                    resolver.delete(uri, null, null)
                }
            }
            // Return value unused by callers; return a placeholder path for API compat
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            return File(dir, filename)
        } else {
            // API 26-28: write to app-private external dir + ask MediaScanner to index it
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            val file = File(dir, filename)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            return file
        }
    }

    // ─── Batch CSV Export ──────────────────────────────────────────────
    /**
     * Exports summary metrics for all successful batch results to a single CSV.
     * Each row = one image.
     */
    fun exportBatchCsv(
        context: Context,
        results: List<com.canopyanalyzer.viewmodel.AnalysisViewModel.BatchResult>
    ): File {
        val filename = "CanopyAnalyzer_batch_$dateTag.csv"
        val file = File(exportDir(context), filename)
        file.parentFile?.mkdirs()

        FileWriter(file).use { w ->
            w.appendLine("# Canopy Analyzer – Batch Export")
            w.appendLine("# Date,${csv(dateDisplay)}")
            w.appendLine("# Images,${results.size}")
            w.appendLine("")
            w.appendLine("Filename,Le,L,LX,LXG1,LXG2,DIFN,MTA_ell,x,Channel,Gamma,Lens,Rings,Segments")
            for (r in results) {
                val cr = r.result ?: continue
                val s = cr.settings
                w.appendLine("${csv(r.filename)},${cr.le},${cr.l},${cr.lx},${cr.lxg1},${cr.lxg2}," +
                    "${cr.difn},${cr.mtaEll},${cr.x},${csv(s.channel.label)},${s.gamma}," +
                    "${csv(s.lensType.label)},${s.nRings},${s.nSegments}")
            }
        }
        return file
    }

    fun getExportDir(context: Context): File =
        exportDir(context)
}
