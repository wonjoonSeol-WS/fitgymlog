package io.fitgymlog.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** The languages the app ships strings in; names stored in one must still be recognised in the other. */
val SHIPPED_LANGUAGES: List<Locale> = listOf(Locale.ENGLISH, Locale.KOREAN)

fun Context.resourcesIn(locale: Locale): Resources =
    createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) }).resources
