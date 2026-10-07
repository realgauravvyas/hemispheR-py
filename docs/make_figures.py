"""Regenerate the figures in docs/images from the sample photos.

Every number and picture here comes from running the real pipeline in
core/hemispherR-py.py on the photos in sample_images/ - nothing is mocked up.

    python docs/make_figures.py

Needs numpy, pandas, Pillow and matplotlib.
"""
import importlib.util
import json
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.colors import ListedColormap
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(HERE, "images")
os.makedirs(OUT, exist_ok=True)

spec = importlib.util.spec_from_file_location("hemispherR_py", os.path.join(ROOT, "core", "hemispherR-py.py"))
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)

# circle (xc, yc, rc) in pixels: where the fisheye image sits inside each frame
SAMPLES = [
    {"name": "P072", "file": "P072.jpg", "mask": {"xc": 1496, "yc": 2408, "rc": 1414}},
    {"name": "P042b", "file": "P042b.jpg", "mask": {"xc": 1728, "yc": 1496, "rc": 1414}},
]
SETTINGS = {"channel": 3, "gamma": 0.85, "zonal": True, "lens": "FC-E8",
            "maxVZA": 90, "startVZA": 0, "endVZA": 70, "nrings": 7, "nseg": 8}

# palette shared with the Featherfield site
INK, PANEL = "#070b09", "#0f1713"
TXT, DIM, FAINT = "#d4e2d9", "#8aa093", "#4d5f55"
LEAF, SUN, SKY = "#7fdc9a", "#ffd27a", "#8ed4ff"


def run(sample):
    path = os.path.join(ROOT, "sample_images", sample["file"])
    img, meta = core.import_fisheye(
        path, channel=SETTINGS["channel"], circ_mask=sample["mask"], circular=True,
        gamma=SETTINGS["gamma"], stretch=False, display=False, message=False)
    binary, meta = core.binarize_fisheye((img, meta), method="Otsu", zonal=SETTINGS["zonal"],
                                         manual=None, display=False, export=False)
    gap = core.gapfrac_fisheye(
        (binary, meta), maxVZA=SETTINGS["maxVZA"], lens=SETTINGS["lens"],
        startVZA=SETTINGS["startVZA"], endVZA=SETTINGS["endVZA"],
        nrings=SETTINGS["nrings"], nseg=SETTINGS["nseg"], display=False, message=False)
    res = core.canopy_fisheye(gap).iloc[0]
    return path, img, binary, meta, gap, res


def ring_radii(rc):
    """Ring boundaries in pixels - the same lens model as gapfrac_fisheye."""
    bins = np.arange(SETTINGS["startVZA"], SETTINGS["endVZA"] + 0.001,
                     (SETTINGS["endVZA"] - SETTINGS["startVZA"]) / SETTINGS["nrings"])
    x = bins / SETTINGS["maxVZA"]
    r = rc * (1.06 * x + 0.00498 * x ** 2 - 0.0639 * x ** 3)
    return [round(v) for v in r]


def crop_box(mask, shape):
    h, w = shape[:2]
    x0, x1 = max(0, mask["xc"] - mask["rc"]), min(w, mask["xc"] + mask["rc"])
    y0, y1 = max(0, mask["yc"] - mask["rc"]), min(h, mask["yc"] + mask["rc"])
    return x0, y0, x1, y1


def shrink(a, side=560):
    """Downsample a 2-D/3-D array so figures stay small (nearest for the binary image)."""
    h, w = a.shape[:2]
    k = max(1, int(round(max(h, w) / side)))
    return a[::k, ::k]


def style_axes(ax, title, sub=None):
    ax.set_xticks([]); ax.set_yticks([])
    for s in ax.spines.values():
        s.set_visible(False)
    ax.set_title(title, color=TXT, fontsize=13.5, fontweight="bold", loc="left", pad=22)
    if sub:
        ax.text(0, 1.015, sub, transform=ax.transAxes, color=DIM, fontsize=9.6, va="bottom")


