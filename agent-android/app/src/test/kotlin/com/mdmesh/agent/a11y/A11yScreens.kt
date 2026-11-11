package com.mdmesh.agent.a11y

import android.app.Activity
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.mdmesh.agent.KioskLauncherActivity
import com.mdmesh.agent.MainActivity
import com.mdmesh.agent.R
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.agent.ui.KioskApp
import com.mdmesh.agent.ui.KioskRenderer
import com.mdmesh.agent.ui.KioskScreen
import com.mdmesh.agent.ui.StatusCopy
import com.mdmesh.agent.ui.StatusScreen
import com.mdmesh.agent.ui.StatusUiState
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.DataStoreKioskStateStore
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.store.SharedPrefsConfigStateStore
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/**
 * Every agent screen and dialog, put in a given state for the accessibility tests: the status screen (real
 * MainActivity, drawn from a [StatusUiState]), the kiosk launcher screens (a [KioskRenderer] on a themed activity)
 * and the exit dialogs (real KioskLauncherActivity).
 *
 * Every activity started here is destroyed, and every dialog dismissed, by [close]. The audit harness this grew
 * from didn't, and its idle-launcher test failed in some full runs ("synchronization barrier token has not been
 * posted", during a frame in the activity's first layout) but never alone: it depended on windows earlier tests
 * left open in the same JVM. With this teardown the full 12-configuration matrix ran clean.
 */
class A11yScreens {

    val app: Application get() = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<ActivityController<*>>()

    val apps = listOf(
        "com.example.inventory" to "Inventory",
        "com.example.scanner" to "Scanner",
        "com.example.timeclock" to "Time Clock",
        "com.example.orders" to "Warehouse Order Picking",
        "com.example.safety" to "Safety Checklists and Forms",
    )

    val kiosk = KioskApplyPayload(
        mode = "launcher",
        allowedPackages = apps.map { it.first },
        exitMode = "visible",
        password = "4821",
    )
    val kioskNoExit = kiosk.copy(exitMode = "remote", password = null)

