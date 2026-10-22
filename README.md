# PureCam

<img src="logo.png" width="120" alt="PureCam logo">

An open-source camera for Android that takes **natural, unprocessed photos**, with no AI "enhancement", over-sharpening or smeared noise reduction. It was built and tuned for the **Samsung Galaxy S21 FE** and works on any Android 10+ phone.

## What "Natural mode" turns off (Camera2 API)

| Processing | Setting |
|---|---|
| Noise reduction | `NOISE_REDUCTION_MODE_OFF` (or the lowest mode the lens allows) |
| Sharpening / edge enhancement | `EDGE_MODE_OFF` |
| Chromatic-aberration correction | `COLOR_CORRECTION_ABERRATION_MODE_OFF` |
| Punchy tone curve | Replaced with a plain sRGB curve (`TONEMAP_PRESET_CURVE_SRGB`) |
| Ultra-wide distortion correction | `DISTORTION_CORRECTION_MODE_OFF` (toggle in settings) |
| Scene optimiser, multi-frame merging, AI | Not used. PureCam takes a single frame. |
| Video stabilisation crop | Off (optical stabilisation stays on because it's mechanical) |

JPEGs are saved at quality 100. Optional **RAW (DNG)** saves the untouched sensor data next to each JPEG.

The basic sensor corrections (black level, lens-shading, white balance, demosaic) still run in the phone's hardware image processor, as they do in every camera app. RAW is the only way to skip them.

## Features

- **All lenses**: probes every camera ID, including the ones Samsung hides from the public list, to find ultra-wide (0.5×), main (1×), telephoto (3×) and front. The lens buttons are labelled from the real focal lengths. Settings › "Lenses found" shows exactly what was detected.
- Pinch to zoom within a lens, tap to focus and meter (the ring turns amber when locked).
- **Pro mode**: ISO, shutter speed (1/8000 s to 30 s), manual focus, white-balance presets, exposure compensation, and a live ISO/shutter readout.
- Flash off/auto/on/torch, 3 s and 10 s timer, grid, volume-key shutter, shutter sound toggle.
- Thumbnail opens the last photo in Samsung Gallery. Photos are saved to `DCIM/PureCam`.
- S21 FE tuning: 120 Hz viewfinder, full-sensor 4:3 frame, layout around the punch-hole, controls in thumb reach, and icons that rotate when you turn the phone.

## Get the APK

Push this folder to a GitHub repo. GitHub Actions builds `PureCam.apk` and publishes it under **Releases › Latest build**.

```bash
gh repo create PureCam --public --source=. --push
```

## Licence

Apache-2.0
