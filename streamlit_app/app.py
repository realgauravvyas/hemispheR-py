# -*- coding: utf-8 -*-
"""
Fisheye Canopy Analyzer – Streamlit App – No OpenCV implementation
All cv2 calls replaced with PIL (Pillow) + pure-NumPy Otsu thresholding.
"""

import warnings
warnings.filterwarnings("ignore")

import os
import tempfile

import streamlit as st
import numpy as np
import pandas as pd
from PIL import Image
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Circle
from matplotlib.path import Path

# ─────────────────────────────────────────────────────────────
# PAGE CONFIG
# ─────────────────────────────────────────────────────────────
st.set_page_config(
    page_title="Fisheye Canopy Analyzer",
    page_icon="🌿",
    layout="wide",
    initial_sidebar_state="expanded",
)

st.markdown(
    """
    <style>
    [data-testid="stFileUploaderDropzoneInstructions"] > div > span {
        display: none;
    }
    [data-testid="stFileUploaderDropzoneInstructions"] small {
        display: none;
    }

    /* ── Reset to Auto: outlined Material-style button ── */
    div[data-testid="stButton"] button[kind="secondary"] {
        background-color: transparent;
        border: 1.5px solid rgba(250,250,250,0.4);
        border-radius: 8px;
        color: inherit;
        font-weight: 500;
        letter-spacing: 0.03em;
        transition: border-color 0.2s, background-color 0.2s;
    }
    div[data-testid="stButton"] button[kind="secondary"]:hover {
        background-color: rgba(250,250,250,0.08);
        border-color: rgba(250,250,250,0.75);
    }

    /* Hide +/− stepper buttons on xc/yc/rc number inputs */
    button[data-testid="stNumberInputStepDown"],
    button[data-testid="stNumberInputStepUp"] {
        display: none;
    }
    </style>
    """,
    unsafe_allow_html=True,
)

# ─────────────────────────────────────────────────────────────
# CONSTANTS
# ─────────────────────────────────────────────────────────────

LENS_TYPES = [
    "equidistant",
    "FC-E8",
]

CHANNEL_OPTIONS = {
    "3 – Blue":                   3,
    "1 – Red":                    1,
    "2 – Green":                  2,
    "B – Blue (explicit)":        "B",
    "first – Red":                "first",
    "Luma – Luminance":           "Luma",
    "2BG – 2×Blue−Green":         "2BG",
    "RGB – Mean of RGB":          "RGB",
    "GLA – Green Leaf Algorithm": "GLA",
    "GEI – Green Excess Index":   "GEI",
    "BtoRG – Blue/(R+G)":         "BtoRG",
}


# ─────────────────────────────────────────────────────────────
# CORE FUNCTIONS  (OpenCV-free)
# ─────────────────────────────────────────────────────────────

def _otsu_threshold(values_uint8):
    """Pure-NumPy Otsu's method on a 1-D uint8 array."""
    hist = np.bincount(values_uint8, minlength=256).astype(np.float64)
    total = hist.sum()
    if total == 0:
        return 0.0

    sum_total = np.dot(np.arange(256), hist)
    sum_bg = 0.0
    weight_bg = 0.0
    max_variance = 0.0
    best_threshold = 0.0

    for t in range(256):
        weight_bg += hist[t]
        if weight_bg == 0:
            continue
        weight_fg = total - weight_bg
        if weight_fg == 0:
            break

        sum_bg += t * hist[t]
        mean_bg = sum_bg / weight_bg
        mean_fg = (sum_total - sum_bg) / weight_fg

        variance = weight_bg * weight_fg * (mean_bg - mean_fg) ** 2
        if variance > max_variance:
            max_variance = variance
            best_threshold = t

    return float(best_threshold)


def _zonal_mask(img_shape, xc, yc):
    """Return N/W/S/E triangular boolean masks."""
    height, width = img_shape
    xmax, ymax = width, height
    poly_N = np.array([[0, ymax], [xc, yc], [xmax, ymax], [0, ymax]])
    poly_W = np.array([[0, ymax], [0,    0], [xc, yc],     [0, ymax]])
    poly_S = np.array([[0,    0], [xc, yc],  [xmax,  0],   [0,    0]])
    poly_E = np.array([[xmax, 0], [xmax, ymax], [xc, yc],  [xmax, 0]])
    y_grid, x_grid = np.indices((height, width))
    pts = np.column_stack([x_grid.ravel(), y_grid.ravel()])
    masks = {}
    for name, poly in zip(["N", "W", "S", "E"], [poly_N, poly_W, poly_S, poly_E]):
        masks[name] = Path(poly).contains_points(pts).reshape((height, width))
    return masks


