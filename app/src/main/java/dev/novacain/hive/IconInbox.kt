package dev.novacain.hive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Значки «как на столе»: их рисует сам лаунчер Nothing (модуль derealization внутри него берёт у каждого приложения
 * итоговый значок — однотонный, цветами ColorEngine, с генератором Nothing для приложений без монохромного слоя) и
 * присылает сюда по одному: call("put", ключ, {png}). Принимаем только от com.nothing.launcher.
 */
class IconInbox : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (callingPackage != LAUNCHER) return null
        val ctx = context ?: return null
        when (method) {
            "put" -> {
                val key = arg ?: return null
                val png = extras?.getByteArray("png") ?: return null
                val f = file(ctx, key)
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(png)
                tmp.renameTo(f)
                main.post { listener?.invoke(key) }
            }
            "done" -> {
                // ключи, которых у лаунчера больше нет, — убрать
                val keep = extras?.getStringArrayList("keys")?.map { name(it) }?.toHashSet() ?: return null
                dir(ctx).listFiles()?.forEach { if (it.name !in keep) it.delete() }
                main.post { listener?.invoke(null) }
            }
        }
        return Bundle()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val LAUNCHER = "com.nothing.launcher"
        private const val ACTION = "dev.novacain.ntclocks.EXPORT_ICONS"
        private val main = Handler(Looper.getMainLooper())

        /** Пришёл значок (ключ) или закончилась выгрузка (null). */
        var listener: ((String?) -> Unit)? = null

        fun dir(ctx: Context) = File(ctx.filesDir, "nt-icons").apply { mkdirs() }
        private fun name(key: String) = key.replace(Regex("[^A-Za-z0-9._#-]"), "_") + ".png"
        fun file(ctx: Context, key: String) = File(dir(ctx), name(key))

        /** Попросить лаунчер Nothing прислать значки нужного размера. */
        fun request(ctx: Context, px: Int) {
            ctx.sendBroadcast(Intent(ACTION).setPackage(LAUNCHER).putExtra("size", px).putExtra("to", ctx.packageName))
        }
    }
}
