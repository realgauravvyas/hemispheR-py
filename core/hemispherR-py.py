import numpy as np
import pandas as pd
from PIL import Image
import os

CONFIG = {
    "filename":r"C:\Users\Gaurav\Documents\GitHub\hemispheR-py\sample_images\P072.jpg", #change the image path
    "circ_mask": {"xc": 1496, "yc": 2408, "rc": 1414},#change the mask parameters or set None for automasking
    "channel": 3,
    "circular": True,
    "gamma": 0.85,
    "stretch": False,
    "method": "Otsu",
    "zonal": True,
    "manual_threshold": None,
    "maxVZA": 90,
    "lens": "FC-E8",
    "startVZA": 0,
    "endVZA": 70,
    "nrings": 7,
    "nseg": 8,
    "display":True,
    "export_binary": False,
    "verbose": True,

}


def camera_fisheye(model=None):
    cameras = {
        "Coolpix950+FC-E8": {"xc": 800, "yc": 660, "rc": 562},
    }
    if model is None:
        available = "\n".join(f"  - {k}" for k in cameras)
        raise ValueError(f"Missing model parameter. Available:\n{available}")
    if model not in cameras:
        available = "\n".join(f"  - {k}" for k in cameras)
        raise ValueError(f"Unknown model: '{model}'. Available:\n{available}")
    return cameras[model]


def zonal_mask(img_shape, xc, yc):
    height, width = img_shape
    y_grid, x_grid = np.indices((height, width))
    dx = x_grid - xc
    dy = y_grid - yc
    angle = np.degrees(np.arctan2(dx, dy))
    angle = np.where(angle < 0, angle + 360, angle)
    return {
        "N": (angle >= 315) | (angle < 45),
        "E": (angle >= 45) & (angle < 135),
        "S": (angle >= 135) & (angle < 225),
        "W": (angle >= 225) & (angle < 315),
    }


def _otsu_threshold(values_uint8):
    hist = np.bincount(values_uint8, minlength=256).astype(np.float64)
    total = hist.sum()
    if total == 0:
        return 0.0

    sum_total = np.dot(np.arange(256), hist)
    sum_bg = 0.0
    weight_bg = 0.0
    max_variance = 0.0
    best = 0

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
            best = t

    return float(best)


def _apply_gamma(arr, g):
    if g == 1.0:
        return arr
    minm = np.nanmin(arr)
    maxm = np.nanmax(arr)
    if maxm == minm:
        return arr
    return (maxm - minm) * np.power(arr / (maxm - minm), g)