def import_fisheye(filename, channel=3, circ_mask=None, circular=True,
                   gamma=2.2, stretch=False, original_filename=None):
    """Load and preprocess fisheye image using PIL (no OpenCV)."""
    log = []

    if not os.path.exists(filename):
        raise FileNotFoundError(f"File not found: {filename}")

    pil_img = Image.open(filename).convert("RGB")
    img_rgb = np.array(pil_img)
    height, width, n_layers = img_rgb.shape

    if not isinstance(gamma, (int, float)):
        warnings.warn('Wrong gamma input. Gamma assumed as 1 (no correction).')
        gamma = 1.0
    if gamma <= 0:
        raise ValueError(
            f"Gamma must be positive, got {gamma}. "
            f"Use gamma=1.0 for no correction or gamma=2.2 for JPEG back-correction."
        )

    def apply_gamma_hemispheR(arr, g):
        if g == 1:
            return arr
        minm, maxm = np.nanmin(arr), np.nanmax(arr)
        if maxm == minm:
            return arr
        normalized = arr / (maxm - minm)
        gamma_corrected = np.power(normalized, g)
        return (maxm - minm) * gamma_corrected

    if isinstance(channel, int):
        if channel < 1 or channel > n_layers:
            raise ValueError(f"Channel {channel} is out of range (1-{n_layers}).")
        band = img_rgb[:, :, channel - 1].astype(float)
        img_values = apply_gamma_hemispheR(band, gamma)
    else:
        R_raw = img_rgb[:, :, 0].astype(float)
        G_raw = img_rgb[:, :, 1].astype(float)
        B_raw = img_rgb[:, :, 2].astype(float)

        R = apply_gamma_hemispheR(R_raw, gamma)
        G = apply_gamma_hemispheR(G_raw, gamma)
        B = apply_gamma_hemispheR(B_raw, gamma)

        if channel == "B":
            img_values = B
        elif channel == "first":
            img_values = R
        elif channel == "Luma":
            img_values = 0.3 * R + 0.59 * G + 0.11 * B
        elif channel == "2BG":
            img_values = -G + 2 * B
        elif channel == "RGB":
            img_values = (R + G + B) / 3.0
        elif channel == "GLA":
            num = -1 * R + 2 * G - 1 * B
            den =  1 * R + 2 * G + 1 * B
            with np.errstate(divide='ignore', invalid='ignore'):
                img_values = np.where(den != 0, -(num / den), 0)
        elif channel == "GEI":
            img_values = -(-1 * R + 2 * G - 1 * B)
        elif channel == "BtoRG":
            denom = R + 2 * B + G
            with np.errstate(divide='ignore', invalid='ignore'):
                term = np.where(denom != 0, (2 * B - B - R) / denom, 0)
                img_values = B * (1 + term + B) / 2.0
        else:
            img_values = B

    img_values = np.nan_to_num(img_values, nan=0.0, posinf=0.0, neginf=0.0)

    if stretch:
        p1, p99 = np.percentile(img_values, [1, 99])
        img_values = np.clip(img_values, p1, p99)

    mn, mx = np.nanmin(img_values), np.nanmax(img_values)
    if mx > mn:
        img_values = ((img_values - mn) / (mx - mn)) * 255.0
    else:
        img_values = np.zeros_like(img_values)
    img_values = np.round(img_values)

    if circ_mask:
        if len(circ_mask) < 3:
            raise ValueError('circ_mask must contain 3 parameters: xc, yc, rc')
        xc = circ_mask.get('xc') if isinstance(circ_mask, dict) else circ_mask['xc']
        yc = circ_mask.get('yc') if isinstance(circ_mask, dict) else circ_mask['yc']
        rc = circ_mask.get('rc') if isinstance(circ_mask, dict) else circ_mask['rc']
    else:
        xc = width / 2
        yc = height / 2
        rc = (min(xc, yc) - 2) if circular else (np.sqrt(xc ** 2 + yc ** 2) - 2)

    if circ_mask is not None:
        mask_valid = True
        log.append("=== Validating Mask Parameters ===")
        log.append(f"Image dimensions: {width} x {height} pixels")
        log.append(f"Mask parameters: xc={int(xc)}, yc={int(yc)}, rc={int(rc)}")
        if (xc + rc > width) or (xc - rc < 0):
            log.append(
                f"WARNING: Mask xc±rc [{int(xc-rc)}, {int(xc+rc)}] "
                f"exceeds image width [0, {width}]!"
            )
            mask_valid = False
        if (yc + rc > height) or (yc - rc < 0):
            log.append(
                f"WARNING: Mask yc±rc [{int(yc-rc)}, {int(yc+rc)}] "
                f"exceeds image height [0, {height}]!"
            )
            mask_valid = False
        if mask_valid:
            log.append("✓ Mask parameters are valid")
        else:
            log.append("✗ Mask extends outside image bounds. Consider:")
            log.append("  Option 1: Remove circ_mask=my_mask to auto-detect")
            log.append("  Option 2: Use camera_fisheye('YourCameraModel')")
            log.append("  Option 3: Manually adjust xc, yc, or rc values")
            log.append("")
            log.append("Proceeding anyway (may produce invalid results)...")

    fisheye_type = "circular" if circular else "fullframe"
    log.append(
        f"It is a {fisheye_type} fisheye, where xc, yc and radius are "
        f"{int(xc)}, {int(yc)}, {int(rc)}"
    )

    Y, X = np.ogrid[:height, :width]
    dist_sq = (X - xc) ** 2 + (Y - yc) ** 2
    mask_boolean = dist_sq <= rc ** 2

    final_img = img_values.astype(float).copy()
    if circular or (not circular and circ_mask):
        final_img[~mask_boolean] = np.nan

    display_name = original_filename if original_filename else os.path.basename(filename)

    meta = {
        "xc": float(xc), "yc": float(yc), "rc": float(rc),
        "filename": display_name,
        "channel": str(channel),
        "gamma": gamma,
        "stretch": str(stretch).upper(),
    }
    return final_img, meta, log


