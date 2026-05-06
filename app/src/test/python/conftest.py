"""Pytest fixtures shared across the BowlTrack pipeline tests.

The Python tier under ``app/src/main/python`` is a regular Python
package, so we configure pytest to load it from the file tree directly
rather than asking Chaquopy to be involved. Fixtures here are tiny
helpers for building synthetic detector outputs deterministically.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Make ``app/src/main/python`` importable as the top-level package
# during tests. This mirrors how Chaquopy makes those modules visible
# at runtime on Android.
ROOT = Path(__file__).resolve().parents[2] / "main" / "python"
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
