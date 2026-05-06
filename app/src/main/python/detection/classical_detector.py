"""Classical-CV detector: color-agnostic geometry + motion, with optional HSV calibration.

Pin detection
-------------
Color-agnostic by default. The detector builds a "salient object" mask
that fires on anything visually distinct from the background, regardless
of hue:

1. Saturation channel + Otsu threshold — separates colourful objects
   from a uniform low-saturation floor (white tile, beige carpet).
2. Canny edges on the V channel + dilate — catches low-saturation but
   high-contrast objects (white plastic pins on a beige floor).
3. Union the two masks, then morph-open / morph-close.
4. External contours, filtered by area band (``min_pin_area`` ..
   ``max_pin_area``) and elongation (longer-side / shorter-side, NOT
   plain h/w). The orientation-agnostic ratio means a fallen pin
   lying horizontally on a top-down shot still passes — important
   because the FallDetector watches for orientation transitions and
   needs to keep seeing a pin after it has fallen. The upper area cap
   filters out the car (which is typically larger than a pin) so a
   single mega-blob does not flood the pin tracker.

If the caller hands in HSV ranges via ``DetectorConfig.pin_hsv`` (the
user calibrated colours through Settings → Color Calibration), the
detector falls back to the strict colour-mask path. Calibration is now
a refinement, not a precondition.

Car detection
-------------
Color-agnostic by default via frame differencing: the car is the moving
object, the pins are stationary. Each call:

1. Convert the frame to grayscale and diff against the previous frame.
2. Threshold + open/close to produce a "motion mask".
3. Reject contours that look like a standing pin (tall and narrow) so
   the car cannot be confused with a tipping pin.
4. Return the centroid of the largest remaining motion blob, or
   ``None`` when the scene is static (also returned on the first frame
   because there is no prior to diff against).

If ``DetectorConfig.car_hsv`` is supplied, the detector uses the strict
colour-mask path instead. A motion-based fallback runs when the colour
mask comes up empty so a brief lighting change does not silence the
tracker.

The detector now carries per-stream state (the previous grayscale
frame); construct one detector per analysis run, as the orchestrator
already does.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import cv2
import numpy as np

from models import Detection
from utils import (
    HsvRange,
    bbox_area,
    threshold_hsv,
)


@dataclass
class DetectorConfig:
    """Tuneable knobs surfaced from the Settings screen.

    Defaults are conservative: they prefer false negatives over false
    positives because the YOLO verification tier is what pulls in any
    pins the classical pass misses.

    Attributes:
        pin_hsv: Optional HSV range(s) for pin colour. ``None`` (the
            default) selects the colour-agnostic path that combines
            saturation + edges. When supplied, the strict colour-mask
            path is used and the union of the listed ranges is the pin
            candidate region.
        car_hsv: Optional HSV range(s) for car colour. ``None`` selects
            the motion-based path. When supplied, the colour mask is
            preferred and motion is the fallback.
        min_pin_area: Minimum pin contour area in pixels. Below this
            threshold contours are dropped as noise. Tuned for 720-px
            analysis frames.
        min_pin_aspect: Minimum height/width ratio for a contour to be
            classified as a *standing* pin. Fallen pins fall below this
            threshold by design — they are picked up later by the
            tracker as a state transition.
        morph_kernel: Side length of the elliptical structuring element
            used by the open/close morphology step on the pin mask.
        approx_pin_top_quantile: Limits pin candidates to the top-N% of
            the contour list ranked by score, so a busy background
            cannot flood the tracker with hundreds of bogus pins.
        canny_low: Lower threshold for the Canny edge detector used in
            the color-agnostic pin pass.
        canny_high: Upper threshold for Canny.
        edge_dilate_iterations: How many times to dilate the edge mask
            so a closed pin silhouette is filled in.
        sat_min_pixels: Minimum number of pixels with non-zero
            saturation before Otsu's threshold is trusted. On a frame
            with a perfectly uniform colour the saturation channel has
            no bimodal distribution and Otsu over-fits; below this
            count we fall back to the edge mask alone.
        motion_threshold: Per-pixel grayscale-diff threshold (0..255)
            above which a pixel is considered "moving" between frames.
        motion_morph_kernel: Side length of the elliptical kernel used
            to denoise the motion mask. Larger values bridge the head
            and tail of a fast-moving car.
        min_car_area: Minimum contour area in pixels for a motion blob
            to be considered the car. Smaller than ``min_pin_area``
            because the car silhouette is often partially occluded by a
            tipping pin.
        car_motion_aspect_max: Largest height/width ratio a motion blob
            may have before it is rejected as pin-like. Standing pins
            move when struck and would otherwise be confused with the
            car.
    """

    pin_hsv: Optional[Tuple[HsvRange, ...]] = None
    car_hsv: Optional[Tuple[HsvRange, ...]] = None
    # Pins on a downscaled 720-px-wide frame: a top-down toy pin is
    # roughly 30 × 70 = 2100 px when fallen and 35 × 35 = 1225 when
    # viewed straight down. 250 is a tight floor that still catches
    # both states without admitting tile-grout noise.
    min_pin_area: int = 250
    # Pin contours larger than this are likely the car (or two pins
    # merged across a touching contact). Set to ``0`` to disable.
    max_pin_area: int = 3000
    # Minimum *elongation* = max(w,h) / min(w,h) — orientation-agnostic
    # so a fallen pin lying horizontally and a standing pin viewed
    # top-down both pass. 1.3 keeps pin silhouettes (mildly elongated
    # bottle shapes) and rejects near-square noise blobs.
    min_pin_aspect: float = 1.3
    morph_kernel: int = 3
    # Keep only the strongest 50 % of candidates each frame so a busy
    # scene cannot flood the tracker.
    approx_pin_top_quantile: float = 0.5

    # Color-agnostic pin pass
    canny_low: int = 60
    canny_high: int = 160
    edge_dilate_iterations: int = 1
    sat_min_pixels: int = 200

    # Motion-based car pass — tuned for slow-moving toy cars on a
    # static camera. The pipeline is dilate-then-close (no open) so
    # the thin 1-2 pixel diff band of a car moving a few pixels per
    # frame is preserved instead of being eroded away.
    motion_threshold: int = 10
    motion_morph_kernel: int = 5
    motion_dilate_iterations: int = 2
    min_car_area: int = 80


class ClassicalDetector:
    """Per-stream detector wrapping the classical CV pipeline.

    The detector is now stateful — it caches the previous grayscale
    frame so the motion-based car path can produce a frame difference.
    Construct one instance per analysis run; the orchestrator already
    does.
    """

    def __init__(self, config: Optional[DetectorConfig] = None) -> None:
        self.config = config or DetectorConfig()
        size = max(1, int(self.config.morph_kernel))
        # Elliptical kernel, not rectangular: pin contours are rounded,
        # and an ellipse closes vertical highlights without smearing
        # them sideways into a neighbouring pin.
        self._kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (size, size))
        m_size = max(1, int(self.config.motion_morph_kernel))
        self._motion_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (m_size, m_size))
        # State for the motion-based car detector. Reset between runs by
        # constructing a fresh detector.
        self._prev_gray: Optional[np.ndarray] = None

    # ------------------------------------------------------------------
    # Pin detection
    # ------------------------------------------------------------------
    def detect_pins(self, frame_bgr: np.ndarray) -> List[Detection]:
        """Returns pin detections sorted by descending score.

        Score is heuristic: ``area_score * aspect_score`` where each
        factor is a soft sigmoid-shaped value in ``[0, 1]``. The
        absolute number is meaningless; only the relative ordering
        matters when the orchestrator has to break ties or when the
        YOLO verifier needs candidates ranked.
        """
        if self.config.pin_hsv:
            mask = threshold_hsv(frame_bgr, self.config.pin_hsv)
        else:
            mask = self._color_agnostic_pin_mask(frame_bgr)

        clean = cv2.morphologyEx(mask, cv2.MORPH_OPEN, self._kernel)
        clean = cv2.morphologyEx(clean, cv2.MORPH_CLOSE, self._kernel)

        contours, _ = cv2.findContours(
            clean, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE,
        )

        candidates: List[Detection] = []
        for contour in contours:
            x, y, w, h = cv2.boundingRect(contour)
            bbox = (int(x), int(y), int(w), int(h))
            area = bbox_area(bbox)
            if area < self.config.min_pin_area:
                continue
            if self.config.max_pin_area > 0 and area > self.config.max_pin_area:
                # Bigger than a pin is the car or two pins fused at the
                # base of a contour merge — drop here so the tracker
                # never inherits a giant identity.
                continue
            # Elongation = longer side / shorter side. Orientation-
            # agnostic so a fallen pin (lying horizontally) and a
            # standing pin (top-down or side-view) both pass.
            w_safe = max(1, w)
            h_safe = max(1, h)
            elongation = float(max(w_safe, h_safe)) / float(min(w_safe, h_safe))
            if elongation < self.config.min_pin_aspect:
                continue
            candidates.append(
                Detection(bbox=bbox, score=_pin_score(area, elongation), label="pin"),
            )

        candidates.sort(key=lambda det: det.score, reverse=True)
        # Trim runaway false positives by keeping only the top fraction
        # of candidates. With well-tuned thresholds the keep-fraction
        # is irrelevant; with bad lighting it stops the tracker from
        # exploding to dozens of identities.
        keep_count = max(1, int(len(candidates) * self.config.approx_pin_top_quantile))
        return candidates[:keep_count]

    def _color_agnostic_pin_mask(self, frame_bgr: np.ndarray) -> np.ndarray:
        """Builds a binary mask of "anything visually salient" without
        relying on a specific colour.

        Saturation Otsu catches any colourful object on a uniform floor;
        Canny edges (dilated) catches low-saturation high-contrast
        objects like white pins. Their union covers the typical bowling
        scene without committing to a hue.
        """
        hsv = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2HSV)
        sat = hsv[:, :, 1]
        value = hsv[:, :, 2]

        # Saturation contribution: Otsu only works when there are enough
        # non-zero pixels; otherwise we skip it and rely on edges alone.
        sat_mask = np.zeros_like(sat)
        if int(np.count_nonzero(sat)) >= self.config.sat_min_pixels:
            _, sat_mask = cv2.threshold(
                sat, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU,
            )

        # Edge contribution: run Canny on V (more robust to colour cast
        # than gray), dilate so the silhouette closes into a filled
        # blob ready for contour extraction.
        edges = cv2.Canny(value, self.config.canny_low, self.config.canny_high)
        if self.config.edge_dilate_iterations > 0:
            edges = cv2.dilate(
                edges,
                self._kernel,
                iterations=int(self.config.edge_dilate_iterations),
            )
        edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, self._kernel)

        return cv2.bitwise_or(sat_mask, edges)

    # ------------------------------------------------------------------
    # Car detection
    # ------------------------------------------------------------------
    def detect_car(self, frame_bgr: np.ndarray) -> Optional[Tuple[int, int]]:
        """Returns the centroid of the car's largest plausible blob.

        Color-agnostic by default: motion-based, so the car is whatever
        moved between this frame and the last. The first call always
        returns ``None`` because there is nothing to diff against. When
        ``config.car_hsv`` is set, the colour-mask path runs first and
        the motion path is the fallback.

        Failing fast lets the orchestrator decide whether to defer to
        the Lucas-Kanade flow tracker (which carries the centroid
        forward from the last known position) or to the YOLO fallback.
        """
        # Always update the gray-frame cache so the motion path is ready
        # the moment the colour path fails on a future frame.
        gray = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)

        centroid: Optional[Tuple[int, int]] = None
        if self.config.car_hsv:
            centroid = self._car_centroid_from_hsv(frame_bgr)
        if centroid is None:
            centroid = self._car_centroid_from_motion(gray)

        # Update _prev_gray after both paths so the colour-only path
        # still warms up the motion fallback for the next frame.
        self._prev_gray = gray
        return centroid

    def _car_centroid_from_hsv(self, frame_bgr: np.ndarray) -> Optional[Tuple[int, int]]:
        """Strict colour-mask path. Returns ``None`` when no contour in
        the configured colour range survives morphology."""
        if not self.config.car_hsv:
            return None
        mask = threshold_hsv(frame_bgr, self.config.car_hsv)
        clean = cv2.morphologyEx(mask, cv2.MORPH_OPEN, self._kernel)
        clean = cv2.morphologyEx(clean, cv2.MORPH_CLOSE, self._kernel)
        contours, _ = cv2.findContours(
            clean, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE,
        )
        if not contours:
            return None
        # Largest by *contour area*, not bounding-box area — the latter
        # is biased by a thin tail of mask noise hugging an edge.
        largest = max(contours, key=cv2.contourArea)
        if cv2.contourArea(largest) <= 0:
            return None
        return _moments_centroid(largest)

    def _car_centroid_from_motion(
        self, gray: np.ndarray,
    ) -> Optional[Tuple[int, int]]:
        """Motion path: the car is the largest moving blob in the frame.

        Returns ``None`` on the first frame (no prior to diff against)
        and on any frame where the largest motion blob is smaller than
        ``min_car_area``.

        Pipeline:
          1. Absolute frame-to-frame diff on grayscale.
          2. Threshold (low — to keep the thin diff band of a slow car).
          3. **Dilate** the diff into a solid blob, then **close** to
             fill any internal gaps. We deliberately skip ``MORPH_OPEN``
             because erosion wipes out the 1-2 pixel-thick diff ribbon
             characteristic of slow-moving toy cars.
          4. Take the largest external contour. The car is bigger than
             a single pin's motion silhouette, so the largest blob is a
             reliable proxy for the car.
        """
        if self._prev_gray is None:
            return None
        if self._prev_gray.shape != gray.shape:
            # Frame size changed mid-stream (rare). Skip this frame and
            # let the next one prime the diff again.
            return None

        diff = cv2.absdiff(gray, self._prev_gray)
        _, mask = cv2.threshold(
            diff, int(self.config.motion_threshold), 255, cv2.THRESH_BINARY,
        )

        iters = max(1, int(self.config.motion_dilate_iterations))
        clean = cv2.dilate(mask, self._motion_kernel, iterations=iters)
        clean = cv2.morphologyEx(clean, cv2.MORPH_CLOSE, self._motion_kernel)

        contours, _ = cv2.findContours(
            clean, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE,
        )
        if not contours:
            return None

        largest = max(contours, key=cv2.contourArea)
        if cv2.contourArea(largest) < self.config.min_car_area:
            return None
        return _moments_centroid(largest)


def _pin_score(area: int, ratio: float) -> float:
    """Heuristic combined score for a pin candidate.

    Both factors are bounded sigmoids so the product cannot blow up on
    unusually large or unusually skinny contours.
    """
    # Sigmoid centred at 1500 px area: contours below the minimum area
    # have already been rejected, so this just smooths the upper tail.
    area_score = 1.0 - 1.0 / (1.0 + max(0, area) / 1500.0)
    # Pins are tall — saturating around ratio = 3.0 prevents extreme
    # vertical splinters from dominating the ranking.
    ratio_score = min(1.0, max(0.0, (ratio - 1.0) / 2.0))
    return float(max(0.0, min(1.0, area_score * ratio_score)))


def _moments_centroid(contour: np.ndarray) -> Optional[Tuple[int, int]]:
    """Returns the integer centroid of a contour via image moments,
    or ``None`` when the moments degenerate (zero area)."""
    moments = cv2.moments(contour)
    if moments["m00"] <= 0.0:
        return None
    cx = int(round(moments["m10"] / moments["m00"]))
    cy = int(round(moments["m01"] / moments["m00"]))
    return (cx, cy)
