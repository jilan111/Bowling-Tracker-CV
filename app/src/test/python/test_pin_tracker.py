"""Unit tests for the IoU-based pin tracker."""

from tracking.pin_tracker import PinTracker


def test_first_frame_assigns_fresh_ids():
    tracker = PinTracker(iou_threshold=0.5)
    detections = [(10, 10, 20, 60), (40, 10, 20, 60)]
    updated = tracker.update(frame_index=0, detections=detections)

    assert len(tracker.pins) == 2
    assert {pin.id for pin in updated} == {1, 2}
    assert all(pin.last_seen_frame == 0 for pin in updated)


def test_high_iou_match_inherits_existing_id():
    tracker = PinTracker(iou_threshold=0.5)
    tracker.update(frame_index=0, detections=[(10, 10, 20, 60)])
    pin_id_before = next(iter(tracker.pins))

    # Slight shift, mostly overlapping bbox in next frame.
    tracker.update(frame_index=1, detections=[(11, 12, 20, 60)])
    pin_id_after = next(iter(tracker.pins))
    assert pin_id_before == pin_id_after
    assert tracker.pins[pin_id_after].last_seen_frame == 1


def test_low_overlap_creates_new_identity():
    tracker = PinTracker(iou_threshold=0.5)
    tracker.update(frame_index=0, detections=[(10, 10, 20, 60)])
    # Disjoint bbox: IoU is zero.
    tracker.update(frame_index=1, detections=[(200, 200, 20, 60)])
    assert len(tracker.pins) == 2


def test_unmatched_pin_keeps_last_known_bbox():
    tracker = PinTracker(iou_threshold=0.5)
    tracker.update(frame_index=0, detections=[(10, 10, 20, 60)])
    pin_id = next(iter(tracker.pins))

    # Empty frame: pin is not seen but should still be present.
    tracker.update(frame_index=1, detections=[])
    assert pin_id in tracker.pins
    # last_seen_frame is NOT advanced on a no-match update; the
    # tracker preserves the previous value verbatim.
    assert tracker.pins[pin_id].last_seen_frame == 0


def test_reset_clears_state_and_id_counter():
    tracker = PinTracker(iou_threshold=0.5)
    tracker.update(frame_index=0, detections=[(10, 10, 20, 60)])
    tracker.reset()
    assert tracker.pins == {}

    tracker.update(frame_index=0, detections=[(0, 0, 1, 1)])
    new_id = next(iter(tracker.pins))
    assert new_id == 1, "ids should restart from 1 after reset()"