def pipeline_figure(sample):
    path, img, binary, meta, gap, res = run(sample)
    mask = sample["mask"]
    x0, y0, x1, y1 = crop_box(mask, img.shape)
    rgb = np.array(Image.open(path).convert("RGB"))[y0:y1, x0:x1]
    chan = img[y0:y1, x0:x1]
    bw = binary[y0:y1, x0:x1]
    cx, cy = mask["xc"] - x0, mask["yc"] - y0

    rgb_s, chan_s, bw_s = shrink(rgb), shrink(chan), shrink(bw)
    k = rgb.shape[0] / rgb_s.shape[0]
    thd = meta["thd"].replace("_", " / ")

    fig = plt.figure(figsize=(19.5, 6.3), dpi=110, facecolor=INK)
    gs = fig.add_gridspec(1, 5, width_ratios=[1, 1, 1, 1, 1.22], left=0.012, right=0.992,
                          bottom=0.17, top=0.78, wspace=0.12)

    # 1 import
    ax = fig.add_subplot(gs[0]); ax.set_facecolor("black")
    ax.imshow(rgb_s)
    ax.add_patch(plt.Circle((cx / k, cy / k), mask["rc"] / k, fill=False, ec=SUN, lw=1.6, ls=(0, (5, 4))))
    style_axes(ax, "1  Photo + mask", f"circle xc={mask['xc']}, yc={mask['yc']}, rc={mask['rc']} px")

    # 2 import (channel)
    ax = fig.add_subplot(gs[1]); ax.set_facecolor("black")
    ax.imshow(np.ma.masked_invalid(chan_s), cmap="gray", vmin=0, vmax=255)
    style_axes(ax, "2  Blue channel", f"gamma {meta['gamma']}, scaled 0-255")

    # 3 binarize
    ax = fig.add_subplot(gs[2]); ax.set_facecolor("black")
    cmap = ListedColormap(["#0d2a1a", "#cfe8ff"]); cmap.set_bad("black")
    ax.imshow(np.ma.masked_invalid(bw_s), cmap=cmap, vmin=0, vmax=1, interpolation="nearest")
    gap_pct = 100.0 * np.nanmean(bw)
    style_axes(ax, "3  Sky vs canopy", f"Otsu per quadrant ({thd}), {gap_pct:.0f}% sky")

    # 4 rings and sectors
    ax = fig.add_subplot(gs[3]); ax.set_facecolor("black")
    ax.imshow(np.ma.masked_invalid(bw_s), cmap=cmap, vmin=0, vmax=1, interpolation="nearest")
    for rb in ring_radii(mask["rc"])[1:]:
        ax.add_patch(plt.Circle((cx / k, cy / k), rb / k, fill=False, ec=SUN, lw=1.1, alpha=0.9))
    rmax = ring_radii(mask["rc"])[-1] / k
    for j in range(SETTINGS["nseg"]):
        a = j * 2 * np.pi / SETTINGS["nseg"]
        ax.plot([cx / k, cx / k + rmax * np.sin(a)], [cy / k, cy / k + rmax * np.cos(a)], color=SUN, lw=0.8, alpha=0.7)
    style_axes(ax, "4  Gap fraction", f"{SETTINGS['nrings']} rings x {SETTINGS['nseg']} sectors, 0-{SETTINGS['endVZA']} deg")

    # 5 chart + results
    ax = fig.add_subplot(gs[4]); ax.set_facecolor(PANEL)
    cols = [c for c in gap.columns if c.startswith("GF")]
    rings = gap["ring"].values
    gf = gap[cols].mean(axis=1).values
    ax.bar(rings, gf, width=7.2, color=SKY, alpha=0.9, label="measured")
    th = np.linspace(1, 69, 200)
    ax.plot(th, np.exp(-0.5 * float(res["Le"]) / np.cos(np.radians(th))), color=SUN, lw=2.0,
            label=f"random canopy with the same Le ({res['Le']:.2f})")
    ax.set_xlim(0, 70); ax.set_ylim(0, max(gf.max(), 0.3) * 1.5)
    ax.set_xlabel("view zenith angle (deg)", color=DIM, fontsize=9.5)
    ax.set_ylabel("gap fraction", color=DIM, fontsize=9.5)
    ax.tick_params(colors=DIM, labelsize=9)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(FAINT)
    ax.grid(axis="y", color=FAINT, alpha=0.35, lw=0.6)
    ax.legend(loc="upper right", frameon=False, labelcolor=TXT, fontsize=9)
    ax.set_title("5  Canopy attributes", color=TXT, fontsize=13.5, fontweight="bold", loc="left", pad=22)
    ax.text(0, 1.015, "rings -> LAI, clumping, openness", transform=ax.transAxes, color=DIM, fontsize=9.6, va="bottom")

    stats = (f"Le {res['Le']:.2f}  L {res['L']:.2f}  LX {res['LX']:.2f}  "
             f"DIFN {res['DIFN']:.0f}%")
    ax.text(0.0, -0.20, stats, transform=ax.transAxes, color=SUN, fontsize=10.5, fontweight="bold",
            family="monospace", va="top")

    fig.text(0.012, 0.955, "hemispheR-py", color=LEAF, fontsize=17, fontweight="bold", family="monospace", va="center")
    fig.text(0.115, 0.955, f"one fisheye photo  ->  leaf area index and canopy structure     "
             f"({sample['file']} from sample_images/, blue channel, gamma {SETTINGS['gamma']}, FC-E8 lens)",
             color=DIM, fontsize=11, va="center")
    path_out = os.path.join(OUT, f"pipeline-{sample['name']}.png")
    fig.savefig(path_out, facecolor=INK)
    plt.close(fig)
    print("wrote", os.path.relpath(path_out, ROOT), os.path.getsize(path_out) // 1024, "KB")

    ring_rows = [{"vza": float(r), "gap_fraction": round(float(g), 4)} for r, g in zip(rings, gf)]
    return {"file": sample["file"], "mask": mask, "settings": SETTINGS, "thresholds": meta["thd"],
            "results": {k: (None if k not in res else (float(res[k]) if not isinstance(res[k], str) else res[k]))
                        for k in ("Le", "L", "LX", "LXG1", "LXG2", "DIFN", "MTA.ell", "x")},
            "rings": ring_rows}


if __name__ == "__main__":
    summary = [pipeline_figure(s) for s in SAMPLES]
    with open(os.path.join(OUT, "results.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)
    print(json.dumps([{"file": s["file"], **s["results"]} for s in summary], indent=1))
