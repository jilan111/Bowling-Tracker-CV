"""BowlTrack Python package.

This package holds all the computer vision logic that runs on the phone.
The Android (Kotlin) side talks to it through Chaquopy.

Sub-packages:
    detection - finds pins and the car in a single frame
    tracking  - keeps track of pin IDs and the car path across frames
    analysis  - higher-level logic (e.g. decide when a pin has fallen)
    models    - small data classes shared by everyone
    utils     - small helpers (geometry, color ranges)
"""
