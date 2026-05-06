"""Unit tests for the pin-fall detector's debounced state transitions."""

from analysis.fall_detector import FallDetector
from models.pin_state import PinState, PinStatus


def _standing_pin(pin_id: int = 1) -> PinState:
    return PinState(
        id=pin_id,
        current_bbox=(10, 10, 20, 60),     # tall and narrow
        initial_bbox=(10, 10, 20, 60),
    )


def test_aspect_inversion_requires_consecutive_frames():
    detector = FallDetector(required_consecutive=3)
    pin = _standing_pin()
    pins = {pin.id: pin}

    # Two frames where the aspect inverts but the debounce hasn't met.
    pin.current_bbox = (10, 50, 60, 20)
    detector.update(frame_index=0, timestamp_seconds=0.0, pins=pins)
    detector.update(frame_index=1, timestamp_seconds=0.1, pins=pins)
    assert pin.status == PinStatus.STANDING

    # Third consecutive frame → the transition is confirmed.
    detector.update(frame_index=2, timestamp_seconds=0.2, pins=pins)
    assert pin.status == PinStatus.FALLEN
    assert detector.fallen_count == 1
    event = detector.events[0]
    assert event.order == 1 and event.frame_index == 2 and event.timestamp_seconds == 0.2


def test_top_edge_drop_triggers_fall():
    detector = FallDetector(required_consecutive=2, top_drop_fraction=0.4)
    pin = _standing_pin()
    pins = {pin.id: pin}

    # Same shape, but top edge slid down by 50% of the initial height.
    pin.current_bbox = (10, 10 + 30, 20, 60)
    detector.update(frame_index=0, timestamp_seconds=0.0, pins=pins)
    detector.update(frame_index=1, timestamp_seconds=0.1, pins=pins)
    assert pin.status == PinStatus.FALLEN


def test_consecutive_counter_resets_on_recovery():
    detector = FallDetector(required_consecutive=3)
    pin = _standing_pin()
    pins = {pin.id: pin}

    # Inverted aspect for two frames...
    pin.current_bbox = (10, 50, 60, 20)
    detector.update(frame_index=0, timestamp_seconds=0.0, pins=pins)
    detector.update(frame_index=1, timestamp_seconds=0.1, pins=pins)

    # ...then the pin pops back upright. Counter resets.
    pin.current_bbox = (10, 10, 20, 60)
    detector.update(frame_index=2, timestamp_seconds=0.2, pins=pins)
    assert pin.consecutive_fallen_frames == 0
    assert pin.status == PinStatus.STANDING


def test_each_pin_gets_unique_fall_order():
    detector = FallDetector(required_consecutive=1)
    a = _standing_pin(pin_id=1)
    b = _standing_pin(pin_id=2)
    pins = {a.id: a, b.id: b}

    a.current_bbox = (10, 50, 60, 20)
    detector.update(frame_index=0, timestamp_seconds=0.5, pins=pins)
    b.current_bbox = (40, 50, 60, 20)
    detector.update(frame_index=1, timestamp_seconds=0.7, pins=pins)

    assert [e.order for e in detector.events] == [1, 2]
    assert [e.pin_id for e in detector.events] == [1, 2]
