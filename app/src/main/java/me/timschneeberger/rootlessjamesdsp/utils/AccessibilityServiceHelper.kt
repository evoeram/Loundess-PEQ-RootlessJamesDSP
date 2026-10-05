package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import me.timschneeberger.hiddenapi_impl.ShizukuSystemServerApi
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.service.VolumeKeyAccessibilityService
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.context.GlobalContext
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Manages enabling/disabling the VolumeKeyAccessibilityService on Android 13+.
 *
 * Android 13+ blocks sideloaded apps from enabling Accessibility Services via a
 * restricted AppOp (ACCESS_RESTRICTED_SETTINGS). This helper works around that by:
 *
 * 1. If WRITE_SECURE_SETTINGS is granted (via ADB or Shizuku):
 *    - Unrestricts the ACCESS_RESTRICTED_SETTINGS appop
 *    - Enables the accessibility service via secure settings
 * 2. If Shizuku is available:
 *    - Uses Shizuku's system server access to perform the same operations
 * 3. Fallback: opens system accessibility settings for manual enable
 *    (will work after the user unrestricts via ADB command)
 */
object AccessibilityServiceHelper {

    private const val TAG = "AccessibilityHelper"

    /**
     * Check if the accessibility service is currently enabled.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expectedComponent = "${context.packageName}/${VolumeKeyAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedComponent)
    }

    /**
     * Check if ACCESS_RESTRICTED_SETTINGS appop is blocking us.
     * Returns true if the op is in "ignore" or "deny" mode.
     */
    fun isRestricted(context: Context): Boolean {
        // On Android < 13, there's no restriction
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        return try {
            val op = getRestrictedSettingsOp(appOpsManager)
            val uid = android.os.Process.myUid()
            val mode = appOpsManager.unsafeCheckOpNoThrow(op, uid, context.packageName)
            mode != android.app.AppOpsManager.MODE_ALLOWED && mode != android.app.AppOpsManager.MODE_DEFAULT
        } catch (e: Exception) {
            // If we can't check, assume not restricted
            false
        }
    }

    /**
     * Attempt to enable the accessibility service automatically.
     * Returns a [Result] indicating success or the reason for failure.
     */
    fun tryEnableService(context: Context): Result {
        val component = "${context.packageName}/${VolumeKeyAccessibilityService::class.java.name}"

        // Already enabled?
        if (isServiceEnabled(context)) return Result.SUCCESS

        // Strategy 0: Root shell (root flavor only)
        if (tryEnableViaRoot(context, component)) {
            if (isServiceEnabled(context)) return Result.SUCCESS
        }

        // Strategy 1: WRITE_SECURE_SETTINGS (granted via ADB or Shizuku)
        if (hasWriteSecureSettings(context)) {
            return try {
                // First, unrestrict ourselves
                unrestrictViaSecureSettings(context)

                // Then enable the service
                enableServiceViaSecureSettings(context, component)

                if (isServiceEnabled(context)) Result.SUCCESS
                else Result.FAILED_UNKNOWN
            } catch (e: Exception) {
                Timber.w(e, "Failed to enable via WRITE_SECURE_SETTINGS")
                Result.FAILED_UNKNOWN
            }
        }

        // Strategy 2: Shizuku
        if (isShizukuAvailable()) {
            return try {
                enableServiceViaShizuku(context, component)

                if (isServiceEnabled(context)) Result.SUCCESS
                else Result.FAILED_UNKNOWN
            } catch (e: Exception) {
                Timber.w(e, "Failed to enable via Shizuku")
                Result.FAILED_SHIZUKU_ERROR
            }
        }

        // No automatic method available
        return Result.NEEDS_MANUAL_SETUP
    }

