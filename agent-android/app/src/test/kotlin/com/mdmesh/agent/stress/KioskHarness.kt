package com.mdmesh.agent.stress

import android.app.ActivityManager
import android.app.Application
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.agent.KioskLauncherActivity
import com.mdmesh.agent.R
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.core.store.DataStoreKioskStateStore
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Kiosk stress harness: the real KioskLauncherActivity (real Hilt graph, real DataStore, real PIN gate and crash
 * guard prefs) on Robolectric. Robolectric models lock task only as ActivityManager.lockTaskModeState (set by
 * start/stopLockTask); it does not enforce lock task, so escapes through the system UI are analysed separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-notnight-mdpi")
abstract class KioskHarness {

    protected val app: Application get() = ApplicationProvider.getApplicationContext()

    protected val pinned = "com.example.pos"
    protected val other = "com.example.scanner"
    protected val pin = "4821"

    protected val grid = KioskApplyPayload(
        mode = "launcher",
        allowedPackages = listOf(pinned, other),
        exitMode = "visible",
        password = pin,
    )
    protected val single = KioskApplyPayload(
        mode = "single",
        allowedPackages = listOf(pinned),
        pinPackage = pinned,
        exitMode = "visible",
        password = pin,
    )

    protected var ctl: ActivityController<KioskLauncherActivity>? = null
    protected val activity: KioskLauncherActivity get() = ctl!!.get()

    @Before fun seedDevice() {
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        shadowOf(dpm).setDeviceOwner(ComponentName(app, AdminReceiver::class.java))
        for (pkg in listOf(pinned, other)) install(pkg)
    }

