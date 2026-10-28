package dev.novacain.hive

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import java.io.File
import java.text.Collator
import java.util.concurrent.Executors

/** Приложение в сотах. icon — круглая картинка, появляется, когда догрузится (до этого — тусклый кружок). */
class App(val label: String, val component: ComponentName, val user: UserHandle) {
    @Volatile var icon: Bitmap? = null
    var tint: Int = Color.GRAY
    val key = component.flattenToString() + "#" + user.hashCode()
}

/**
 * Список приложений из LauncherApps (все профили, включая рабочий), по алфавиту. Значки — «как на столе», от лаунчера
 * Nothing (IconInbox); пока их нет — свои круглые из адаптивных значков. Грузятся в фоне четырьмя потоками в порядке
 * сот (от центра), свои — с кэшем на диске (ключ — компонент, профиль и время файла APK).
 * Изменения пакетов — через LauncherApps.Callback: список перечитывается, лаунчер Nothing досылает значки.
 */
class Apps(private val ctx: Context, private val onChanged: (List<App>) -> Unit, private val onIcon: () -> Unit) {
    private val la = ctx.getSystemService(LauncherApps::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4) { r -> Thread(r, "hive-icons").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val own = File(ctx.cacheDir, "icons").apply { mkdirs() }
    val iconPx = (96 * ctx.resources.displayMetrics.density).toInt()
    @Volatile private var generation = 0
    private var current: List<Pair<App, LauncherActivityInfo>> = emptyList()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reloadLater()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reloadLater()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reloadLater()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reloadLater()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reloadLater()
    }

    private val reload = Runnable {
        load()
        IconInbox.request(ctx, iconPx)                   // у нового или обновлённого приложения — новый значок
    }

    fun start() {
        la.registerCallback(callback, main)
        IconInbox.listener = { key -> if (key != null) refresh(key) }
        load()
        IconInbox.request(ctx, iconPx)
    }

    fun stop() {
        la.unregisterCallback(callback)
        IconInbox.listener = null
        io.shutdownNow()
    }

    private fun reloadLater() {
        main.removeCallbacks(reload)
        main.postDelayed(reload, 300)
    }

    fun load() {
        val gen = ++generation
        val infos = ArrayList<LauncherActivityInfo>()
        for (u in la.profiles) infos += la.getActivityList(null, u)
        val self = ctx.packageName
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val old = current.associate { it.first.key to it.first.icon }
        val apps = infos.filter { it.componentName.packageName != self }
            .map { App(it.label?.toString() ?: it.componentName.packageName, it.componentName, it.user) to it }
            .sortedWith { a, b -> collator.compare(a.first.label, b.first.label) }
        for ((app, _) in apps) app.icon = old[app.key]
        current = apps
        onChanged(apps.map { it.first })
        for ((app, info) in apps) {
            if (app.icon != null) continue
            io.execute {
                if (gen != generation) return@execute
                val bmp = try {
                    fromLauncher(app) ?: fromDisk(app, info) ?: circle(info.getBadgedIcon(0)).also { toDisk(app, info, it) }
                } catch (t: Throwable) {
                    null
                } ?: return@execute
                app.icon = bmp
                post()
            }
        }
    }

    /** Лаунчер прислал значок — подменить. */
    private fun refresh(key: String) {
        val app = current.firstOrNull { it.first.key == key }?.first ?: return
        io.execute {
            val bmp = fromLauncher(app) ?: return@execute
            app.icon = bmp
            post()
        }
    }

    private fun post() {
        main.removeCallbacks(onIcon)
        main.post(onIcon)
    }

    private fun fromLauncher(app: App): Bitmap? {
        val f = IconInbox.file(ctx, app.key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE })
    }

    private fun ownFile(app: App, info: LauncherActivityInfo): File {
        val stamp = File(info.applicationInfo.sourceDir).lastModified()
        return File(own, app.key.replace(Regex("[^A-Za-z0-9._#-]"), "_") + "_" + stamp + "_" + iconPx + ".png")
    }

    private fun fromDisk(app: App, info: LauncherActivityInfo): Bitmap? {
        val f = ownFile(app, info)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE })
    }

    private fun toDisk(app: App, info: LauncherActivityInfo, bmp: Bitmap) {
        try {
            val prefix = app.key.replace(Regex("[^A-Za-z0-9._#-]"), "_") + "_"
            own.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }   // старые версии значка
            val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
            java.io.FileOutputStream(ownFile(app, info)).use { soft.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (soft !== bmp) soft.recycle()
        } catch (_: Throwable) {
        }
    }

    /**
     * Свой круглый значок (пока лаунчер Nothing не прислал свой). Адаптивный — фон и передний слой (они в полтора раза
     * больше видимой части) под круглой маской, обычный — на белом круге с полями.
     */
    private fun circle(d: Drawable): Bitmap {
        val s = iconPx
        val raw = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(raw)
        if (d is AdaptiveIconDrawable) {
            val pad = s / 4
            d.background?.let { it.setBounds(-pad, -pad, s + pad, s + pad); it.draw(c) }
            d.foreground?.let { it.setBounds(-pad, -pad, s + pad, s + pad); it.draw(c) }
        } else {
            c.drawColor(Color.WHITE)
            val inset = (s * 0.16f).toInt()
            d.setBounds(inset, inset, s - inset, s - inset)
            d.draw(c)
        }
        // круглая маска со сглаживанием края (clipPath режет ступеньками)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(out).drawCircle(s / 2f, s / 2f, s / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
        raw.recycle()
        return out.copy(Bitmap.Config.HARDWARE, false)?.also { out.recycle() } ?: out
    }
}