    /**
     * Get the ADB command users can run to grant WRITE_SECURE_SETTINGS.
     */
    fun getAdbGrantCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    }

    /**
     * Get the full ADB command sequence to enable the accessibility service.
     */
    fun getAdbEnableCommands(context: Context): String {
        val pkg = context.packageName
        val component = "$pkg/${VolumeKeyAccessibilityService::class.java.name}"
        return buildString {
            append("adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS\n")
            append("adb shell settings put secure enabled_accessibility_services \"$component\"\n")
            append("adb shell settings put secure accessibility_enabled 1")
        }
    }

    // --- Internal methods ---

    /**
     * Try to enable accessibility service using root shell (root flavor only).
     * Uses `settings put secure` command via su.
     */
    private fun tryEnableViaRoot(context: Context, component: String): Boolean {
        return try {
            val existing = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val newServices = if (existing.isNotBlank() && !existing.contains(component)) {
                "$existing:$component"
            } else {
                component
            }

            // Use reflection to avoid compile-time dependency on RootShellImpl in main source
            val rootShellClass = try {
                Class.forName("me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl")
            } catch (e: ClassNotFoundException) {
                return false
            }

            val cmd1 = rootShellClass.getMethod("cmd", String::class.java)
            val success1 = cmd1.invoke(
                rootShellClass.getDeclaredField("INSTANCE").get(null),
                "settings put secure enabled_accessibility_services \"$newServices\""
            ) as Boolean

            val success2 = cmd1.invoke(
                rootShellClass.getDeclaredField("INSTANCE").get(null),
                "settings put secure accessibility_enabled 1"
            ) as Boolean

            success1 && success2
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable accessibility service via root shell")
            false
        }
    }

    private fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun unrestrictViaSecureSettings(context: Context) {
        // Once we have WRITE_SECURE_SETTINGS, we can use appops command
        // Actually we need to set the appop via Settings.Secure or via exec
        // The most reliable way is to use the `appops` shell command through Shizuku exec
        // or just set the secure setting directly since WRITE_SECURE_SETTINGS allows that

        // Actually, ACCESS_RESTRICTED_SETTINGS is an appop, not a secure setting.
        // But with WRITE_SECURE_SETTINGS, we can set enabled_accessibility_services directly
        // and the system will allow it because we have the permission.
        // However, on Android 13+, even with WRITE_SECURE_SETTINGS, the system may still
        // block setting enabled_accessibility_services if the appop is restricted.
        // We need to unrestrict the appop first.

        // Try using runtime exec for appops (works if running as shell/ADB user)
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c",
                    "appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
            )
            process.waitFor()
        } catch (e: Exception) {
            Timber.d("appops exec failed (expected without ADB): ${e.message}")
        }
    }

    private fun enableServiceViaSecureSettings(context: Context, component: String) {
        // Preserve existing services
        val existing = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val newServices = if (existing.isNotBlank() && !existing.contains(component)) {
            "$existing:$component"
        } else {
            component
        }

        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            newServices
        )
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            1
        )
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun enableServiceViaShizuku(context: Context, component: String) {
        val pkg = context.packageName
        val uid = context.applicationInfo.uid

        // 1. Unrestrict ACCESS_RESTRICTED_SETTINGS appop
        try {
            ShizukuSystemServerApi.AppOpsService_setMode(
                "ACCESS_RESTRICTED_SETTINGS",
                uid,
                pkg,
                ShizukuSystemServerApi.APP_OPS_MODE_ALLOW
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to unrestrict ACCESS_RESTRICTED_SETTINGS via Shizuku")
            // Try via exec as fallback
            ShizukuSystemServerApi.exec("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        }

        // 2. Enable the service via settings put
        val existing = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val newServices = if (existing.isNotBlank() && !existing.contains(component)) {
            "$existing:$component"
        } else {
            component
        }

        ShizukuSystemServerApi.exec(
            "settings put secure enabled_accessibility_services \"$newServices\""
        )
        ShizukuSystemServerApi.exec("settings put secure accessibility_enabled 1")
    }

    private fun getRestrictedSettingsOp(
        appOpsManager: android.app.AppOpsManager
    ): String {
        // The op string varies by Android version
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "ACCESS_RESTRICTED_SETTINGS"
        } else {
            "ACCESS_RESTRICTED_SETTINGS"
        }
    }

    enum class Result {
        SUCCESS,
        NEEDS_MANUAL_SETUP,
        FAILED_SHIZUKU_ERROR,
        FAILED_UNKNOWN
    }
}
