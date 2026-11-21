/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.patches.yandexmusic.misc.signature

import app.morphe.patcher.patch.patch
import app.morphe.patches.all.misc.fix.spoofsignature.spoofSignaturePatch
import app.nyandex.patches.yandexmusic.shared.Constants.COMPATIBILITY_YANDEX_MUSIC

/**
 * Morphe re-signs the patched APK with its own key. Yandex Passport and the relay service compare
 * the app's signature with Yandex's and refuse the re-signed app ("application signature
 * mismatch"). The universal signature spoof from the Morphe patches library makes the app's own
 * `PackageManager` lookups return the original certificate. It is applied by default here, as
 * login does not work without it.
 */
val spoofAppSignaturePatch = patch(
    name = "Spoof app signature",
    description = "Makes the app see its original Yandex signature, so Yandex ID login and other " +
        "signature checks keep working after Morphe re-signs the APK. Required for login.",
) {
    compatibleWith(COMPATIBILITY_YANDEX_MUSIC)

    dependsOn(spoofSignaturePatch)
}
