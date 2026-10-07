# Fisheye Canopy Analyzer (Streamlit app)

Interactive web UI for hemispheR-py: upload a hemispherical photo, adjust the circular mask,
tune channel / threshold / lens settings, and read off LAI, clumping and canopy openness.

## Run

```bash
python -m pip install -r requirements.txt
python -m streamlit run app.py
```

On Windows you can also double-click `start_app.bat`. Then open <http://localhost:8501>.

Upload a fisheye image from the sidebar, configure the mask / import / threshold / gap-fraction
parameters, then click **Run Analysis**. Results appear in four tabs: Import, Binarize,
Gap Fraction and Canopy Attributes.
