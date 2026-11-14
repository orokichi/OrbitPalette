package com.orokichi.orbitpalette

import android.app.ActivityManager
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Moves / resizes another app's freeform window for real (so the app follows its title bar).
 * Normal apps can't do this; it goes through Shizuku (shell privileges → IActivityTaskManager.resizeTask).
 */
object TaskCtl {
    const val REQ = 4711

    fun installed() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun request() = runCatching { if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(REQ) }

    private var atmCache: Any? = null
    private fun atm(): Any? {
        if (!granted()) return null
        atmCache?.let { return it }
        return runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            val b = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task"))
            Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, b)
        }.getOrNull()?.also { atmCache = it }
    }

    /** Newest task whose base/top activity belongs to [pkg]. */
    fun taskIdOf(pkg: String): Int? {
        val a = atm() ?: return null
        return runCatching {
            val m = a.javaClass.methods.first { it.name == "getTasks" }
            val args = m.parameterTypes.mapIndexed { i, t ->
                when (t) {
                    Int::class.javaPrimitiveType -> if (i == 0) 64 else -1
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }.toTypedArray()
            @Suppress("UNCHECKED_CAST")
            val list = m.invoke(a, *args) as List<Any?>
            list.filterIsInstance<ActivityManager.RunningTaskInfo>()
                .firstOrNull { it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg }?.taskId
        }.getOrNull()
    }

    fun resize(taskId: Int, r: Rect): Boolean {
        val a = atm() ?: return false
        return runCatching {
            val m = a.javaClass.methods.first { it.name == "resizeTask" && it.parameterTypes.size == 3 }
            m.invoke(a, taskId, r, 0); true
        }.getOrDefault(false)
    }

    fun remove(taskId: Int): Boolean {
        val a = atm() ?: return false
        return runCatching {
            val m = a.javaClass.methods.first { it.name == "removeTask" && it.parameterTypes.size == 1 }
            m.invoke(a, taskId) as? Boolean ?: true
        }.getOrDefault(false)
    }
}
