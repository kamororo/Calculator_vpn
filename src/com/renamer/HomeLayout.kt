package com.renamer

import android.content.Context
import kotlin.math.pow
import kotlin.math.sqrt

/** Shared home-grid layout: how many tiles per row
 *  (2–5, default 3 — the approved look) and how big
 *  tiles render (slider 70–140%, default 100%).
 *  One source of truth for Home AND the Sort page,
 *  so the sort preview is exactly what Home shows.
 *  The math is defensive on purpose: whatever the
 *  user picks, icons can never overflow their cell
 *  and labels can never grow the row without bound.
 *  Unsaved changes live only in the Sort page's
 *  local state; Save writes them here, Back drops
 *  them — the same model as the tile order. */
object HomeLayout {
    private const val PREFS = "renamer_settings"
    private const val KEY_COLS = "home_cols"
    private const val KEY_SCALE = "home_tile_scale"

    fun cols(ctx: Context): Int = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COLS, 3).coerceIn(2, 5)
    } catch (_: Exception) { 3 }

    fun scale(ctx: Context): Float = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SCALE, 1.0f).coerceIn(0.7f, 1.4f)
    } catch (_: Exception) { 1.0f }

    fun save(ctx: Context, cols: Int, scale: Float) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_COLS, cols.coerceIn(2, 5))
                .putFloat(KEY_SCALE, scale.coerceIn(0.7f, 1.4f))
                .apply()
        } catch (_: Exception) {}
    }

    /** Icon size in dp for a cell. Wider grids get
     *  smaller icons (square-root falloff, so 2 cols
     *  is not twice as big as 4), the slider scales
     *  on top, and the result is always clamped to
     *  fit inside its own cell with room to spare. */
    fun iconDp(cols: Int, scale: Float, screenWidthDp: Float): Int {
        val c = cols.coerceIn(2, 5)
        val s = scale.coerceIn(0.7f, 1.4f)
        val cellW = (screenWidthDp - 32f) / c
        val cap = cellW - 20f
        val base = 64f * sqrt(3f / c) * s
        return base.coerceIn(36f, 96f).coerceAtMost(cap.coerceAtLeast(36f)).toInt()
    }

    /** Label size in sp. Gentler falloff than icons
     *  (fourth root), so 5 columns stays readable,
     *  clamped so no choice makes text huge or tiny. */
    fun textSp(cols: Int, scale: Float): Float {
        val c = cols.coerceIn(2, 5)
        val s = scale.coerceIn(0.7f, 1.4f)
        return (13f * (3f / c).toDouble().pow(0.25).toFloat() * s).coerceIn(10f, 17f)
    }

    fun screenWidthDp(ctx: Context): Float = try {
        val m = ctx.resources.displayMetrics
        m.widthPixels / m.density
    } catch (_: Exception) { 360f }
}