def import_fisheye(filename, channel=3, circ_mask=None, circular=True,
                   gamma=2.2, stretch=False, display=False, message=True):

    if not os.path.exists(filename):
        raise FileNotFoundError(f"File not found: {filename}")

    pil_img = Image.open(filename).convert("RGB")
    img_rgb = np.array(pil_img)
    pil_img.close()
    height, width, n_layers = img_rgb.shape

    if not isinstance(gamma, (int, float)):
        print("Warning: invalid gamma, using 1.0")
        gamma = 1.0
    if gamma <= 0:
        raise ValueError(f"Gamma must be positive, got {gamma}.")

    if isinstance(channel, int):
        if channel < 1 or channel > n_layers:
            raise ValueError(f"Channel {channel} out of range (1-{n_layers}).")
        img_values = _apply_gamma(img_rgb[:, :, channel - 1].astype(float), gamma)
    else:
        R = _apply_gamma(img_rgb[:, :, 0].astype(float), gamma)
        G = _apply_gamma(img_rgb[:, :, 1].astype(float), gamma)
        B = _apply_gamma(img_rgb[:, :, 2].astype(float), gamma)

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
        elif channel == "GEI":
            img_values = R - 2 * G + B
        elif channel == "GLA":
            num = -R + 2 * G - B
            den = R + 2 * G + B
            with np.errstate(divide="ignore", invalid="ignore"):
                img_values = np.where(den != 0, -(num / den), 0)
        elif channel == "BtoRG":
            denom = R + 2 * B + G
            with np.errstate(divide="ignore", invalid="ignore"):
                term = np.where(denom != 0, (2 * B - B - R) / denom, 0)
                img_values = B * (1 + term + B) / 2.0
        else:
            print(f"Warning: unknown channel '{channel}', using Blue band.")
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
        if not all(k in circ_mask for k in ("xc", "yc", "rc")):
            raise ValueError("circ_mask must have keys: xc, yc, rc")
        xc, yc, rc = circ_mask["xc"], circ_mask["yc"], circ_mask["rc"]
    else:
        xc = width / 2
        yc = height / 2
        rc = (min(xc, yc) - 2) if circular else (np.sqrt(xc**2 + yc**2) - 2)

    if circ_mask is not None:
        if (xc + rc > width) or (xc - rc < 0):
            print(f"Warning: mask x-range [{xc-rc:.0f}, {xc+rc:.0f}] outside width [0, {width}]")
        if (yc + rc > height) or (yc - rc < 0):
            print(f"Warning: mask y-range [{yc-rc:.0f}, {yc+rc:.0f}] outside height [0, {height}]")

    if message:
        ftype = "circular" if circular else "fullframe"
        print(f"  Fisheye type: {ftype}, xc={xc}, yc={yc}, rc={rc}")

    Y, X = np.ogrid[:height, :width]
    mask_bool = (X - xc) ** 2 + (Y - yc) ** 2 <= rc ** 2

    final_img = img_values.astype(float).copy()
    if circular or (not circular and circ_mask):
        final_img[~mask_bool] = np.nan

    meta = {
        "xc": xc, "yc": yc, "rc": rc,
        "filename": os.path.basename(filename),
        "channel": str(channel), "gamma": gamma,
        "stretch": str(stretch).upper(),
    }

    if display:
        import matplotlib.pyplot as plt
        from matplotlib.patches import Circle
        fig, ax = plt.subplots(figsize=(7, 6))
        im = ax.imshow(final_img, cmap="gray", vmin=0, vmax=255, origin="lower")
        ax.set_title("circular mask")
        if circular:
            ax.add_patch(Circle((xc, yc), rc, fill=False, edgecolor="red", lw=2))
            ax.plot([xc, xc + rc], [yc, yc], "r-", lw=2)
        plt.colorbar(im, ax=ax, fraction=0.046, pad=0.04, label="Pixel Value")
        plt.tight_layout()
        plt.show()

    return final_img, meta