def binarize_fisheye(img_data, method='Otsu', zonal=False, manual=None):
    """Threshold image to binary canopy(0)/gap(1) using pure-NumPy Otsu."""
    log = []

    if isinstance(img_data, tuple):
        img, meta = img_data[0], img_data[1]
    else:
        raise ValueError("Input must be tuple from import_fisheye: (img, meta).")

    if img.ndim > 2:
        raise ValueError("Error: please select single channel image.")

    if zonal and manual is not None:
        raise ValueError("Cannot use zonal=True AND manual threshold together!")

    if manual is not None:
        if not isinstance(manual, (int, float)):
            raise ValueError('Manual threshold must be numeric.')
        valid_vals = img[~np.isnan(img)]
        if valid_vals.size > 0:
            img_min, img_max = np.nanmin(valid_vals), np.nanmax(valid_vals)
            if manual > img_max or manual < img_min:
                raise ValueError(
                    f'Manual threshold {manual} outside image range '
                    f'[{img_min:.1f}, {img_max:.1f}].'
                )

    def get_otsu(arr_chunk):
        valid = arr_chunk[~np.isnan(arr_chunk)]
        if valid.size == 0:
            return 0
        valid_uint8 = np.clip(np.round(valid), 0, 255).astype(np.uint8)
        return _otsu_threshold(valid_uint8)

    valid_mask = ~np.isnan(img)
    thresholds_used = []

    log.append("--- Binarizing Image ---")

    if zonal and manual is None:
        xc = meta.get("xc", img.shape[1] / 2)
        yc = meta.get("yc", img.shape[0] / 2)
        masks = _zonal_mask(img.shape, xc, yc)
        temp_binary = np.zeros_like(img, dtype=np.float32)

        for zone_name in ["N", "W", "S", "E"]:
            m = masks[zone_name]
            combined_mask = m & valid_mask
            chunk = img[combined_mask]
            if chunk.size > 0:
                th = get_otsu(chunk)
                thresholds_used.append(th)
                temp_binary[combined_mask] = np.where(chunk > th, 1.0, 0.0)
            else:
                thresholds_used.append(0)
        binary_img = np.where(valid_mask, temp_binary, np.nan)

    elif manual is not None:
        th = float(manual)
        thresholds_used.append(th)
        binary_img = np.where(valid_mask,
                              np.where(img > th, 1.0, 0.0),
                              np.nan)
    else:
        th = get_otsu(img)
        thresholds_used.append(th)
        binary_img = np.where(valid_mask,
                              np.where(img > th, 1.0, 0.0),
                              np.nan)

    binary_img[~valid_mask] = np.nan

    thd_str = "_".join([str(int(t)) for t in thresholds_used])
    new_meta = meta.copy()
    new_meta["zonal"] = str(zonal).upper()
    new_meta["method"] = method if manual is None else "manual"
    new_meta["thd"] = thd_str

    return binary_img, new_meta, log


def _get_radius(vza, lens, rc, maxVZA):
    """Pixel radius for a zenith angle given lens distortion model."""
    x = vza / maxVZA
    if lens == 'equidistant':
        result = rc * x
    elif lens == 'FC-E8':
        result = rc * (1.06 * x + 0.00498 * x**2 - 0.0639 * x**3)
    else:
        result = rc * x
    return round(result)


def gapfrac_fisheye(img_data, maxVZA=90, lens='equidistant',
                    startVZA=0, endVZA=70, nrings=7, nseg=8):
    """Calculate angular gap fraction."""
    log = []

    if isinstance(img_data, tuple):
        img_bw, meta = img_data[0], img_data[1]
    else:
        raise ValueError("Input must be tuple from binarize_fisheye.")

    vals = img_bw[~np.isnan(img_bw)]
    unique_vals = np.unique(vals)

    if not np.all(np.isin(unique_vals, [0, 1])):
        raise ValueError("Image must be binary (0,1) from binarize_fisheye().")
    if len(unique_vals) < 2:
        raise ValueError("Image contains only 0 or only 1. Gap fraction impossible.")

    height, width = img_bw.shape
    maxVZA = float(maxVZA)

    xc = meta.get("xc")
    yc = meta.get("yc")
    rc = meta.get("rc")

    if xc is None or yc is None or rc is None:
        y_coords, x_coords = np.where(~np.isnan(img_bw))
        xc = round(np.mean(x_coords))
        yc = round(np.mean(y_coords))
        unique_img_vals = len(np.unique(img_bw[~np.isnan(img_bw)]))
        if unique_img_vals == 2:
            rc = round(np.sqrt(xc**2 + yc**2)) - 2
        else:
            rc = round((np.max(x_coords) - np.min(x_coords)) / 2)
        log.append(f"Recomputed xc, yc, rc: {int(xc)}, {int(yc)}, {int(rc)}")
    else:
        xc, yc, rc = float(xc), float(yc), float(rc)
        log.append(f"Used xc, yc, rc: {int(xc)}, {int(yc)}, {int(rc)}")

    y_grid, x_grid = np.indices((height, width))
    dx = x_grid - xc
    dy = y_grid - yc
    r_px = np.sqrt(dx ** 2 + dy ** 2)

    theta_deg = np.degrees(np.arctan2(dx, dy))
    theta_deg = np.where(theta_deg < 0, theta_deg + 360, theta_deg)

    vza_step = (endVZA - startVZA) / nrings
    vza_bins = np.arange(startVZA, endVZA + 0.001, vza_step)
    vza_centers = [(vza_bins[i] + vza_bins[i + 1]) / 2
                   for i in range(len(vza_bins) - 1)]
    vza_string = "_".join([str(int(c)) for c in vza_centers])

    r_bounds = [_get_radius(v, lens, rc, maxVZA) for v in vza_bins]

    seg_step = 360 / nseg
    seg_bins = np.arange(0, 360 + 0.001, seg_step)

    records = []
    for i in range(nrings):
        r_inner = r_bounds[i]
        r_outer = r_bounds[i + 1]
        ring_center_angle = (vza_bins[i] + vza_bins[i + 1]) / 2
        mask_r = (r_px >= r_inner) & (r_px < r_outer)

        for j in range(nseg):
            az_inner = seg_bins[j]
            az_outer = seg_bins[j + 1]
            mask_az = (theta_deg >= az_inner) & (theta_deg < az_outer)
            mask = mask_r & mask_az

            pixels = img_bw[mask]
            valid = pixels[~np.isnan(pixels)]
            gf = np.mean(valid) if valid.size > 0 else np.nan

            records.append({"ring": ring_center_angle,
                            "az_id": j + 1,
                            "GF": gf})

    df = pd.DataFrame(records)
    df_wide = df.pivot(index="ring", columns="az_id", values="GF").reset_index()
    df_wide.columns = ["ring"] + [f"GF{c}" for c in df_wide.columns if c != "ring"]

    df_wide["id"] = meta.get("filename", "image")
    df_wide["lens"] = lens
    df_wide["mask"] = f"{int(xc)}_{int(yc)}_{int(rc)}"
    df_wide["rings"] = nrings
    df_wide["azimuths"] = nseg
    df_wide["VZA"] = vza_string

    for k in ["channel", "stretch", "gamma", "zonal", "method", "thd"]:
        if k in meta:
            df_wide[k] = meta[k]

    return df_wide, r_bounds, seg_bins, xc, yc, log


