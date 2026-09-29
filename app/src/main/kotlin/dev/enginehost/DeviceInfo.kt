package dev.enginehost

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import java.io.BufferedReader
import java.io.FileReader

/**
 * Device information gathered for bug reports, cached after first query.
 */
data class DeviceInfo(
    val model: String,
    val soc: String,
    val gpuRenderer: String,
    val gpuDriverVersion: String,
    val androidVersion: String,
    val androidApiLevel: String,
    val runningAbi: String,
    val totalRamMb: String,
    val displaySize: String,
    val refreshRate: String
) {
    companion object {
        @Volatile private var instance: DeviceInfo? = null

        fun get(context: Context): DeviceInfo {
            return instance ?: synchronized(this) {
                instance ?: queryAndCache(context).also { instance = it }
            }
        }

        private fun queryAndCache(context: Context): DeviceInfo {
            return DeviceInfo(
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                soc = querySoc(),
                gpuRenderer = queryGpuRenderer(),
                gpuDriverVersion = queryGpuDriverVersion(),
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                androidApiLevel = Build.VERSION.SDK_INT.toString(),
                runningAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                totalRamMb = queryTotalRam(),
                displaySize = queryDisplaySize(context),
                refreshRate = queryRefreshRate(context)
            )
        }

        private fun querySoc(): String {
            // Try to get SoC from /proc/cpuinfo
            return runCatching {
                BufferedReader(FileReader("/proc/cpuinfo")).use { reader ->
                    reader.lines()
                        .firstOrNull { it.contains("Hardware") }?.let { line ->
                            line.substringAfter(":").trim()
                        } ?: "unknown"
                }
            }.getOrElse { "unknown" }
        }

        private fun queryGpuRenderer(): String {
            // Get GPU renderer from system properties
            return runCatching {
                val renderer = Build.getString("ro.opengles.version")
                    ?: Build.getString("ro.hardware.gpu.renderer")
                    ?: Build.getString("ro.boot.bootloader")
                    ?: "unknown"
                renderer.ifEmpty { "unknown" }
            }.getOrElse { "unknown" }
        }

        private fun queryGpuDriverVersion(): String {
            // Try to get GPU driver version from system properties
            return runCatching {
                val version = Build.getString("ro.gpu.driver.version")
                    ?: Build.getString("ro.opengles.version")
                    ?: "unknown"
                version.ifEmpty { "unknown" }
            }.getOrElse { "unknown" }
        }

        private fun queryTotalRam(): String {
            // Get total RAM from /proc/meminfo
            return runCatching {
                BufferedReader(FileReader("/proc/meminfo")).use { reader ->
                    reader.lines()
                        .firstOrNull { it.startsWith("MemTotal:") }?.let { line ->
                            val kb = line.substringAfter(":").trim().removeSuffix("kB").trim().toIntOrNull()
                            kb?.let { "${(it / 1024).toString()} MB" } ?: "unknown"
                        } ?: "unknown"
                }
            }.getOrElse { "unknown" }
        }

        private fun queryDisplaySize(context: Context): String {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display: Display = wm.defaultDisplay
            val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
            return "${metrics.widthPixels}x${metrics.heightPixels}"
        }

        private fun queryRefreshRate(context: Context): String {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display: Display = wm.defaultDisplay
            return runCatching {
                "${display.refreshRate} Hz"
            }.getOrElse { "unknown" }
        }
    }
}