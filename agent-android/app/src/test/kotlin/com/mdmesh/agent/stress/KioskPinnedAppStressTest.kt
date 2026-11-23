package com.mdmesh.agent.stress

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import com.mdmesh.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/** The single-app kiosk's app: a TV-style app (Leanback entry only), and an update while the splash shows. */
class KioskPinnedAppStressTest : KioskHarness() {

    /** N5: a TV kiosk app with only a Leanback launcher entry opens, and isn't called "not installed". */
    @Test fun leanbackOnlyKioskAppOpens() {
        val tv = "com.example.signage"
        install(tv, launchable = false)
        val ai = ApplicationInfo().apply { packageName = tv }
        val ri = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = tv; name = "$tv.Tv"; applicationInfo = ai; exported = true
            }
        }
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(tv)
        shadowOf(app.packageManager).addResolveInfoForIntent(leanback, ri)
        launch(single.copy(pinPackage = tv, allowedPackages = listOf(tv)))
        assertEquals(1, drainStarts().count { it.`package` == tv || it.component?.packageName == tv })
        assertNull(findText(decor, app.getString(R.string.kiosk_app_missing, tv)))
    }

    /** N6: the pinned app is updated while the splash shows: once it is back, it opens again, uncounted. */
    @Test fun pinnedAppUpdatedDuringSplashOpensAgain() {
        launch(single)
        drainStarts()
        uninstall(pinned)
        app.sendBroadcast(packageIntent(Intent.ACTION_PACKAGE_REMOVED, replacing = true))
        settle()
        install(pinned)
        app.sendBroadcast(packageIntent(Intent.ACTION_PACKAGE_ADDED, replacing = true))
        settle()
        assertEquals("the updated app opens", 1, pinnedStarts())
        assertFalse(holdShowing())
    }

    private fun packageIntent(action: String, replacing: Boolean) =
        Intent(action, Uri.fromParts("package", pinned, null)).putExtra(Intent.EXTRA_REPLACING, replacing)
}
