package com.canopyanalyzer.model

import java.io.Serializable

data class AnalysisSettings(
    // Channel selection
    val channel: ChannelType = ChannelType.BLUE_3,

    // Gamma correction (default 0.85 as in the reference implementation)
    val gamma: Float = 0.85f,

    // Percentile stretch
    val stretch: Boolean = false,

    // Circular mask
    val maskMode: MaskMode = MaskMode.AUTO,
    val maskXc: Int = 0,
    val maskYc: Int = 0,
    val maskRc: Int = 0,
    val circularFisheye: Boolean = true,   // circular=True vs fullframe

    // Camera preset (used when maskMode == CAMERA_PRESET)
    val cameraPreset: CameraPreset = CameraPreset.COOLPIX950_FCE8,

    // Binarization
    val thresholdMethod: ThresholdMethod = ThresholdMethod.GLOBAL_OTSU,
    val manualThreshold: Int = 128,

    // Gap fraction
    val lensType: LensType = LensType.FC_E8,
    val maxVZA: Float = 90f,
    val startVZA: Float = 0f,
    val endVZA: Float = 70f,
    val nRings: Int = 7,
    val nSegments: Int = 8
) : Serializable

// ─── Channel ────────────────────────────────────────────────────────────────
enum class ChannelType(val label: String, val code: String) {
    BLUE_3("3 – Blue", "3"),
    RED_1("1 – Red", "1"),
    GREEN_2("2 – Green", "2"),
    B_BLUE("B – Blue (explicit)", "B"),
    FIRST_RED("first – Red", "first"),
    LUMA("Luma – Luminance", "Luma"),
    TWO_BG("2BG – 2×Blue−Green", "2BG"),
    RGB_MEAN("RGB – Mean of RGB", "RGB"),
    GLA("GLA – Green Leaf Algorithm", "GLA"),
    GEI("GEI – Green Excess Index", "GEI"),
    BTORG("BtoRG – Blue/(R+G)", "BtoRG");

    companion object {
        fun fromCode(code: String): ChannelType =
            values().firstOrNull { it.code == code } ?: BLUE_3
    }
}

// ─── Mask mode ───────────────────────────────────────────────────────────────
enum class MaskMode(val label: String) {
    AUTO("Auto-detect"),
    MANUAL("Manual (xc, yc, rc)"),
    CAMERA_PRESET("Camera Preset")
}

// ─── Camera presets (matches camera_fisheye() function) ─────────────────────
/**
 * Known fisheye camera + lens combinations with pre-calibrated mask parameters.
 * Equivalent to the camera_fisheye() function in the reference implementation.
 */
enum class CameraPreset(val label: String, val xc: Int, val yc: Int, val rc: Int) {
    COOLPIX950_FCE8("Coolpix950 + FC-E8", xc = 800, yc = 660, rc = 562),
    COOLPIX4500_FCE8("Coolpix4500 + FC-E8", xc = 1024, yc = 768, rc = 668),
    CANON5D_SIGMA8("Canon 5D + Sigma 8mm", xc = 2136, yc = 1424, rc = 1420),
    NIKON_D90_SIGMA8("Nikon D90 + Sigma 8mm", xc = 2144, yc = 1424, rc = 1400),
    NIKON_D7000_SIGMA8("Nikon D7000 + Sigma 8mm", xc = 2448, yc = 1632, rc = 1620);

    companion object {
        fun fromLabel(label: String): CameraPreset =
            values().firstOrNull { it.label == label } ?: COOLPIX950_FCE8
    }
}

// ─── Threshold method ────────────────────────────────────────────────────────
enum class ThresholdMethod(val label: String) {
    GLOBAL_OTSU("Global Otsu"),
    ZONAL_OTSU("Zonal Otsu (N/W/S/E)"),
    MANUAL("Manual Threshold")
}

// ─── Lens type ───────────────────────────────────────────────────────────────
enum class LensType(val label: String) {
    EQUIDISTANT("equidistant"),
    FC_E8("FC-E8")
}