def binarize_fisheye(img_data, method="Otsu", zonal=False, manual=None,
                     display=False, export=False):

    if not isinstance(img_data, tuple) or len(img_data) != 2:
        raise ValueError("Input must be (img, meta) tuple from import_fisheye.")

    img, meta = img_data

    if img.ndim != 2:
        raise ValueError("Image must be single-band (2D array).")
    if zonal and manual is not None:
        raise ValueError("Cannot use zonal=True and manual threshold together.")
    if manual is not None:
        if not isinstance(manual, (int, float)):
            raise ValueError("Manual threshold must be numeric.")
        valid_vals = img[~np.isnan(img)]
        if valid_vals.size > 0:
            vmin, vmax = valid_vals.min(), valid_vals.max()
            if manual < vmin or manual > vmax:
                raise ValueError(f"Threshold {manual} outside range [{vmin:.1f}, {vmax:.1f}].")

    valid_mask = ~np.isnan(img)
    thresholds_used = []

    def get_otsu(chunk):
        valid = chunk[~np.isnan(chunk)]
        if valid.size == 0:
            return 0
        return _otsu_threshold(np.clip(np.round(valid), 0, 255).astype(np.uint8))

    if zonal and manual is None:
        xc = meta.get("xc", img.shape[1] / 2)
        yc = meta.get("yc", img.shape[0] / 2)
        masks = zonal_mask(img.shape, xc, yc)
        binary_img = np.full_like(img, np.nan, dtype=np.float32)

        for zone in ("N", "W", "S", "E"):
            cmask = masks[zone] & valid_mask
            chunk = img[cmask]
            if chunk.size > 0:
                th = get_otsu(chunk)
                thresholds_used.append(th)
                binary_img[cmask] = np.where(chunk > th, 1.0, 0.0)
            else:
                thresholds_used.append(0)

    elif manual is not None:
        th = float(manual)
        thresholds_used.append(th)
        binary_img = np.where(valid_mask, np.where(img > th, 1.0, 0.0), np.nan)

    else:
        th = get_otsu(img)
        thresholds_used.append(th)
        binary_img = np.where(valid_mask, np.where(img > th, 1.0, 0.0), np.nan)

    meta["zonal"] = str(zonal).upper()
    meta["method"] = method if manual is None else "manual"
    meta["thd"] = "_".join(str(int(t)) for t in thresholds_used)

    if display:
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(7, 6))
        if len(thresholds_used) > 1:
            title = "Binarized (zonal): " + ", ".join(str(int(t)) for t in thresholds_used)
        else:
            title = f"Binarized (threshold={int(thresholds_used[0])})"
        ax.set_title(title)
        im = ax.imshow(binary_img, cmap="gray", origin="lower", vmin=0, vmax=1)
        cbar = plt.colorbar(im, ax=ax, fraction=0.046, pad=0.04, ticks=[0, 1])
        cbar.ax.set_yticklabels(["Canopy (0)", "Gap (1)"])
        plt.tight_layout()
        plt.show()

    if export:
        results_dir = os.path.join(os.getcwd(), "results")
        os.makedirs(results_dir, exist_ok=True)
        fname = f"class_{meta.get('filename', 'fisheye')}"
        save_path = os.path.join(results_dir, fname)
        to_save = binary_img.copy()
        to_save[np.isnan(to_save)] = 0
        to_save = np.clip(to_save * 255, 0, 255).astype(np.uint8)
        Image.fromarray(to_save).save(save_path)
        print(f"  Exported to: {save_path}")

    binary_img = np.where(valid_mask, binary_img, np.nan).astype(np.float32)
    return binary_img, meta


def gapfrac_fisheye(img_data, maxVZA=90, lens="equidistant", startVZA=0,
                    endVZA=70, nrings=7, nseg=8, display=False, message=False):

    if isinstance(img_data, tuple):
        img_bw, meta = img_data
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
        unique_count = len(np.unique(img_bw[~np.isnan(img_bw)]))
        if unique_count == 2:
            rc = round(np.sqrt(xc**2 + yc**2)) - 2
        else:
            rc = round((np.max(x_coords) - np.min(x_coords)) / 2)
    else:
        xc, yc, rc = float(xc), float(yc), float(rc)

    if message:
        print(f"  xc={xc}, yc={yc}, rc={rc}")

    y_grid, x_grid = np.indices((height, width))
    dx = x_grid - xc
    dy = y_grid - yc
    r_px = np.sqrt(dx**2 + dy**2)
    theta_deg = np.degrees(np.arctan2(dx, dy))
    theta_deg = np.where(theta_deg < 0, theta_deg + 360, theta_deg)

    vza_step = (endVZA - startVZA) / nrings
    vza_bins = np.arange(startVZA, endVZA + 0.001, vza_step)
    vza_centres = [(vza_bins[i] + vza_bins[i + 1]) / 2 for i in range(nrings)]
    vza_string = "_".join(str(int(c)) for c in vza_centres)

    def vza_to_radius(vza):
        x = vza / maxVZA
        if lens == "equidistant":
            return rc * x
        elif lens == "FC-E8":
            return rc * (1.06 * x + 0.00498 * x**2 - 0.0639 * x**3)
        else:
            print(f"Warning: unknown lens '{lens}', using equidistant.")
            return rc * x

    r_bounds = [round(vza_to_radius(v)) for v in vza_bins]
    seg_step = 360.0 / nseg
    seg_bins = np.arange(0, 360 + 0.001, seg_step)

    results = []
    for i in range(nrings):
        mask_r = (r_px >= r_bounds[i]) & (r_px < r_bounds[i + 1])
        for j in range(nseg):
            mask_az = (theta_deg >= seg_bins[j]) & (theta_deg < seg_bins[j + 1])
            pixels = img_bw[mask_r & mask_az]
            valid = pixels[~np.isnan(pixels)]
            gf = np.mean(valid) if valid.size > 0 else np.nan
            results.append({"ring": vza_centres[i], "az_id": j + 1, "GF": gf})

    df = pd.DataFrame(results)
    df_wide = df.pivot(index="ring", columns="az_id", values="GF").reset_index()
    df_wide.columns = ["ring"] + [f"GF{c}" for c in range(1, nseg + 1)]

    df_wide["id"] = meta.get("filename", "image")
    df_wide["lens"] = lens
    df_wide["mask"] = f"{int(xc)}_{int(yc)}_{int(rc)}"
    df_wide["rings"] = nrings
    df_wide["azimuths"] = nseg
    df_wide["VZA"] = vza_string
    for k in ("channel", "stretch", "gamma", "zonal", "method", "thd"):
        if k in meta:
            df_wide[k] = meta[k]

    if display:
        import matplotlib.pyplot as plt
        from matplotlib.patches import Circle
        fig, ax = plt.subplots(figsize=(7, 6))
        ax.imshow(img_bw, cmap="gray", origin="lower", vmin=0, vmax=1)
        for rb in r_bounds:
            if rb > 0:
                ax.add_patch(Circle((xc, yc), rb, fill=False, edgecolor="yellow", lw=2))
        max_r = max(r_bounds)
        for seg_angle in seg_bins:
            rad = np.radians(seg_angle)
            ax.plot([xc, xc + max_r * np.sin(rad)],
                    [yc, yc + max_r * np.cos(rad)], "y-", lw=2)
        ax.set_title(f"Rings and Segments ({lens})")
        plt.tight_layout()
        plt.show()

    return df_wide