def canopy_fisheye(rdfw):
    """Compute canopy attributes from gap fraction data."""
    unique_ids = rdfw["id"].unique()
    if len(unique_ids) == 0:
        raise ValueError("No image IDs found in input dataframe")

    all_results = []
    for img_id in unique_ids:
        df_sub = rdfw[rdfw["id"] == img_id].copy()
        result = _calc_canopy(df_sub)
        all_results.append(result)

    cols_order = [
        "id", "Le", "L", "LX", "LXG1", "LXG2", "DIFN", "MTA.ell", "x",
        "VZA", "rings", "azimuths", "mask", "lens", "channel",
        "stretch", "gamma", "zonal", "method", "thd",
    ]
    res = pd.DataFrame(all_results)
    for c in cols_order:
        if c not in res.columns:
            res[c] = None
    return res[cols_order]


def _calc_canopy(df):
    """Internal: compute canopy metrics for a single image."""
    gf_cols = [c for c in df.columns if c.startswith("GF")]
    if len(gf_cols) == 0:
        raise ValueError("No GF columns found in dataframe")

    for c in gf_cols:
        df[c] = df[c].replace(0, 0.00004530)

    df["GapFr"] = df[gf_cols].mean(axis=1)

    rads = np.radians(df["ring"])
    sinth = np.sin(rads)
    costh = np.cos(rads)

    w = sinth / np.sum(sinth)
    W = (sinth * costh) / np.sum(sinth * costh) / 2.0

    Le_part = -np.log(df["GapFr"]) * w * costh
    Le = 2 * Le_part.sum()

    log_segs = -np.log(df[gf_cols])
    L_part = log_segs.mean(axis=1) * w * costh
    L = 2 * L_part.sum()

    LX = Le / L if L != 0 else 0

    DIFN = (df["GapFr"] * 2 * W).sum() * 100

    def ellip_func(z_rad, x):
        num = np.sqrt(x ** 2 + np.tan(z_rad) ** 2)
        term = (((0.000509 * x - 0.013) * x + 0.1223) * x + 0.45) * x
        return num / (1.47 + term)

    Z = rads.values
    T1 = df["GapFr"].values
    x_param = 1.0
    dx = 0.01
    xmin, xmax_val = 0.1, 10.0

    for _ in range(50):
        if (xmax_val - xmin) < dx:
            break
        KB = ellip_func(Z, x_param)
        DK = ellip_func(Z, x_param + dx) - KB
        S1 = np.sum(np.log(T1) * KB)
        S2 = np.sum(KB ** 2)
        S3 = np.sum(KB * DK)
        S4 = np.sum(DK * np.log(T1))
        F = S2 * S4 - S1 * S3
        if F < 0:
            xmin = x_param
        else:
            xmax_val = x_param
        x_param = (xmax_val + xmin) / 2.0

    MTA_ell = 90 * (0.1 + 0.9 * np.exp(-0.5 * x_param))

    n = len(gf_cols)
    w23 = (2 * np.arange(n, 0, -1)) / (n * (n + 1))
    harmonic = 1.0 / np.arange(1, n + 1)
    w34_raw = harmonic / n
    w34 = np.cumsum(w34_raw[::-1])[::-1]

    lxg1_w_sum = 0.0
    lxg2_w_sum = 0.0

    for i, row in df.iterrows():
        vals = row[gf_cols].values
        vals_sorted = np.sort(vals)[::-1]

        sum_a23 = np.sum(vals_sorted * w23)
        sum_r23 = np.sum(vals_sorted * w23[::-1])
        sum_a34 = np.sum(vals_sorted * w34)
        sum_r34 = np.sum(vals_sorted * w34[::-1])

        lxg1 = (np.log(sum_a23) / np.log(sum_r23)
                if (sum_a23 > 0 and sum_r23 > 0 and abs(sum_r23 - 1) > 1e-9)
                else 1)
        lxg2 = (np.log(sum_a34) / np.log(sum_r34)
                if (sum_a34 > 0 and sum_r34 > 0)
                else 1)

        lxg1_w_sum += lxg1 * w[i]
        lxg2_w_sum += lxg2 * w[i]

    meta_row = df.iloc[0]

    def safe_get(key, default=None):
        if key in meta_row.index:
            val = meta_row[key]
            if pd.isna(val):
                return default
            return val
        return default

    return {
        "id": safe_get("id", "unknown"),
        "Le": round(Le, 2),
        "L": round(L, 2),
        "LX": round(LX, 2),
        "LXG1": round(lxg1_w_sum, 2),
        "LXG2": round(lxg2_w_sum, 2),
        "DIFN": round(DIFN, 1),
        "MTA.ell": round(MTA_ell, 1),
        "x": round(x_param, 2),
        "VZA": safe_get("VZA"),
        "rings": safe_get("rings", len(df)),
        "azimuths": safe_get("azimuths", n),
        "mask": safe_get("mask"),
        "lens": safe_get("lens"),
        "channel": safe_get("channel"),
        "stretch": safe_get("stretch"),
        "gamma": safe_get("gamma"),
        "zonal": safe_get("zonal"),
        "method": safe_get("method"),
        "thd": safe_get("thd"),
    }


