package br.com.exames.inferencelocal

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import br.com.exames.domain.inference.DeviceCapabilitySnapshot

/** Probes local hardware (RAM, 64-bit ABI, GPU/Vulkan) and connectivity without PII or network calls. */
class AndroidDeviceCapabilityProbe(context: Context) {
    private val appContext = context.applicationContext

    fun snapshot(): DeviceCapabilitySnapshot {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val is64Bit = Build.VERSION.SDK_INT >= 24 &&
            Process.is64Bit() &&
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val hasGlEs31 = (am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30001
        val hasVulkan = Build.VERSION.SDK_INT >= 24 &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        return DeviceCapabilitySnapshot(
            totalRamBytes = memInfo.totalMem.coerceAtLeast(0L),
            availableRamBytes = memInfo.availMem.coerceAtLeast(0L),
            is64Bit = is64Bit,
            hasSupportedGpu = hasGlEs31 || hasVulkan,
        )
    }

    fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isUnmeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
