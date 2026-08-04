# hemispheR-py

**A dependency-light Python reimplementation of hemispherical (fisheye) canopy photo
analysis** — the workflow ecologists use to pull leaf area index and canopy structure
out of an upward-facing fisheye photo, without needing OpenCV or R.

Point it at a hemispherical photo of a forest canopy and it gives back:

- **Effective LAI (Le)** and **true LAI (L)**, via the Miller/Welles inversion
- **Clumping index (Ω)** — how non-randomly leaves are distributed (LX = Le / L)
- **Gap fraction** per zenith ring, computed with Otsu thresholding (global or
  4-way zonal, for scenes with uneven lighting)
- **DIFN** (diffuse non-interceptance / canopy openness)

## Why it exists

The reference implementations for this kind of analysis are R packages that lean on
a fairly heavy geospatial R/OpenCV stack. This is a from-scratch Python port using
only `numpy`, `pandas`, and `Pillow` — no OpenCV, no R — so the same LAI/clumping
pipeline runs anywhere Python does, including lightweight or mobile-adjacent
environments.

## Layout

```
core/                 the actual algorithm — import → binarize → gap fraction → canopy metrics
  hemispherR-py.py     single-file pipeline, CONFIG dict at the top
  CONFIG_README.txt     parameter-by-parameter reference (mask, channel, lens model, thresholding...)
streamlit_app/         interactive web UI — upload a photo, tune parameters, run analysis
android_app/           Android scaffold for on-device capture + analysis
sample_images/         example hemispherical photos to test against
```

## Quick start

**Script:**
```bash
pip install numpy pandas pillow
# edit the CONFIG dict at the top of core/hemispherR-py.py — at minimum, filename
python core/hemispherR-py.py
```

**Streamlit app:**
```bash
cd streamlit_app
pip install -r requirements.txt
streamlit run app.py
```
Upload a fisheye image from the sidebar, tune the circular mask / channel / threshold
/ view-angle parameters, and run the analysis interactively.

## How it works, briefly

1. **Import** — mask out everything outside the circular fisheye field of view
   (auto-detected or given as `xc, yc, rc`), pick a channel (blue separates sky from
   canopy best for upward shots), apply gamma correction.
2. **Binarize** — Otsu-threshold sky vs. canopy, either globally or per compass
   quadrant (`zonal=True`) to handle uneven illumination.
3. **Gap fraction** — reproject pixels by view zenith angle (equidistant or
   FC-E8 fisheye-converter lens models) into rings and angular segments, and compute
   the fraction of sky visible in each.
4. **Canopy metrics** — combine the per-ring gap fractions into effective/true LAI,
   the clumping index, and DIFN, following the standard hemispherical-photography
   inversion used in forest ecology.

## License

See [LICENSE](LICENSE).
