package dev.bold7.design

import android.view.animation.PathInterpolator

// The only Android file of the module: views animate through a PathInterpolator, Compose takes
// the same points through CubicBezierEasing.

fun CubicBezier.toInterpolator() = PathInterpolator(x1, y1, x2, y2)
