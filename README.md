# LogCam

Manual Camera2 video recorder with direct HEVC Main10 encoding and preview-only .cube LUTs.

The custom modes load Apple Log-shaped or O-Log-shaped tonemap curves into the camera.
They are experimental: matching a mathematical curve does not establish a calibrated Apple/OPPO
gamut, sensor dynamic range, or absence of vendor image processing.

## MagicOS 11 capture path

On Honor Magic 8 Pro BKQ-N49, firmware11.0.0.120C901E8, a standalone probe verified coloured
1080p30 Main10 with a256-point custom curve and STANDARD dynamic range. At258 points the same
pipeline produces grayscale. LogCam caps custom curves at256 and uses STANDARD; HLG is requested
only when the user selects HLG. Main10 files do not alone prove10-bit sensor precision.

The encoder receives camera frames directly. Preview LUTs sample the preview as supplied, without
silently decoding Apple Log or rotating assumed primaries. Choose a LUT appropriate to the selected
curve; exact camera matching still requires calibration.

The output preserves the encoder's actual transfer semantics, including HDR for explicit HLG
capture. Container colour metadata is reconciled against the encoded HEVC VUI instead of blindly
relabeling every recording SDR. Standard EDGE/NR OFF requests are available; metadata readback
cannot establish that all vendor sharpening or noise reduction is disabled.

## Build

Use Android Studio's JBR and Android SDK37:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Device results and remaining calibration limits are recorded in `docs/magicos11-validation.md`.
