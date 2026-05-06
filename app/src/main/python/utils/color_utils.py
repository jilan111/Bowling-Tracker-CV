"""HSV thresholding helpers.

The detection pipeline uses HSV (rather than RGB) because the H channel
is much more robust to ambient brightness than any RGB component would
be. A pin lit from above and a pin lit from a side window vary wildly in
RGB but track each other closely in H + S.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Tuple

import cv2
import numpy as np

# Ranges in OpenCV HSV space:
#   H: 0..179 (note: NOT 0..359; OpenCV halves it to fit in a uint8)
#   S: 0..255
#   V: 0..255

HsvBound = Tuple[int, int, int]


@dataclass(frozen=True)
class HsvRange:
    """Closed HSV range used to mask a single colour.

    Some colours (notably red) wrap around the H boundary. Callers can
    represent that with a *second* ``HsvRange`` and union the two masks
    via :func:`threshold_hsv`.

    Attributes:
        lower: Lower bound, inclusive. Order is (H, S, V).
        upper: Upper bound, inclusive. Order is (H, S, V).
    """

    lower: HsvBound
    upper: HsvBound

    def to_arrays(self) -> tuple[np.ndarray, np.ndarray]:
        """Returns the bounds as NumPy arrays ready for ``cv2.inRange``."""
        return (
            np.array(self.lower, dtype=np.uint8),
            np.array(self.upper, dtype=np.uint8),
        )


# Tuned for white / cream bowling pins under typical indoor lighting.
# Wide V range because pins reflect very differently depending on the
# overhead light. The narrow saturation cap rejects coloured plastic
# (e.g. a red car) without rejecting yellowing of older pins.
DEFAULT_PIN_HSV: tuple[HsvRange, ...] = (
    HsvRange(lower=(0, 0, 170), upper=(179, 60, 255)),
)

# Two-range default for "coral red" RC car. Red wraps the H boundary, so
# we accept either the low (~0) or the high (~170) end of the H ring.
DEFAULT_CAR_HSV: tuple[HsvRange, ...] = (
    HsvRange(lower=(0, 120, 80), upper=(10, 255, 255)),
    HsvRange(lower=(170, 120, 80), upper=(179, 255, 255)),
)


def threshold_hsv(frame_bgr: np.ndarray, ranges: tuple[HsvRange, ...]) -> np.ndarray:
    """Builds a binary mask for the union of one or more HSV ranges.

    Args:
        frame_bgr: BGR image as returned by ``cv2.imdecode`` or a Bitmap
            converted via :func:`cv2.cvtColor`. Must be a 3-channel
            ``uint8`` array.
        ranges: One or more ``HsvRange`` definitions. Their masks are
            OR-ed together so wrap-around hues stay simple.

    Returns:
        Single-channel ``uint8`` mask where 255 marks pixels inside the
        union of the supplied ranges and 0 marks everything else.

    Raises:
        ValueError: If ``ranges`` is empty.
    """
    if not ranges:
        raise ValueError("threshold_hsv requires at least one HsvRange")
    hsv = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2HSV)
    accumulator: np.ndarray | None = None
    for rng in ranges:
        lower, upper = rng.to_arrays()
        mask = cv2.inRange(hsv, lower, upper)
        accumulator = mask if accumulator is None else cv2.bitwise_or(accumulator, mask)
    assert accumulator is not None  # at least one range processed
    return accumulator
