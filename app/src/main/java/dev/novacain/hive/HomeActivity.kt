package dev.novacain.hive

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu

/**
 * Рабочий стол — одни соты. Выключили экран — соты прячутся, после разблокировки прорастают волной от центра;
 * открыли приложение — поле влетает в значок, а окно растёт из него; вернулись — поле отъезжает на место;
 * «домой» на столе — к центру.
 */
class HomeActivity : Activity(), HiveView.Listener {
    private lateinit var hive: HiveView
    private lateinit var anchor: View
    private lateinit var apps: Apps
    private var resumed = false
    private var launched = false
    private var introArmed = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    introArmed = true
                    hive.armIntro()
                }
                Intent.ACTION_USER_PRESENT -> if (resumed) maybeIntro()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this)
        hive = HiveView(this).also { it.listener = this }
        anchor = View(this)
        root.addView(hive, FrameLayout.LayoutParams(-1, -1))
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        apps = Apps(this, onChanged = { hive.setApps(it) }, onIcon = { hive.invalidate() })
        apps.start()
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        introArmed = true
        hive.armIntro()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (introArmed) maybeIntro()
        else if (launched) hive.playReturn()
        launched = false
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        hive.resetLaunch()
    }

    /** Соты прорастают, только когда экран уже разблокирован (иначе ждём USER_PRESENT). */
    private fun maybeIntro() {
        if (!introArmed) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        introArmed = false
        hive.playIntro()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // «домой», когда стол уже на экране
        if (resumed && Intent.ACTION_MAIN == intent.action) hive.goHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        hive.goHome()                                    // со стола уходить некуда
    }

    override fun onDestroy() {
        unregisterReceiver(screen)
        apps.stop()
        super.onDestroy()
    }

    override fun onLaunch(app: App, bounds: Rect) {
        launched = true
        val opts = ActivityOptions.makeScaleUpAnimation(hive, bounds.left, bounds.top, bounds.width(), bounds.height())
        try {
            getSystemService(android.content.pm.LauncherApps::class.java)
                .startMainActivity(app.component, app.user, bounds, opts.toBundle())
        } catch (t: Throwable) {
            launched = false
            hive.resetLaunch()
        }
    }

    override fun onLongPress(app: App, x: Float, y: Float) {
        anchor.translationX = x
        anchor.translationY = y
        val menu = PopupMenu(this, anchor, Gravity.CENTER_HORIZONTAL)
        menu.menu.add(0, 1, 0, app.label).isEnabled = false
        menu.menu.add(0, 2, 1, getString(R.string.app_info))
        menu.menu.add(0, 3, 2, getString(R.string.uninstall))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> getSystemService(android.content.pm.LauncherApps::class.java)
                    .startAppDetailsActivity(app.component, app.user, null, null)
                3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))
                    .putExtra(Intent.EXTRA_USER, app.user))
            }
            true
        }
        menu.show()
    }
}
