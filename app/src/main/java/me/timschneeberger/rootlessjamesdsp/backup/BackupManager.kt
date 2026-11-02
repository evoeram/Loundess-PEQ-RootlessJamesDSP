package me.timschneeberger.rootlessjamesdsp.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Job
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.broadcastPresetLoadEvent
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import okio.buffer
import okio.gzip
import okio.sink
import okio.source
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(private val context: Context): KoinComponent {
    private val preferences: Preferences.App by inject()
    var job: Job? = null

    /**
     * Create backup file from database
     *
     * @param uri path of Uri
     * @param isAutoBackup backup called from scheduled backup job
     */
    fun createBackup(uri: Uri, isAutoBackup: Boolean): String {
        Timber.d("Creating backup (auto=$isAutoBackup) to $uri")

        var file: UniFile? = null
        try {
            file = (if (isAutoBackup) {
                        // Get dir of file and create
                        var dir = UniFile.fromUri(context, uri)
                        dir = dir.createDirectory("automatic")

                        // Delete older backups
                        val numberOfBackups = preferences.get<String>(R.string.key_backup_maximum).toIntOrNull() ?: 2
                        val backupRegex = Regex("""jamesdsp_backup_\d+-\d+-\d+_\d+-\d+.tar.gz""")
                        dir.listFiles { _, filename -> backupRegex.matches(filename) }
                            .orEmpty()
                            .sortedByDescending { it.name }
                            .drop(numberOfBackups - 1)
                            .forEach { it.delete() }

                        // Create new file to place backup
                        dir.createFile(getBackupFilename())
                    } else {
                        UniFile.fromUri(context, uri)
                    })
                ?: throw Exception("Couldn't create backup file")

            if (!file.isFile) {
                throw IllegalStateException("Failed to get handle on file")
            }

            Tar.Composer(file.openOutputStream().sink().gzip().buffer().outputStream()).use { c ->
                c.metadata = mutableMapOf(
                    META_MIN_VERSION_CODE to BuildConfig.VERSION_CODE.toString(),
                    META_FLAVOR to BuildConfig.FLAVOR,
                    META_IS_BACKUP to true.toString(),
                    META_HAS_DEVICE_PROFILES to false.toString()
                )

                File(context.applicationInfo.dataDir + "/shared_prefs")
                    .listFiles()
                    ?.filter { it.name.startsWith("dsp_") }
                    ?.filter { it.extension == "xml" }
                    ?.forEach { c.add(it, "shared_prefs/${it.name}") }

                if(preferences.get<Boolean>(R.string.key_device_profiles_enable)) {
                    c.metadata[META_HAS_DEVICE_PROFILES] = true.toString()
                    File(context.applicationInfo.dataDir + "/files/profiles").let { root ->
                        root
                            .walkTopDown()
                            .forEach { c.add(it, "profiles/${it.toRelativeString(root)}") }
                    }
                }

                FileLibraryPreference.types.entries.forEach { entry ->
                    File(context.getExternalFilesDir(null), "/${entry.key}")
                        .listFiles()
                        ?.filter { entry.value.any { ext -> it.absolutePath.endsWith(ext) } }
                        ?.forEach { c.add(it, "${entry.key}/${it.name}") }
                }
            }


            return file.uri.toString()
        } catch (e: Exception) {
            Timber.e(e)
            file?.delete()
            throw e
        }
    }

    fun restoreBackup(uri: Uri, dirty: Boolean) {
        Timber.d("Restoring backup from $uri")

        val staging = File(context.cacheDir, "restore_staging")
        val rollback = File(context.cacheDir, "restore_rollback")
        try {
            context.contentResolver.openInputStream(uri)!!.source().gzip().buffer().inputStream().use { stream ->
                val metadata = Tar.Reader(stream, ::isKnownFile).extract(staging)
                if(metadata == null) {
                    throw UnsupportedOperationException(context.getString(R.string.backup_restore_error_format))
                }

                if(BuildConfig.VERSION_CODE < (metadata[META_MIN_VERSION_CODE]?.toIntOrNull() ?: 0)) {
                    throw UnsupportedOperationException(context.getString(R.string.backup_restore_error_version_too_new))
                }

                // Clean restore: move existing data aside first so a failure
                // mid-restore can roll back instead of leaving the user
                // without their previous settings.
                if(!dirty) {
                    rollback.deleteRecursively()
                    moveExistingDataAside(rollback)
                }

                try {
                    var enableDeviceProfiles = false
                    staging.listFiles()?.forEach { file ->
                        if(file.isDirectory && file.name == "shared_prefs")
                            file.copyRecursively(File(context.applicationInfo.dataDir + "/shared_prefs"), true)
                        else if(file.isDirectory && file.name == "profiles") {
                            enableDeviceProfiles = true
                            file.copyRecursively(
                                File(context.applicationInfo.dataDir + "/files/profiles"),
                                true
                            )
                        }
                        else if(file.isDirectory && file.name in FileLibraryPreference.types.keys) {
                            file.copyRecursively(File(context.getExternalFilesDir(null)!!.absolutePath + "/" + file.name), true)
                        }
                    }

                    context.broadcastPresetLoadEvent()
                    context.sendLocalBroadcast(Intent(Constants.ACTION_BACKUP_RESTORED))

                    if(enableDeviceProfiles)
                        preferences.set(R.string.key_device_profiles_enable, true)
                } catch (e: Exception) {
                    if(!dirty) {
                        Timber.e(e, "Restore failed; rolling back to previous data")
                        restoreRollbackData(rollback)
                    }
                    throw e
                }
            }
        } finally {
            staging.deleteRecursively()
            rollback.deleteRecursively()
        }
    }

    /** Moves the data a clean restore would overwrite into [rollbackDir]. */
    private fun moveExistingDataAside(rollbackDir: File) {
        val sharedPrefs = File(context.applicationInfo.dataDir, "shared_prefs")
        sharedPrefs.listFiles { file: File ->
            file.name.startsWith("dsp_") && file.extension == "xml"
        }?.forEach { moveToRollback(it, File(rollbackDir, "shared_prefs")) }

        moveToRollback(File(context.applicationInfo.dataDir, "files/profiles"), File(rollbackDir, "profiles"))

        context.getExternalFilesDir(null)?.listFiles()?.forEach {
            moveToRollback(it, File(rollbackDir, "ext"))
        }
    }

    private fun moveToRollback(source: File, rollbackSub: File) {
        if (!source.exists()) return
        val destination = File(rollbackSub, source.name)
        if (source.renameTo(destination)) return
        try {
            // Cross-filesystem fallback (e.g. external storage on adoptable media)
            source.copyRecursively(destination, overwrite = true)
            source.deleteRecursively()
        } catch (e: Exception) {
            Timber.w(e, "Failed to move ${source.absolutePath} aside for rollback")
        }
    }

    /** Copies the pre-restore snapshot back into place after a failed restore. */
    private fun restoreRollbackData(rollbackDir: File) {
        File(rollbackDir, "shared_prefs").listFiles()?.forEach {
            it.copyRecursively(File(context.applicationInfo.dataDir, "shared_prefs/${it.name}"), overwrite = true)
        }
        File(rollbackDir, "profiles").listFiles()?.forEach {
            it.copyRecursively(File(context.applicationInfo.dataDir, "files/profiles/${it.name}"), overwrite = true)
        }
        val external = context.getExternalFilesDir(null)
        File(rollbackDir, "ext").listFiles()?.forEach {
            external?.let { dir -> it.copyRecursively(File(dir, it.name), overwrite = true) }
        }
    }

    companion object {
        private const val META_MIN_VERSION_CODE = "min_version_code"
        private const val META_FLAVOR = "flavor"
        private const val META_HAS_DEVICE_PROFILES = "has_device_profiles"
        const val META_IS_BACKUP = "is_backup"

        /**
         * Strict allowlist matching the layout [createBackup] produces:
         * `shared_prefs/dsp_*.xml` (flat), any path under `profiles/`, and
         * `<LibraryDir>/<file with known extension>` (flat). Everything else —
         * including `profiles/../../…`-style names — is rejected here and by
         * the canonical-path check in [Tar.Reader.extract].
         */
        internal fun isKnownFile(name: String): Boolean {
            val normalized = name.replace('\\', '/')
            if (normalized.isEmpty() || normalized.startsWith("/")) return false
            // Never allow parent-directory segments under any backup root;
            // Tar.Reader re-checks this, but the allowlist must be exact too.
            if (normalized.split('/').any { it == ".." }) return false

            if (normalized.startsWith("shared_prefs/")) {
                val fileName = normalized.removePrefix("shared_prefs/")
                return fileName.startsWith("dsp_") && fileName.endsWith(".xml") &&
                        !fileName.contains('/')
            }

            if (normalized == "profiles" || normalized.startsWith("profiles/")) {
                return normalized.removePrefix("profiles/").isNotEmpty()
            }

            return FileLibraryPreference.types.any { (dir, extensions) ->
                if (normalized == dir) return@any false
                if (!normalized.startsWith("$dir/")) return@any false
                val fileName = normalized.removePrefix("$dir/")
                !fileName.contains('/') && extensions.any { fileName.endsWith(it) }
            }
        }

        fun getBackupFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            return "jamesdsp_backup_$date.tar.gz"
        }
    }
}
