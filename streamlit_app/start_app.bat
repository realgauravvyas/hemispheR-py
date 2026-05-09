@echo off
cd /d "%~dp0"
"C:\Users\Gaurav\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" -m streamlit run app.py --server.port 8501 --server.headless true --browser.gatherUsageStats false
