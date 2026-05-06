# BowlTrack — Sample video guidance

The detection pipeline is pre-trained on COCO and tuned for a fairly specific recording
setup. You will get noticeably better results if your input video respects the suggestions
below.

## Camera placement

- **Top-down or shallow downward angle** is best. Steep side angles let foreshortening
  shrink the standing pin's apparent height — that pushes the aspect ratio below the
  detector's 1.4 threshold and the pin fails to register.
- **Camera fixed.** Put the phone on a tripod, a stack of books, anything that does not
  move during the run. Camera shake confuses the IoU pin tracker (every pin "moves" every
  frame) and the Lucas–Kanade car tracker.
- **Frame the whole alley + a margin.** Pins near the edge of the frame can leave the
  scene during the fall and trip the disappearance heuristic.

## Lighting

- **Even, neutral indoor light.** A single ceiling light or two diffuse desk lamps.
- **Avoid mixed colour temperatures** (e.g. warm bulbs + a cool monitor in the
  background). White-balance drift between frames will widen the HSV mask required for
  the pins, which lets noisy white pixels into the contour pass.
- **Avoid strong shadows that move.** A pin with a sliding shadow looks like a falling pin
  to the top-edge-drop rule.

## Pins

- **High contrast vs background.** White or cream pins on a dark surface is the optimal
  case. Pins matching the floor colour will require careful HSV tuning before they show
  up reliably.
- **Pin spacing of at least one pin width.** Heavily clustered pins (typical 10-pin
  triangle) work, but the IoU tracker will sometimes merge two adjacent pins into one
  track. Use Settings → Detection → "Detection sensitivity" to dial up the contour
  filter's strictness if false positives flood the tracker.
- **Avoid translucent or reflective pin materials.** Glass / mirrored finishes reflect the
  car colour into the pin contour and the colour mask gets confused.

## RC car

- **Bright accent colour different from the pins.** Coral red is the bundled default; any
  saturated colour works. If you cannot recolour the car, just pick the most saturated
  region (e.g. a tape strip) and calibrate the Car Colour range to match.
- **Calibrate via Settings.** Settings → Detection → Car colour calibration → drag the
  H/S/V sliders until only the car is tinted mint in the live preview. Save and rerun.
- **YOLO can take over** if you do not want to calibrate the colour at all — the pipeline
  will use COCO's `car`/`truck` proxies for the centroid. This works on cars resembling a
  toy sedan; less so on tracked vehicles or non-car shapes.

## Resolution and framerate

- **1080p 30 fps** is plenty. The pipeline downscales to 720 px wide and decimates to
  ~15 fps internally; sending a 4K 60 fps video just makes Python work harder for the
  same output.
- **Keep clips short.** 4–8 seconds is plenty to capture a full run. Longer clips work
  but make the analysis pass linearly longer.

## Codec

- Default mp4 (H.264) is the safest bet. The bundled `MediaCodec` decoder handles HEVC on
  most modern devices but is more likely to hit vendor quirks on older hardware.

## Quick checklist

Before recording:

- [ ] Phone is fixed in place.
- [ ] Whole alley + ~10 % margin fits in the frame.
- [ ] Pins have visible top-to-bottom range (i.e. not seen edge-on).
- [ ] Lighting is even and not flickering.
- [ ] Car colour stands out from pins and floor.
- [ ] Calibrated pin + car colours in Settings if your setup differs from the defaults.

After analysis, if the score looks wrong:

1. Open Settings → Pin colour calibration. Does only the pin tint mint in the preview? If
   not, narrow the H or raise the S/V floor.
2. Same check for the car colour.
3. If pins are missed because they are knocked into pile-ups, the merged-bbox tracker may
   be folding two pins into one identity. There is no clean fix without a pin-aware
   trained model — note it as a limitation.