    protected fun install(pkg: String, launchable: Boolean = true) {
        val pm = shadowOf(app.packageManager)
        val ai = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = pkg.substringAfterLast('.')
            flags = 0
        }
        pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
        pm.setApplicationIcon(pkg, BitmapDrawable(app.resources, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)))
        if (launchable) {
            val ri = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg; name = "$pkg.Main"; applicationInfo = ai; exported = true
                }
            }
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            pm.addResolveInfoForIntent(main, ri)
        }
    }

    protected fun uninstall(pkg: String) {
        shadowOf(app.packageManager).removePackage(pkg)
        shadowOf(app.packageManager).removeResolveInfosForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), pkg,
        )
    }

    /** Drains the main looper and the DataStore/IO threads (real threads) until quiet. */
    protected fun settle() {
        repeat(12) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    protected fun saveKiosk(k: KioskApplyPayload?) {
        runBlocking { DataStoreKioskStateStore(app).save(k) }
    }

    protected fun launch(k: KioskApplyPayload?, saved: Bundle? = null): KioskLauncherActivity {
        saveKiosk(k)
        val built = Robolectric.buildActivity(KioskLauncherActivity::class.java)
        ctl = if (saved == null) built.setup() else built.setup(saved)
        settle()
        return activity
    }

    /** HOME comes back to the front after something covered it (the pinned app closing, the screen turning on). */
    protected fun coverAndReturn(between: () -> Unit = {}) {
        ctl!!.pause().stop()
        settle()
        between()
        ctl!!.restart().resume()
        settle()
    }

    /** Activity recreated (rotation on polish, locale change / process restore with saved state on both). */
    protected fun recreate() {
        ctl!!.recreate()
        settle()
    }

    /** Saved-state-only restore: a brand-new activity built from what the old one saved (process death). */
    protected fun processDeathRestore(k: KioskApplyPayload? = null) {
        val out = Bundle()
        ctl!!.saveInstanceState(out)
        ctl!!.pause().stop().destroy()
        if (k != null) saveKiosk(k)
        ShadowDialog.reset()
        ctl = Robolectric.buildActivity(KioskLauncherActivity::class.java).setup(out)
        settle()
    }

    // --- Finders -------------------------------------------------------------------------------------------------

    protected fun showingDialog(): Dialog? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

    protected fun pinDialog(): Dialog? = showingDialog()?.takeIf { edit(it.window!!.decorView) != null }

    protected fun lockedDialog(): Dialog? =
        showingDialog()?.takeIf { findText(it.window!!.decorView, s(R.string.kiosk_exit_locked_title)) != null }

    /** "Exit isn't available right now" in its own dialog (not under the PIN field: that one has the field). */
    protected fun unavailableDialog(): Dialog? = showingDialog()?.takeIf { isUnavailable(it) }

    /** Whether [d] is the "Exit isn't available" dialog, shown or not. */
    protected fun isUnavailable(d: Dialog): Boolean {
        val root = d.window?.decorView ?: return false
        return edit(root) == null && findText(root, s(R.string.kiosk_exit_unavailable)) != null
    }

    /** The first view under [v] (depth first, [v] included) that [match]es. */
    private fun <T : View> first(v: View, type: Class<T>, match: (T) -> Boolean = { true }): T? = when {
        type.isInstance(v) && match(type.cast(v)!!) -> type.cast(v)
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { first(v.getChildAt(it), type, match) }
        else -> null
    }

    protected fun edit(v: View): EditText? = first(v, EditText::class.java)

    protected fun button(v: View, text: String): Button? =
        first(v, Button::class.java) { it.text.toString() == text && it.isShown }

    protected fun findText(v: View, text: String): TextView? =
        first(v, TextView::class.java) { it !is Button && it.isShown && it.text.toString() == text }

    protected fun byDescription(v: View, desc: String): View? =
        first(v, View::class.java) { it.contentDescription?.toString() == desc }

    protected fun s(id: Int): String = app.getString(id)

    protected val decor: View get() = activity.window.decorView

    protected fun exitButton(): Button? = button(decor, s(R.string.kiosk_exit_button))

    protected fun holdShowing(): Boolean = findText(decor, s(R.string.kiosk_hold_title)) != null

    protected fun tapExitKiosk() {
        exitButton()!!.performClick()
        settle()
    }

    /** Types [entered] into the open PIN dialog and taps Exit. */
    protected fun submitPin(entered: String) {
        val d = pinDialog() ?: error("no PIN dialog")
        edit(d.window!!.decorView)!!.setText(entered)
        button(d.window!!.decorView, s(R.string.kiosk_exit_confirm))!!.performClick()
        settle()
    }

    /**
     * The exit journal can't be written (a full disk): the launcher's applier is swapped for one whose journal refuses
     * every entry, so an exit is refused before its first step.
     */
    protected fun exitJournalFull() {
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(app, AdminReceiver::class.java)
        val homeAlias = ComponentName(app, "com.mdmesh.agent.KioskHomeAlias")
        val full = object : com.mdmesh.core.kiosk.ExitJournal {
            override fun pending(): com.mdmesh.core.kiosk.ExitJournal.Pending? = null
            override fun begin(p: com.mdmesh.core.kiosk.ExitJournal.Pending) = false
            override fun end() = true
        }
        activity.applier = com.mdmesh.core.kiosk.KioskApplier(
            com.mdmesh.kiosk.LockTaskKioskController(dpm, admin), activity.store,
            com.mdmesh.core.kiosk.AndroidKioskHomeSwitch(app, homeAlias), homeAlias,
            journal = full, io = kotlinx.coroutines.Dispatchers.IO,
        ) { true }
    }

    // --- State probes --------------------------------------------------------------------------------------------

    protected fun lockTaskState(): Int = app.getSystemService(ActivityManager::class.java).lockTaskModeState

    protected fun storedKiosk(): KioskApplyPayload? = runBlocking { DataStoreKioskStateStore(app).load() }

    /** Every activity start recorded since the last call (the pinned app, grid apps, the OEM home...). */
    protected fun drainStarts(): List<Intent> {
        val out = mutableListOf<Intent>()
        while (true) out += shadowOf(app).nextStartedActivity ?: break
        return out
    }

    protected fun pinnedStarts(): Int =
        drainStarts().count { it.`package` == pinned || it.component?.packageName == pinned }

    protected fun events(type: String): Int {
        val log = EventLog(app)
        val all = log.drain()
        log.restore(all)
        return all.count { it.type == type }
    }

    protected fun pinFailures(): Int =
        app.getSharedPreferences(ExitPinGate.PREFERENCE_NAME, 0).getInt("failures", 0)

    /** As if [ms] of wall-clock time passed since the crash guard's last fault (its window is wall-clock based). */
    protected fun ageCrashFaults(ms: Long) {
        val prefs = app.getSharedPreferences(CrashLoopGuard.FAULT_PREFERENCE_NAME, 0)
        val last = prefs.getLong("last_fault_time", -1L)
        if (last > 0) prefs.edit().putLong("last_fault_time", last - ms).commit()
    }

    /** Close the pinned app [times] times in a row (each return to HOME counts towards the crash guard). */
    protected fun bounce(times: Int) = repeat(times) { coverAndReturn() }
}