    fun seed() {
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        shadowOf(dpm).setDeviceOwner(ComponentName(app, AdminReceiver::class.java))
        ServerConfigStore(app).save("https://mdm.example.com")
        runBlocking { DeviceIdStore(app).saveCredentials("dev_7f3a9c21e4b84d0e", "secret") }
        val pm = shadowOf(app.packageManager)
        for ((pkg, label) in apps) {
            val ai = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
            pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
            val ri = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = ai }
            }
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            pm.addResolveInfoForIntent(launch, ri)
        }
    }

    fun close() {
        ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { it.dismiss() }
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        controllers.clear()
        idle()
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T : Activity> start(c: ActivityController<T>): T {
        controllers += c
        c.setup()
        idle()
        return c.get()
    }

    // ---- status screen ----

    /** The status screen with [kiosk] configured, drawn from [state] (null: the screen's own state). */
    fun status(kiosk: KioskApplyPayload?, state: StatusUiState?): MainActivity {
        val store = SharedPrefsConfigStateStore(app)
        if (kiosk == null) store.clear() else store.save(ConfigApplyPayload(revision = "a".repeat(64), kiosk = kiosk))
        val a = start(Robolectric.buildActivity(MainActivity::class.java))
        if (state != null) {
            StatusScreen(a, {}, {}).render(state)
            idle()
        }
        return a
    }

    @Suppress("LongParameterList")
    fun statusState(
        deviceOwner: Boolean = true,
        locked: Boolean = false,
        configured: Boolean = false,
        deviceId: String? = "dev_7f3a9c21e4b84d0e",
        lastError: String? = null,
        returning: Boolean = false,
        returnFailure: String? = null,
        copied: StatusCopy.Target? = null,
    ): StatusUiState = StatusUiState.of(
        deviceOwner = deviceOwner,
        locked = locked,
        configured = configured,
        deviceId = deviceId,
        deviceIdLoaded = true,
        lastError = lastError,
        lastErrorAtMs = lastError?.let { ERROR_AT_MS },
        serverUrl = "https://mdm.example.com/",
        returning = returning,
        returnFailure = returnFailure,
    ).copy(copied = copied)

    fun returnDialog(kiosk: KioskApplyPayload): Dialog {
        // The screen's own state, button and dialog: Return to kiosk is offered for the configured kiosk.
        val a = status(kiosk, null)
        button(a.window.decorView, app.getString(R.string.kiosk_return_action))!!.performClick()
        idle()
        return showing()!!
    }

    // ---- kiosk launcher ----

    /** [screen] drawn by the launcher's renderer, on the app theme. */
    fun kioskScreen(screen: KioskScreen, backdrop: android.graphics.Bitmap? = null): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java)
        c.get().setTheme(R.style.Theme_MdmAgent)
        val a = start(c)
        val renderer = KioskRenderer(a, NoActions)
        renderer.render(screen)
        backdrop?.let { image ->
            // Through the launcher's own decoder: written as a PNG, decoded for this window and colour.
            val file = java.io.File(app.cacheDir, "busy.png")
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val metrics = a.resources.displayMetrics
            val decoded = com.mdmesh.agent.ui.BackdropDecoder.decode(
                file, metrics.widthPixels, metrics.heightPixels, screen.palette.background,
                com.mdmesh.agent.ui.DecodeLimits.NORMAL,
            ) as com.mdmesh.agent.ui.Decoded.Ready
            renderer.setBackdrop(decoded.bitmap, decoded.info)
        }
        idle()
        return a
    }

    /** A busy, high-contrast photo stand-in: black and white noise in small blocks, plus saturated stripes. */
    fun busyImage(): android.graphics.Bitmap {
        val w = 540
        val h = 1170
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        val palette = intArrayOf(Color.BLACK, Color.WHITE, Color.RED, Color.YELLOW, Color.BLUE, Color.GREEN)
        val blocks = IntArray((w / BLOCK + 1) * (h / BLOCK + 1)) { palette[random.nextInt(palette.size)] }
        val pixels = IntArray(w * h) { i -> blocks[(i / w / BLOCK) * (w / BLOCK + 1) + (i % w) / BLOCK] }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    fun kioskApps(): List<KioskApp> = apps.map { (pkg, label) -> KioskApp(pkg, label, ColorDrawable(Color.GRAY)) }

    fun launcher(k: KioskApplyPayload?): KioskLauncherActivity {
        runBlocking { DataStoreKioskStateStore(app).save(k) }
        return start(Robolectric.buildActivity(KioskLauncherActivity::class.java))
    }

    fun exitPinDialog(): Dialog {
        val a = launcher(kiosk)
        button(a.window.decorView, app.getString(R.string.kiosk_exit_button))!!.performClick()
        idle()
        return showing()!!
    }

    fun wrongPin(d: Dialog) {
        edit(d.window!!.decorView)!!.setText("0000")
        button(d.window!!.decorView, app.getString(R.string.kiosk_exit_confirm))!!.performClick()
        idle()
    }

    /** Wrong PINs until the launcher shows Exit locked. */
    fun exitLockedDialog(): Dialog {
        var d = exitPinDialog()
        repeat(MAX_TRIES) {
            if (edit(d.window!!.decorView) == null) return d
            wrongPin(d)
            d = showing()!!
        }
        return d
    }

    // ---- views ----

    fun showing(): Dialog? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

    fun <T : View> find(v: View, type: Class<T>, match: (T) -> Boolean = { true }): T? = when {
        type.isInstance(v) && match(type.cast(v)!!) -> type.cast(v)
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it), type, match) }
        else -> null
    }

    fun button(v: View, text: String): Button? =
        find(v, Button::class.java) { it.text.toString() == text && it.visibility == View.VISIBLE }

    fun edit(v: View): EditText? = find(v, EditText::class.java)

    /** [root] drawn into a screen-sized bitmap at 0,0, as ATF reads its bounds (a dialog's window included). */
    fun screenshot(root: View): Bitmap {
        val dm = app.resources.displayMetrics
        return Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            c.drawColor(Color.GRAY)
            root.draw(c)
        }
    }

    private object NoActions : KioskRenderer.Actions {
        override fun openApp(packageName: String) = Unit
        override fun openAgain() = Unit
        override fun requestExit() = Unit
    }

    private companion object {
        const val ERROR_AT_MS = 1_762_800_000_000L
        const val MAX_TRIES = 12
    }
}

private const val BLOCK = 6