def canopy_fisheye(rdfw):
    unique_ids = rdfw["id"].unique()
    if len(unique_ids) == 0:
        raise ValueError("No image IDs found.")

    all_results = [_calculate_canopy_metrics(rdfw[rdfw["id"] == uid].copy())
                   for uid in unique_ids]

    cols_order = [
        "id", "Le", "L", "LX", "LXG1", "LXG2", "DIFN", "MTA.ell", "x",
        "VZA", "rings", "azimuths", "mask", "lens", "channel", "stretch",
        "gamma", "zonal", "method", "thd",
    ]
    result_df = pd.DataFrame(all_results)
    for col in cols_order:
        if col not in result_df.columns:
            result_df[col] = None
    return result_df[cols_order]


def _calculate_canopy_metrics(df):
    df = df.reset_index(drop=True)

    gf_cols = [c for c in df.columns if c.startswith("GF")]
    if not gf_cols:
        raise ValueError("No GF columns found.")

    for c in gf_cols:
        df[c] = df[c].replace(0, 0.00004530)

    df["GapFr"] = df[gf_cols].mean(axis=1)

    rads = np.radians(df["ring"].values)
    sinth = np.sin(rads)
    costh = np.cos(rads)
    w = sinth / np.sum(sinth)
    W = (sinth * costh) / (2.0 * np.sum(sinth * costh))

    Le = 2 * np.sum(-np.log(df["GapFr"].values) * w * costh)

    log_segs = -np.log(df[gf_cols].values)
    L = 2 * np.sum(log_segs.mean(axis=1) * w * costh)

    LX = Le / L if L != 0 else 0.0

    DIFN = np.sum(df["GapFr"].values * 2 * W) * 100

    def ellip_func(z_rad, x):
        num = np.sqrt(x**2 + np.tan(z_rad)**2)
        term = (((0.000509 * x - 0.013) * x + 0.1223) * x + 0.45) * x
        return num / (1.47 + term)

    Z = rads
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
        S2 = np.sum(KB**2)
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

    for idx in range(len(df)):
        vals_sorted = np.sort(df.iloc[idx][gf_cols].values)[::-1]
        sum_a23 = np.sum(vals_sorted * w23)
        sum_r23 = np.sum(vals_sorted * w23[::-1])
        sum_a34 = np.sum(vals_sorted * w34)
        sum_r34 = np.sum(vals_sorted * w34[::-1])

        lxg1 = (np.log(sum_a23) / np.log(sum_r23)
                if sum_a23 > 0 and sum_r23 > 0 and abs(sum_r23 - 1) > 1e-9
                else 1.0)
        lxg2 = (np.log(sum_a34) / np.log(sum_r34)
                if sum_a34 > 0 and sum_r34 > 0 and abs(sum_r34 - 1) > 1e-9
                else 1.0)

        lxg1_w_sum += lxg1 * w[idx]
        lxg2_w_sum += lxg2 * w[idx]

    row0 = df.iloc[0]

    def safe(key, default=None):
        return default if key not in row0.index or pd.isna(row0[key]) else row0[key]

    return {
        "id": safe("id", "unknown"),
        "Le": round(Le, 2), "L": round(L, 2), "LX": round(LX, 2),
        "LXG1": round(lxg1_w_sum, 2), "LXG2": round(lxg2_w_sum, 2),
        "DIFN": round(DIFN, 1), "MTA.ell": round(MTA_ell, 1),
        "x": round(x_param, 2),
        "VZA": safe("VZA"), "rings": safe("rings", len(df)),
        "azimuths": safe("azimuths", len(gf_cols)),
        "mask": safe("mask"), "lens": safe("lens"),
        "channel": safe("channel"), "stretch": safe("stretch"),
        "gamma": safe("gamma"), "zonal": safe("zonal"),
        "method": safe("method"), "thd": safe("thd"),
    }


