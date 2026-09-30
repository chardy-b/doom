package com.chardy.doom

internal const val SAFE_SYSTEM_UI_PACKAGE = "__system_ui__"
internal const val SAFE_RECOGNIZED_IME_PACKAGE = "__recognized_ime__"

/** Narrow, package-only classification used solely for visit-local timer dismissal. */
internal fun isOrdinaryForeignForTimerDismissal(
    rootPackage: String,
    doomPackage: String,
    imePackages: Set<String>,
): Boolean = rootPackage != "com.instagram.android" &&
    rootPackage != doomPackage &&
    rootPackage != "com.android.systemui" &&
    rootPackage != SAFE_SYSTEM_UI_PACKAGE &&
    rootPackage != SAFE_RECOGNIZED_IME_PACKAGE &&
    rootPackage !in imePackages
