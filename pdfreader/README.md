# PDF Reader (Android)

Lightweight offline PDF viewer built on the platform `PdfRenderer` (no third-party PDF engine),
Jetpack Compose and Material 3.

Features
- Open PDFs via the file picker, or directly from file managers, browsers, mail clients and the
  "Share" sheet (`application/pdf` and `*.pdf` intent filters).
- Page-by-page swiping (horizontal or vertical), pinch-to-zoom, drag-to-pan, double-tap zoom.
- Higher-resolution re-render when zoomed in.
- Page slider, previous/next buttons, "Go to page" dialog.
- Night mode (inverted colours), immersive full-screen when the toolbars are hidden (tap to toggle).
- Recent files list with remembered reading position.
- Russian and English UI.

Build
```
cd pdfreader
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```
The CI workflow `.github/workflows/pdfreader-build.yml` builds the APK on every push and commits it
to `dist/pdfreader-debug.apk`.

Requirements: Android 8.0 (API 26) or newer. Password-protected PDFs are not supported by the
platform renderer.
