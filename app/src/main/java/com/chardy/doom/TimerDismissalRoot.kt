package com.chardy.doom

/** Narrow, package-only classification used solely for visit-local timer dismissal. */
internal fun isOrdinaryForeignForTimerDismissal(
    rootPackage: String,
    doomPackage: String,
    imePackages: Set<String>,
): Boolean = rootPackage != "com.instagram.android" &&
    rootPackage != doomPackage &&
    rootPackage != "com.android.systemui" &&
    rootPackage !in imePackages
