package com.bumplog

import android.content.Context

/** The phone's display numbers, for feedback reports. Numbers only: no model, ID, or location. */
object DisplayInfo {
    fun bucket(dpi: Int): String = when {
        dpi <= 120 -> "ldpi"
        dpi <= 160 -> "mdpi"
        dpi <= 240 -> "hdpi"
        dpi <= 320 -> "xhdpi"
        dpi <= 480 -> "xxhdpi"
        else -> "xxxhdpi"
    }

    fun sizeClass(widthDp: Int): String = when {
        widthDp < 600 -> "compact"
        widthDp < 840 -> "medium"
        else -> "expanded"
    }

    fun describe(fontScale: Float, densityDpi: Int, widthPx: Int, heightPx: Int): String {
        val d = densityDpi.coerceAtLeast(1) / 160f
        val wDp = Math.round(widthPx / d); val hDp = Math.round(heightPx / d)
        return "font scale %.2f, %d dpi (%s), screen %dx%d dp (%dx%d px), %s width".format(
            java.util.Locale.US, fontScale, densityDpi, bucket(densityDpi), wDp, hDp, widthPx, heightPx, sizeClass(wDp))
    }

    fun current(ctx: Context): String {
        val dm = ctx.resources.displayMetrics
        return describe(ctx.resources.configuration.fontScale, dm.densityDpi, dm.widthPixels, dm.heightPixels)
    }
}
