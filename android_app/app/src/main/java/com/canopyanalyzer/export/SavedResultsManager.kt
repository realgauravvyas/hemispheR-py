package com.canopyanalyzer.export

import android.content.Context
import com.canopyanalyzer.model.AnalysisResult
import com.canopyanalyzer.model.CanopyResult
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class SavedResultEntry(
    val id: String,
    val imageName: String,
    val timestamp: String,
    val le: Double,
    val l: Double,
    val lx: Double,
    val lxg1: Double,
    val lxg2: Double,
    val difn: Double,
    val mtaEll: Double,
    val x: Double,
    val threshold: String,
    val channel: String,
    val gamma: Float,
    val stretch: Boolean,
    val maskInfo: String,
    val maskMode: String,
    val thresholdMode: String,
    val lens: String,
    val vzaRange: String,
    val ringsSegments: String,
    val gapFracTable: String
)

object SavedResultsManager {

    private const val DIR = "saved_results"
    private val tsDisplay = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val tsFile    = SimpleDateFormat("yyyyMMdd_HHmmss",     Locale.US)

    private fun getDir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    /** Save directly from a [CanopyResult] (e.g. from batch analysis — no bitmaps needed). */
    fun saveCanopyResult(context: Context, cr: CanopyResult): String {
        val s = cr.settings
        val m = cr.meta
        val timestamp = tsDisplay.format(Date())
        val safeName  = m.filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val id        = "${tsFile.format(Date())}_$safeName.json"

        val obj = JSONObject().apply {
            put("id",           id)
            put("imageName",    m.filename)
            put("timestamp",    timestamp)
            put("le",           cr.le)
            put("l",            cr.l)
            put("lx",           cr.lx)
            put("lxg1",         cr.lxg1)
            put("lxg2",         cr.lxg2)
            put("difn",         cr.difn)
            put("mtaEll",       cr.mtaEll)
            put("x",            cr.x)
            put("threshold",    "—")
            put("channel",      s.channel.label)
            put("gamma",        s.gamma)
            put("stretch",      s.stretch)
            put("maskInfo",     "xc=${m.xc.toInt()}, yc=${m.yc.toInt()}, rc=${m.rc.toInt()}")
            put("maskMode",     s.maskMode.label)
            put("thresholdMode",s.thresholdMethod.label)
            put("imageSize",    "${m.width} × ${m.height} px")
            put("lens",         s.lensType.label)
            put("vzaRange",     "${s.startVZA}°–${s.endVZA}° (max ${s.maxVZA}°)")
            put("ringsSegments","${s.nRings} rings × ${s.nSegments} segments")
            put("gapFracTable", "")
        }

        val dir = getDir(context)
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(obj.toString(2))
        tmp.renameTo(File(dir, id))
        return id
    }

    fun save(context: Context, result: AnalysisResult): String {
        val cr  = result.canopyResult
        val s   = cr.settings
        val m   = cr.meta
        val thd = result.binaryImage.thresholds.joinToString(" / ")

        val timestamp = tsDisplay.format(Date())
        val safeName  = m.filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val id        = "${tsFile.format(Date())}_$safeName.json"

        val obj = JSONObject().apply {
            put("id",           id)
            put("imageName",    m.filename)
            put("timestamp",    timestamp)
            put("le",           cr.le)
            put("l",            cr.l)
            put("lx",           cr.lx)
            put("lxg1",         cr.lxg1)
            put("lxg2",         cr.lxg2)
            put("difn",         cr.difn)
            put("mtaEll",       cr.mtaEll)
            put("x",            cr.x)
            put("threshold",    "$thd (${result.binaryImage.method})")
            put("channel",      s.channel.label)
            put("gamma",        s.gamma)
            put("stretch",      s.stretch)
            put("maskInfo",     "xc=${m.xc.toInt()}, yc=${m.yc.toInt()}, rc=${m.rc.toInt()}")
            put("maskMode",     s.maskMode.label)
            put("thresholdMode",s.thresholdMethod.label)
            put("imageSize",    "${m.width} × ${m.height} px")
            put("lens",         s.lensType.label)
            put("vzaRange",     "${s.startVZA}°–${s.endVZA}° (max ${s.maxVZA}°)")
            put("ringsSegments","${s.nRings} rings × ${s.nSegments} segments")
            put("gapFracTable", buildGapFracTable(result))
        }

        // Write to a temp file then rename — atomic on ext4/F2FS, prevents corrupt
        // JSON if the process is killed mid-write.
        val dir = getDir(context)
        val tmp = File(dir, "$id.tmp")
        tmp.writeText(obj.toString(2))
        tmp.renameTo(File(dir, id))
        return id
    }

    fun loadAll(context: Context): List<SavedResultEntry> =
        getDir(context)
            .listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { f ->
                try {
                    val o = JSONObject(f.readText())
                    SavedResultEntry(
                        id           = o.getString("id"),
                        imageName    = o.getString("imageName"),
                        timestamp    = o.getString("timestamp"),
                        le           = o.getDouble("le"),
                        l            = o.getDouble("l"),
                        lx           = o.getDouble("lx"),
                        lxg1         = o.getDouble("lxg1"),
                        lxg2         = o.getDouble("lxg2"),
                        difn         = o.getDouble("difn"),
                        mtaEll       = o.getDouble("mtaEll"),
                        x            = o.getDouble("x"),
                        threshold    = o.optString("threshold", "—"),
                        channel      = o.optString("channel", "—"),
                        gamma        = o.optDouble("gamma", 1.0).toFloat(),
                        stretch      = o.optBoolean("stretch", false),
                        maskInfo     = o.optString("maskInfo", "—"),
                        maskMode     = o.optString("maskMode", "—"),
                        thresholdMode= o.optString("thresholdMode", "—"),
                        lens         = o.optString("lens", "—"),
                        vzaRange     = o.optString("vzaRange", "—"),
                        ringsSegments= o.optString("ringsSegments", "—"),
                        gapFracTable = o.optString("gapFracTable", "")
                    )
                } catch (e: Exception) { null }
            } ?: emptyList()

    fun delete(context: Context, id: String) {
        File(getDir(context), id).delete()
    }

    private fun buildGapFracTable(result: AnalysisResult): String {
        val gf    = result.gapFraction
        val rings = gf.records.map { it.ring }.distinct().sorted()
        val gfMap = gf.records.associateBy { it.ring to it.azId }
        val sb    = StringBuilder()
        sb.append("Ring(VZA°)")
        for (seg in 1..gf.nSegments) sb.append("  Seg$seg")
        sb.append("  Mean\n")
        sb.append("-".repeat(10 + gf.nSegments * 7 + 6)).append("\n")
        for (ring in rings) {
            sb.append("%-10.1f".format(ring))
            val vals = mutableListOf<Float>()
            for (seg in 1..gf.nSegments) {
                val v = gfMap[ring to seg]?.gf
                if (v != null && !v.isNaN()) { sb.append("  %.3f".format(v)); vals.add(v) }
                else sb.append("    NA")
            }
            if (vals.isNotEmpty()) sb.append("  %.3f".format(vals.average()))
            sb.append("\n")
        }
        return sb.toString()
    }
}
