# PureCam

<img src="logo.png" width="120" alt="PureCam logo">

An open-source camera for Android, built and tuned for the **Samsung Galaxy S21 FE**. It can shoot natural, unprocessed photos, or use processed looks, Portrait blur and 4K video (Standard, Natural or LOG).

## Modes

Swipe the viewfinder sideways or tap the mode names: **VIDEO · PHOTO · PORTRAIT · PRO**

### Photo and Pro: looks (✦ button)
| Look | What it does |
|---|---|
| **Natural** | Phone processing **off**: no noise reduction, sharpening, local tone-mapping or distortion correction. Plain sRGB curve, single frame. |
| Standard | Phone's full-quality noise reduction and sharpening |
| Vivid / Rich contrast / Warm / Cool / Film / Mono | Standard processing plus a colour grade, shown **live** in the viewfinder and applied to the saved JPEG |

Optional RAW (DNG) keeps the untouched sensor data.

### Portrait
Person-aware background blur (ML Kit selfie segmentation, runs on the phone). The aperture control (ƒ1.4 to ƒ16) sets the blur strength. Works on every lens, and the 3× telephoto gives the most flattering compression. The blur is computed after you take the photo.

### Video
- **4K or 1080p, 30 or 60 fps** (60 fps when the phone offers it to apps), H.264 or HEVC, 48 kHz stereo AAC audio.
- **Standard**: stabilised, clean, Rec.709. Upload straight to YouTube (4K30 ≈ 50 Mbps H.264).
- **Natural**: processing and stabilisation crop off.
- **PureLog**: a flat log curve through the camera's tone-map, which keeps highlight and shadow detail for grading. Apply `luts/PureLog_to_Rec709.cube` in DaVinci Resolve, Premiere or CapCut, then grade.
- **All lenses with smooth, animated zooms between them, even while recording.** Video uses the phone's multi-camera zoom system, so the phone hands over between the 0.5×, 1× and 3× modules.

## Zoom
- **iPhone-style zoom dial:** drag sideways on the lens buttons (or pinch the viewfinder). One smooth, logarithmic dial runs across all lenses (0.5× → 1× → 3× → 10×) and folds back into the buttons when you let go.
- Tapping a lens button animates the zoom to that lens.
- Zoom uses the sensor crop, which every Android camera supports. If a phone ignores it, PureCam detects that and zooms the viewfinder and saved photo itself.

## Lenses (including the 3× telephoto)
**Galaxy S21 FE:** Samsung only exposes the ultra-wide (ID 2), the main (ID 0) and the front cameras to apps. The telephoto isn't listed as its own camera. PureCam 2.1 therefore asks the main camera for 3× zoom. On phones where Samsung switches to the telephoto internally, the viewfinder shows **"TELEPHOTO LENS ✓"**; otherwise it shows **"Digital zoom · tap for real 3× lens"**. Tapping that opens Samsung's camera (the only app Samsung lets use the telephoto) for that shot, and the photo or video is saved straight into DCIM/PureCam. Settings › Camera diagnostics produces a full report of what your phone exposes.

PureCam probes every camera ID, including the ones Samsung hides from the public list. If the telephoto (or ultra-wide) can't be opened directly, PureCam reaches it through the phone's **multi-camera zoom system** (`CONTROL_ZOOM_RATIO` = 3.0), where the phone switches to the real telephoto module itself. Settings › "Lenses found" shows exactly what was detected and which route is used. In Pro mode, the info pill shows the sensor ID that is active right now.

## Get the APK
Push this folder to GitHub. GitHub Actions builds `PureCam.apk` and publishes it under **Releases › Latest build**.

## Licence
Apache-2.0
