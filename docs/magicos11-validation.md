# MagicOS 11 validation — 2026-10-21

Device: Honor Magic 8 Pro BKQ-N49, Android17, firmware11.0.0.120C901E8.
Research artifacts: `../honor-camera-research/2026-10-21/` (outside this repository).

## Capture and metadata

A standalone timestamp-matched YUV sweep retains colour at128/256/257 curve points;
258/259/260/512 give exactly neutral chroma. Direct STANDARD Main10 probe captures confirm
256 retains colour and258 becomes grayscale. A HAL overrun remains unproven.

The updated LogCam app recorded:

- `LogCam_20261021-180634_APPLE_LOG.mp4`: 3840×2160,30fps, HEVC Main10/yuv420p10le,
  AAC,538 frames. Flat-looking colour survives the actual preview + encoder session.
- `LogCam_20261021-180712_O_LOG.mp4`: same path,293 frames; successfully saved.

For the Apple-shaped clip, MP4 nclx and HEVC VUI now agree: primaries5,transfer6,matrix5,
full-range. These are encoder labels, not measured camera gamut or the custom log curve.
The parser reconciles the container to the encoded stream; it neither grades nor rewrites samples.
A fixture from the original Honor output proves that reconciliation changes only two container
bytes. Explicit HLG metadata remains HLG rather than being falsely relabelled SDR.

Custom curves are capped at256 at both capability discovery and profile generation. They use
STANDARD capture; only the explicit HLG mode requests HLG10. Auto WB no longer forces a stale
harvested matrix into the tested custom-curve path. Manual WB remains a separate uncalibrated path.

## Processing controls

At ISO400,1/100 shutter, manual focus≈0.31m and NR OFF, two Apple-shaped recordings requested
EDGE OFF versus HIGH_QUALITY. Ten-frame averages, reduced to960×540, have correlation0.999175
and mean absolute luma difference0.786/255. This broad comparison does not establish a meaningful
sharpening improvement; it also cannot exclude local differences or hidden vendor processing.
AWB remained automatic. No calibrated slanted-edge target or measured illuminant was available.

Evidence: `logcam-validation/apple-edge-off-nr-off.mp4`, `apple-edge-on-nr-off.mp4`,
`edge-comparison.json` in the research directory. Earlier recordings made while the phone was
being interacted with are excluded from this comparison.

## Monitoring and lifecycle

LUTs now sample the preview directly. The old forced Apple Log1→Apple Log2 gamut conversion
was inappropriate for other profiles and relied on an unmeasured BT.2020 acquisition gamut.
A cube must be selected for its intended input; direct sampling does not make this camera calibrated.

The user initially requested FILMFLOW Apple Log, then selected Stalman Film03 (Apple Log2).
A fresh145-frame4K Main10/AAC clip (`LogCam_20261021-181245_APPLE_LOG.mp4`) was
recorded with this LUT active; the stored clip remains ungraded. Keep that selected LUT. LUTs are not bundled into the repository or baked into recording.
The document picker uses the actual DISPLAY_NAME and persists its read grant and URI.

Opening the picker exposed MagicOS camera error3 from keeping the camera open in the background.
The activity now releases idle preview onStop and reopens onStart. Active recordings are finalized
when backgrounded. The picker is disabled during recording/start/stop transitions. Returning from
selection and from cancelling the picker was verified without the previous camera error.

Playback of a reverse-landscape clip revealed the existing recording orientation used sensor+
display rotation. Changed it to sensor−display rotation (mod360) for the back camera. Preview
orientation code is unchanged. The subsequent comparison recording
`LogCam_20261021-182505_APPLE_LOG.mp4` plays upright after decoding with its rotation metadata.
LUT selection restored successfully after reinstall/restart.

## Checks and limits

Build,33 JVM tests and Android lint pass (lint has warnings, no errors). Coverage includes curve
caps, STANDARD/HLG selection, real SPS parsing, HLG preservation, malformed input, idempotence,
and avoiding fake MP4 boxes inside compressed media.

No calibrated chart/gray card is available (confirmed by user). Exact Apple/OPPO gamut matching,
transfer response, highlight headroom, effective sensor bit depth, and removal of all sharpening/NR
remain unverified. This change fixes the tested grayscale route and monitoring/metadata bugs;
it does not promise a calibrated cinema-camera pipeline.

## Stock Pro LOG comparison

Stationary sequential captures are in the research `stock-comparison/` directory, with an HTML
viewer, original files, detail crops, and ungraded/Stalman/FILMFLOW comparisons. Stock capture
results report ISO200 and approximately1/160s despite UI ISO100; LogCam requested ISO200 and1/160s.
Stock used hidden camera8, WB5000K and stabilization ON; LogCam used camera0, AWB and requested
stabilization OFF. The stock result is smoother and darker, LogCam brighter and noisier in this
scene. These differences and the distinct input curves prevent a calibrated quality ranking.
Apple Log LUTs are applied directly for appearance comparison, not a verified Honor Log conversion.
The hidden stock camera ID is a future investigation lead; camera0 vendor-tag probes do not
establish equivalence to the stock Pro LOG pipeline.
