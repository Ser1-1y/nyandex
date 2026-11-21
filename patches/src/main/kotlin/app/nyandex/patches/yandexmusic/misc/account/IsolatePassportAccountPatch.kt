/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.patches.yandexmusic.misc.account

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.nyandex.patches.yandexmusic.shared.Constants.COMPATIBILITY_YANDEX_MUSIC
import app.nyandex.patches.yandexmusic.util.replaceExactStringConstants
import java.util.logging.Logger

private val logger = Logger.getLogger("YandexMusicPassportAccount")

private const val STOCK_ACCOUNT_TYPE = "com.yandex.passport"
private const val ISOLATED_ACCOUNT_TYPE = "com.yandex.passport.morphe"

/**
 * Yandex apps share the `com.yandex.passport` Android account type through their common
 * signing certificate. Android registers only one authenticator per account type, and on
 * a typical device that is Yandex Go (`ru.beru.android`). Once Morphe re-signs Yandex
 * Music it belongs to a different signature group, so the system refuses
 * `AccountManager.addAccountExplicitly` with
 * `cannot explicitly add accounts of type: com.yandex.passport` and login fails.
 *
 * Point the app at its own account type so its own `AuthenticationService` becomes the
 * only authenticator for that type. The authenticator XML and the sync adapter read the
 * type from `@string/passport_account_type`, so the resource and the exact string
 * constants in code must be renamed together. Longer `com.yandex.passport.*` names
 * (actions, permissions, provider authorities) are intentionally left untouched.
 */
val isolatePassportAccountPatch = patch(
    name = "Отдельный аккаунт Passport",
    description = "Даёт переподписанному приложению собственный тип аккаунта Passport, чтобы Android " +
        "разрешил создать в нём аккаунт Яндекса, а оригинальные приложения Яндекса сохранили общий.",
) {
    compatibleWith(COMPATIBILITY_YANDEX_MUSIC)

    execute {
        val stringsFile = resourceFile("res/values/strings.xml")
        val oldEntry = "<string name=\"passport_account_type\">$STOCK_ACCOUNT_TYPE</string>"
        val newEntry = "<string name=\"passport_account_type\">$ISOLATED_ACCOUNT_TYPE</string>"
        val text = stringsFile.readText()

        if (!text.contains(oldEntry)) {
            throw PatchException("passport_account_type was not found in res/values/strings.xml")
        }
        stringsFile.writeText(text.replace(oldEntry, newEntry))

        val changed = replaceExactStringConstants(STOCK_ACCOUNT_TYPE, ISOLATED_ACCOUNT_TYPE)
        if (changed == 0) {
            throw PatchException("No \"$STOCK_ACCOUNT_TYPE\" string constants were found")
        }

        logger.info(
            "Renamed the Passport account type to $ISOLATED_ACCOUNT_TYPE " +
                "($changed string constants and passport_account_type)",
        )
    }
}
