# Chaquopy ships its own runtime; do not strip its classes or the Python
# interpreter loses its bridge.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Keep Compose-tooling classes used only in debug builds.
-dontwarn androidx.compose.ui.tooling.**
