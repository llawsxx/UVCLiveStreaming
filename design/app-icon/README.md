# USB Live Studio icon

The camera, play symbol and broadcast arcs represent video capture, recording
and streaming. Navy, cyan and blue keep the silhouette legible at launcher size.

- `usb-live-studio.svg`: editable 512-pixel SVG export.
- `preview.png`: raster preview.
- `generate_assets.py`: shared geometry for SVG, adaptive Android vectors,
  monochrome themed icons and legacy square/round icons at five densities.

Regenerate with `python design/app-icon/generate_assets.py` (requires Pillow).
The Android foreground stays inside the adaptive icon safe area.