# ─────────────────────────────────────────────────────────────
# PLOT HELPERS
# ─────────────────────────────────────────────────────────────

def plot_mask_preview(rgb_arr, xc, yc, rc):
    """
    Show original colour image with the mask circle overlaid.
    Uses origin='upper' so numpy/PIL (row=y-from-top) coordinates map correctly.
    """
    h, w = rgb_arr.shape[:2]
    fig, ax = plt.subplots(figsize=(7, 6))
    ax.imshow(rgb_arr)                                           # origin='upper' (default)
    ax.add_patch(Circle((xc, yc), rc, fill=False, edgecolor="red", lw=2.5))
    ax.set_title(
        f"Mask Preview  •  xc = {int(xc)}   yc = {int(yc)}   rc = {int(rc)}",
        fontsize=11,
    )
    ax.set_xlim(0, w)
    ax.set_ylim(h, 0)    # image-style: y=0 at top
    ax.axis("off")
    plt.tight_layout()
    return fig


def plot_import(img, meta):
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(img, cmap="gray", vmin=0, vmax=255, origin="lower")
    xc, yc, rc = meta["xc"], meta["yc"], meta["rc"]
    ax.add_patch(Circle((xc, yc), rc, fill=False, edgecolor="red", lw=2))
    ax.plot([xc, xc + rc], [yc, yc], "r-", lw=2)
    ax.set_title("Imported Fisheye (Masked)")
    cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    cbar.set_label("Pixel Value (0=Dark, 255=White)")
    plt.tight_layout()
    return fig


def plot_binary(img, thd_str):
    thresholds = [int(t) for t in thd_str.split("_")]
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(img, cmap="gray", origin="lower", vmin=0, vmax=1)
    if len(thresholds) > 1:
        title = (f"Binarized Fisheye (Canopy=0, Gap=1)\n"
                 f"Zonal thresholds: {', '.join(str(t) for t in thresholds)}")
    else:
        title = (f"Binarized Fisheye (Canopy=0, Gap=1)\n"
                 f"Single threshold: {thresholds[0]}")
    ax.set_title(title)
    cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04, ticks=[0, 1])
    cbar.ax.set_yticklabels(["Canopy (0)", "Gap (1)"])
    plt.tight_layout()
    return fig


def plot_gapfrac(img_bw, xc, yc, r_bounds, seg_bins, lens):
    fig, ax = plt.subplots(figsize=(6, 5))
    im = ax.imshow(img_bw, cmap="gray", origin="lower", vmin=0, vmax=1)
    for r_b in r_bounds:
        if r_b > 0:
            ax.add_patch(Circle((xc, yc), r_b, fill=False, edgecolor="yellow", lw=2))
    max_r = max(r_bounds)
    for seg_angle in seg_bins:
        ar = np.radians(seg_angle)
        ax.plot([xc, xc + max_r * np.sin(ar)],
                [yc, yc + max_r * np.cos(ar)], "y-", lw=1.5)
    cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04, ticks=[0, 1])
    cbar.ax.set_yticklabels(["Canopy", "Gap"])
    ax.set_title(f"Applied Rings & Segments ({lens})")
    plt.tight_layout()
    return fig


# ─────────────────────────────────────────────────────────────
# HELPERS
# ─────────────────────────────────────────────────────────────

def _auto_rc(xc, yc, circular):
    """Default radius from image-centre coordinates."""
    if circular:
        return int(min(xc, yc) - 2)
    return int(np.sqrt(xc ** 2 + yc ** 2) - 2)


# ─────────────────────────────────────────────────────────────
# STREAMLIT UI
# ─────────────────────────────────────────────────────────────

st.title("🌿 Fisheye Canopy Analyzer")
st.divider()

