package fr.qth.meshcorebridge

import android.content.Context

object BuildConfigInfo {
    fun version(c: Context): String = try {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        "${p.versionName} (code $code)"
    } catch (e: Exception) { "?" }
}
