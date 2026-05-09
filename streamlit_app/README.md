# Fisheye Canopy Analyzer

Streamlit app for OpenCV-free fisheye canopy analysis using Pillow, NumPy, pandas, and matplotlib.

## Run

```powershell
python -m pip install -r requirements.txt
python -m streamlit run app.py
```

On this machine, the default `python` command currently points to a missing Python install. I added `start_app.bat`, which runs the app with the working Codex Python runtime:

```powershell
.\start_app.bat
```

Then open:

```text
http://localhost:8501
```

Upload a fisheye image from the sidebar, configure the mask/import/threshold/gap-fraction parameters, then click **Run Analysis**.
