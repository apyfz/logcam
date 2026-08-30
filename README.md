# LogCam

A manual video camera for Android that records real log footage — Apple Log, O-Log or HLG —
with the ISP's sharpening and noise reduction switched off, so the file is something you grade
rather than something already graded for you.

Built for the Honor Magic 8 Pro (`BKQ-N49`). Everything is capability-gated, so it degrades on
devices that expose less rather than lying about what it recorded.

- 10-bit HEVC Main10, 4K30, up to 100 Mbps, AAC audio
- Custom tonemap curves loaded into the ISP (Apple Log 1, O-Log), plus HLG and a linear reference
- Full manual control: ISO, shutter, focus, white balance in Kelvin, zoom
- Monitoring LUT in the preview only; the recording stays untouched log
- Files are written SDR-tagged, so nothing downstream tone-maps them as HDR

Colour maths is cross-checked against `colour-science` rather than derived once and trusted.
