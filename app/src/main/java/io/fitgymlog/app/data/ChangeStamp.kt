package io.fitgymlog.app.data

/** Sync keeps the later change, so a change must sort after the one it replaces even when this phone's clock is behind. */
fun stampAfter(previous: Long?, now: Long): Long = if (previous == null) now else maxOf(now, previous + 1)