# ── Sidebar ───────────────────────────────────────────────────
with st.sidebar:
    st.header("⚙️ Parameters")
    uploaded = st.file_uploader(
        "Upload Fisheye Image",
        type=["jpg", "jpeg", "png", "tif", "tiff"],
        label_visibility="visible",
    )

    # Reset all state whenever a new file is uploaded
    if uploaded is not None:
        if st.session_state.get("_last_upload") != uploaded.name:
            for k in ("mask_rgb", "adj_xc", "adj_yc", "adj_rc", "_prev_circular",
                      "_img_w", "_img_h",
                      "img_data", "bin_data", "gap_df", "gap_extras", "canopy_df",
                      "log_import", "log_bin", "log_gap"):
                st.session_state.pop(k, None)
            # Cache image dimensions and RGB array immediately
            pil_img = Image.open(uploaded).convert("RGB")
            rgb_arr = np.array(pil_img)
            h_img, w_img = rgb_arr.shape[:2]
            st.session_state["mask_rgb"]      = rgb_arr
            st.session_state["_img_w"]        = w_img
            st.session_state["_img_h"]        = h_img
            st.session_state["_last_upload"]  = uploaded.name
            # Pre-initialise mask defaults so number_input widgets pick them up
            st.session_state["adj_xc"] = w_img // 2
            st.session_state["adj_yc"] = h_img // 2
            st.session_state["adj_rc"] = _auto_rc(w_img // 2, h_img // 2, True)
            st.rerun()  # fresh render so widgets see the new session state

    # ── 1 · Circular Mask ─────────────────────────────────────
    st.subheader("1 · Circular Mask")
    mask_mode = st.radio(
        "Mask source",
        ["Auto-detect", "Manual"],
        help=(
            "**Auto-detect**: mask centre and radius are computed from the image "
            "dimensions. Fine-tune xc / yc / rc before running analysis.\n\n"
            "**Manual**: enter xc, yc, rc directly in pixels."
        ),
    )

    circ_mask = None
    circular  = True

    if mask_mode == "Auto-detect":
        circular = st.checkbox("Circular fisheye (uncheck = fullframe)", value=True)

        if uploaded is not None:
            w_img = st.session_state["_img_w"]
            h_img = st.session_state["_img_h"]

            # Update default rc if circular mode changes
            prev_circ = st.session_state.get("_prev_circular", circular)
            if prev_circ != circular:
                st.session_state["adj_rc"] = _auto_rc(w_img // 2, h_img // 2, circular)
                st.session_state["_prev_circular"] = circular

            st.caption(
                f"Image: {w_img} × {h_img} px  •  "
                f"Auto centre: ({w_img // 2}, {h_img // 2})"
            )

            if "_mask_step" not in st.session_state:
                st.session_state["_mask_step"] = 40
            st.number_input(
                "Step size per tap (px)", min_value=1, max_value=500,
                step=1, key="_mask_step",
                help="How many pixels each arrow-button tap moves xc, yc or rc — "
                     "matches the Mask Move Step setting in the Android app.",
            )

            # Callback factories — run before re-render, so widget key can be updated
            def _dec_cb(key, mn):
                def cb():
                    st.session_state[key] = max(mn, int(st.session_state[key]) - int(st.session_state["_mask_step"]))
                return cb

            def _inc_cb(key, mx):
                def cb():
                    st.session_state[key] = min(mx, int(st.session_state[key]) + int(st.session_state["_mask_step"]))
                return cb

            # Arrow-button rows matching Android Fine-Tune Circular Mask layout
            for _lbl, _key, _dec, _inc, _min, _max in [
                ("xc", "adj_xc", "←", "→", 0, w_img),
                ("yc", "adj_yc", "↑", "↓", 0, h_img),
                ("rc", "adj_rc", "⊖", "⊕", 1, max(w_img, h_img)),
            ]:
                lbl_c, dec_c, val_c, inc_c = st.columns([0.5, 1, 2, 1])
                lbl_c.markdown(
                    f"<p style='margin:0;padding-top:8px;font-weight:600'>{_lbl}</p>",
                    unsafe_allow_html=True,
                )
                dec_c.button(_dec, key=f"btn_{_key}_dec",
                             on_click=_dec_cb(_key, _min), use_container_width=True)
                val_c.number_input(
                    _lbl, min_value=_min, max_value=_max, step=1,
                    key=_key, label_visibility="collapsed",
                )
                inc_c.button(_inc, key=f"btn_{_key}_inc",
                             on_click=_inc_cb(_key, _max), use_container_width=True)

            def _reset_cb(circ=circular):
                w = st.session_state["_img_w"]
                h = st.session_state["_img_h"]
                st.session_state["adj_xc"] = w // 2
                st.session_state["adj_yc"] = h // 2
                st.session_state["adj_rc"] = _auto_rc(w // 2, h // 2, circ)

            col_reset, col_status = st.columns(2)
            col_reset.button("← Reset to Auto", on_click=_reset_cb, use_container_width=True)
            col_status.success(
                f"✓ xc={st.session_state['adj_xc']}  "
                f"yc={st.session_state['adj_yc']}  "
                f"rc={st.session_state['adj_rc']}"
            )

            circ_mask = {
                "xc": int(st.session_state["adj_xc"]),
                "yc": int(st.session_state["adj_yc"]),
                "rc": int(st.session_state["adj_rc"]),
            }
        else:
            st.caption("Upload an image to enable mask adjustment.")

    else:  # Manual
        circular = st.checkbox(
            "Circular fisheye (uncheck = fullframe)", value=True, key="_manual_circ"
        )
        w_lim = st.session_state.get("_img_w", 20000)
        h_lim = st.session_state.get("_img_h", 20000)
        default_xc = w_lim // 2
        default_yc = h_lim // 2
        default_rc = _auto_rc(default_xc, default_yc, circular)
        c1, c2, c3 = st.columns(3)
        xc_m = c1.number_input("xc (px)", 0, w_lim, default_xc, step=50)
        yc_m = c2.number_input("yc (px)", 0, h_lim, default_yc, step=50)
        rc_m = c3.number_input("rc (px)", 1, max(w_lim, h_lim), default_rc, step=50)
        circ_mask = {"xc": int(xc_m), "yc": int(yc_m), "rc": int(rc_m)}

    # ── 2 · Import ────────────────────────────────────────────
    st.subheader("2 · Import")
    ch_label = st.selectbox("Channel", list(CHANNEL_OPTIONS.keys()), index=0,
                            help="Blue channel (3 / B) is standard for canopy analysis.")
    channel = CHANNEL_OPTIONS[ch_label]
    gamma = st.slider(
        "Gamma correction", 0.10, 5.0, 0.85, 0.05,
        help=(
            "Power applied to pixel values before thresholding.\n\n"
            "• **1.0** – no correction\n"
            "• **0.85** – slight brightening (Code 2 default)\n"
            "• **2.2** – JPEG linearisation (hemispheR default)"
        ),
    )
    stretch = st.checkbox("Contrast stretch (1st–99th percentile)", False)

    # ── 3 · Binarize ──────────────────────────────────────────
    st.subheader("3 · Binarize")
    zonal = st.checkbox("Zonal thresholding (N/W/S/E)", False,
                        help="Separate Otsu threshold per quadrant.")
    manual_val = st.number_input(
        "Manual threshold (0 = auto Otsu)", 0, 255, 0,
        help="Set > 0 to override automatic thresholding.",
    )
    manual = None if manual_val == 0 else int(manual_val)
    if zonal and manual is not None:
        st.warning("Zonal + manual are mutually exclusive. Manual will be ignored.")
        manual = None

    # ── 4 · Gap Fraction ──────────────────────────────────────
    st.subheader("4 · Gap Fraction")
    lens     = st.selectbox("Lens type", LENS_TYPES, index=LENS_TYPES.index("FC-E8"),
                            help="Select the fisheye lens model for distortion correction.")
    maxVZA   = st.number_input("Max VZA (°)", 1, 180, 90)
    startVZA = st.number_input("Start VZA (°)", 0, 89, 0)
    endVZA   = st.number_input("End VZA (°)", 1, 90, 70,
                               help="70° for standard LAI; 75° for LAI-2000.")
    nrings   = st.slider("Zenith rings", 1, 20, 7)
    nseg     = st.slider("Azimuth segments", 1, 36, 8)

    st.divider()
    run_btn = st.button("▶  Run Analysis", type="primary", use_container_width=True)
    if st.button("🗑  Clear Results", use_container_width=True):
        for k in ("img_data", "bin_data", "gap_df", "gap_extras", "canopy_df",
                  "log_import", "log_bin", "log_gap"):
            st.session_state.pop(k, None)
        st.rerun()

# ── Guard: nothing uploaded ────────────────────────────────────
if uploaded is None:
    st.info("👈  Upload a fisheye image in the sidebar to begin.")
    st.stop()

# ── Live Mask Preview (hidden once analysis has run, matching Android pipeline) ──
if "mask_rgb" in st.session_state and circ_mask is not None and not run_btn and "img_data" not in st.session_state:
    cur_xc  = circ_mask["xc"]
    cur_yc  = circ_mask["yc"]
    cur_rc  = circ_mask["rc"]
    rgb_arr = st.session_state["mask_rgb"]
    h_img, w_img = rgb_arr.shape[:2]

    out_of_bounds = (
        cur_xc + cur_rc > w_img or cur_xc - cur_rc < 0 or
        cur_yc + cur_rc > h_img or cur_yc - cur_rc < 0
    )

    with st.expander(
        f"🎯 Mask Preview  —  xc={cur_xc}  yc={cur_yc}  rc={cur_rc}"
        + ("  ⚠️ Out of bounds!" if out_of_bounds else ""),
        expanded=True,
    ):
        col_fig, col_stats = st.columns([3, 1])
        with col_fig:
            fig = plot_mask_preview(rgb_arr, cur_xc, cur_yc, cur_rc)
            st.pyplot(fig, use_container_width=True)
            plt.close(fig)
        with col_stats:
            st.metric("Image W",       f"{w_img} px")
            st.metric("Image H",       f"{h_img} px")
            st.metric("xc",            f"{cur_xc} px")
            st.metric("yc",            f"{cur_yc} px")
            st.metric("rc",            f"{cur_rc} px")
            coverage = min(100.0, np.pi * cur_rc ** 2 / (w_img * h_img) * 100)
            st.metric("Mask coverage", f"{coverage:.1f} %")
            if out_of_bounds:
                st.warning("⚠️ Circle extends outside the image — adjust rc or centre.")

# ── Run pipeline ───────────────────────────────────────────────
if run_btn:
    suffix = os.path.splitext(uploaded.name)[1] or ".jpg"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(uploaded.getvalue())
        tmp_path = tmp.name

    try:
        with st.spinner("Step 1/4 · Importing image …"):
            img, meta, log_import = import_fisheye(
                tmp_path,
                channel=channel,
                circ_mask=circ_mask,
                circular=circular,
                gamma=gamma,
                stretch=stretch,
                original_filename=uploaded.name,
            )
        st.session_state["img_data"]   = (img, meta)
        st.session_state["log_import"] = log_import

        with st.spinner("Step 2/4 · Binarizing …"):
            bin_img, bin_meta, log_bin = binarize_fisheye(
                st.session_state["img_data"],
                method='Otsu',
                zonal=zonal,
                manual=manual,
            )
        st.session_state["bin_data"] = (bin_img, bin_meta)
        st.session_state["log_bin"]  = log_bin

        with st.spinner("Step 3/4 · Calculating gap fractions …"):
            gap_df, r_bounds, seg_bins, gxc, gyc, log_gap = gapfrac_fisheye(
                st.session_state["bin_data"],
                maxVZA=maxVZA, lens=lens,
                startVZA=startVZA, endVZA=endVZA,
                nrings=nrings, nseg=nseg,
            )
        st.session_state["gap_df"]     = gap_df
        st.session_state["gap_extras"] = (r_bounds, seg_bins, gxc, gyc)
        st.session_state["log_gap"]    = log_gap

        with st.spinner("Step 4/4 · Deriving canopy attributes …"):
            canopy_df = canopy_fisheye(gap_df)
        st.session_state["canopy_df"] = canopy_df

    except Exception as exc:
        st.error(f"**Error during analysis:** {exc}")
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass

# ── Result tabs ────────────────────────────────────────────────
has_data = "img_data" in st.session_state


def _edit_mask_cb():
    """Clear analysis results so the mask preview re-appears for re-adjustment."""
    for k in ("img_data", "bin_data", "gap_df", "gap_extras", "canopy_df",
              "log_import", "log_bin", "log_gap"):
        st.session_state.pop(k, None)


if has_data:
    edit_col, _ = st.columns([1, 4])
    edit_col.button(
        "🎯  Edit Mask & Re-run",
        on_click=_edit_mask_cb,
        use_container_width=True,
        help="Clear results and return to mask adjustment so you can tweak xc / yc / rc "
             "and run the analysis again.",
    )
    tab1, tab2, tab3, tab4 = st.tabs(
        ["📷  Import", "⬛  Binarize", "🎯  Gap Fraction", "🌳  Canopy Attributes"]
    )

    with tab1:
        img, meta = st.session_state["img_data"]
        st.success(
            f"**{meta['filename']}** · "
            f"xc={meta['xc']:.0f} px  yc={meta['yc']:.0f} px  "
            f"rc={meta['rc']:.0f} px  |  "
            f"Channel: {meta['channel']}  Gamma: {meta['gamma']}"
        )
        if st.session_state.get("log_import"):
            with st.expander("📋 Import Log", expanded=True):
                st.code("\n".join(st.session_state["log_import"]), language=None)
        col_fig, col_stats = st.columns([3, 1])
        with col_fig:
            fig = plot_import(img, meta)
            st.pyplot(fig, use_container_width=True)
            plt.close(fig)
        with col_stats:
            st.metric("Image width",  f"{img.shape[1]} px")
            st.metric("Image height", f"{img.shape[0]} px")
            st.metric("Mask coverage",
                      f"{100 * int((~np.isnan(img)).sum()) / img.size:.1f} %")
            st.metric("Channel", meta["channel"])
            st.metric("Gamma",   meta["gamma"])
            st.metric("Stretch", meta["stretch"])

    with tab2:
        if "bin_data" not in st.session_state:
            st.info("Click **Run Analysis** to process the image.")
        else:
            bin_img, bin_meta = st.session_state["bin_data"]
            thd_str = bin_meta["thd"]
            st.success(
                f"Method: **{bin_meta['method']}**  |  "
                f"Threshold(s): **{thd_str}**  |  "
                f"Zonal: **{bin_meta['zonal']}**"
            )
            if st.session_state.get("log_bin"):
                with st.expander("📋 Binarize Log", expanded=True):
                    st.code("\n".join(st.session_state["log_bin"]), language=None)
            col_fig, col_stats = st.columns([3, 1])
            with col_fig:
                fig = plot_binary(bin_img, thd_str)
                st.pyplot(fig, use_container_width=True)
                plt.close(fig)
            with col_stats:
                valid = bin_img[~np.isnan(bin_img)]
                gap_pct    = 100.0 * valid.sum() / valid.size if valid.size > 0 else 0.0
                canopy_pct = 100.0 - gap_pct
                st.metric("Gap (sky)", f"{gap_pct:.1f} %")
                st.metric("Canopy",    f"{canopy_pct:.1f} %")

    with tab3:
        if "gap_df" not in st.session_state:
            st.info("Click **Run Analysis** to process the image.")
        else:
            gap_df = st.session_state["gap_df"]
            r_bounds, seg_bins, gxc, gyc = st.session_state["gap_extras"]
            st.success(
                f"Lens: **{lens}**  |  Rings: **{nrings}**  |  "
                f"Segments: **{nseg}**  |  VZA range: **{startVZA}°–{endVZA}°**"
            )
            if st.session_state.get("log_gap"):
                with st.expander("📋 Gap Fraction Log", expanded=True):
                    st.code("\n".join(st.session_state["log_gap"]), language=None)
            fig = plot_gapfrac(
                st.session_state["bin_data"][0],
                gxc, gyc, r_bounds, seg_bins, lens,
            )
            st.pyplot(fig, use_container_width=True)
            plt.close(fig)

    with tab4:
        if "canopy_df" not in st.session_state:
            st.info("Click **Run Analysis** to process the image.")
        else:
            canopy_df = st.session_state["canopy_df"]
            row = canopy_df.iloc[0]

            st.subheader("Key Metrics")
            m1, m2, m3, m4, m5, m6 = st.columns(6)
            m1.metric("Le  (Effective LAI)", f"{row['Le']:.2f}",
                      help="Effective LAI from average gap fraction per ring.")
            m2.metric("L  (True LAI)", f"{row['L']:.2f}",
                      help="True LAI from Miller's theorem applied per segment.")
            m3.metric("LX  (Clumping)", f"{row['LX']:.2f}",
                      help="Lang & Xiang clumping index Le/L.")
            m4.metric("DIFN (%)", f"{row['DIFN']:.1f}",
                      help="Diffuse non-interceptance (canopy openness).")
            m5.metric("MTA (°)", f"{row['MTA.ell']:.1f}",
                      help="Mean tilt angle from ellipsoidal distribution.")
            m6.metric("x  (ellipsoidal)", f"{row['x']:.2f}",
                      help="Ellipsoidal leaf angle distribution parameter.")

            st.subheader("Additional Indices")
            a1, a2 = st.columns(2)
            a1.metric("LXG1", f"{row['LXG1']:.2f}", help="Gap-size based clumping index.")
            a2.metric("LXG2", f"{row['LXG2']:.2f}", help="Alternative gap-size clumping index.")

            st.subheader("Full Results Table")
            st.dataframe(
                canopy_df.T.rename(columns={0: "Value"}),
                use_container_width=True,
            )

elif not run_btn:
    st.info("Configure parameters in the sidebar and click **▶ Run Analysis**.")
