# Generator and validator

Both scripts require Python 3 only. `build_templates.py` writes source template directories, regenerated PNG/TIL sheets, fixed-metadata BDS ZIP files, and previews. `validate_templates.py` checks ZIP safety and limits, BOM-aware metadata, PNG decoding headers, TIL counts/rectangles, required files, layouts, and in-canvas key touch rectangles.

```sh
python3 build_templates.py --clean
python3 validate_templates.py
```