if __name__ == "__main__":
    try:
        cfg = CONFIG

        temp_img = Image.open(cfg["filename"])
        img_w, img_h = temp_img.size
        temp_img.close()

        print(f"Image: {os.path.basename(cfg['filename'])} ({img_w} x {img_h} px)")

        if cfg["circ_mask"]:
            xc, yc, rc = cfg["circ_mask"]["xc"], cfg["circ_mask"]["yc"], cfg["circ_mask"]["rc"]
            print(f"Mask: xc={xc}, yc={yc}, rc={rc}")
            if (xc + rc > img_w) or (xc - rc < 0):
                print(f"  Warning: x-range [{xc-rc}, {xc+rc}] outside width [0, {img_w}]")
            if (yc + rc > img_h) or (yc - rc < 0):
                print(f"  Warning: y-range [{yc-rc}, {yc+rc}] outside height [0, {img_h}]")
        else:
            print("Mask: auto-detect")

        print("\nStep 1: Importing image")
        img_data = import_fisheye(
            filename=cfg["filename"], channel=cfg["channel"],
            circ_mask=cfg["circ_mask"], circular=cfg["circular"],
            gamma=cfg["gamma"], stretch=cfg["stretch"],
            display=cfg["display"], message=cfg["verbose"],
        )

        print("\nStep 2: Binarizing")
        bin_data = binarize_fisheye(
            img_data, method=cfg["method"], zonal=cfg["zonal"],
            manual=cfg["manual_threshold"], display=cfg["display"],
            export=cfg["export_binary"],
        )

        print("\nStep 3: Gap fraction")
        gap_df = gapfrac_fisheye(
            bin_data, maxVZA=cfg["maxVZA"], lens=cfg["lens"],
            startVZA=cfg["startVZA"], endVZA=cfg["endVZA"],
            nrings=cfg["nrings"], nseg=cfg["nseg"],
            display=cfg["display"], message=cfg["verbose"],
        )

        print("\nStep 4: Canopy attributes")
        canopy_stats = canopy_fisheye(gap_df)

        pd.set_option("display.max_columns", None)
        pd.set_option("display.width", 120)
        print("\nResults:")
        print(canopy_stats.to_string(index=False))

    except Exception as e:
        print(f"\nError: {e}")