package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import android.system.ErrnoException
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarHeader
import org.kamranzafar.jtar.TarInputStream
import org.kamranzafar.jtar.TarOutputStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Tar {
    private const val FILE_METADATA = "metadata"

    /**
     * Create tar composer
     * @throws FileNotFoundException if file already exists as a directory or cannot be created for other reasons
     * @throws SecurityException if write access is denied
     */
    class Composer: AutoCloseable, KoinComponent {
        constructor(outputStream: OutputStream) {
            stream = TarOutputStream(outputStream)
        }

        constructor(file: File) {
            stream = TarOutputStream(file)
        }

        private val context: Context by inject()
        private val stream: TarOutputStream

        var metadata = mutableMapOf<String, String>()

        fun add(file: File, entryPath: String? = null): Boolean {
            if (!file.exists() || file.isDirectory) {
                Timber.e("addFile: ${file.absolutePath} is not valid")
                return false
            }

            stream.putNextEntry(TarEntry(file, (entryPath ?: file.name)))
            BufferedInputStream(FileInputStream(file)).use { origin ->
                var count: Int
                val data = ByteArray(2048)
                while (origin.read(data).also { count = it } != -1) {
                    stream.write(data, 0, count)
                }
                stream.flush()
            }
            return true
        }

        override fun close() {
            add(
                File(context.cacheDir, FILE_METADATA).apply {
                    writeText(
                        metadata
                            .map { "${it.key}=${it.value}" }
                            .joinToString("\n")
                    )
                }
            )
            stream.close()
        }
    }

    /** Create tar reader */
    class Reader(
        private val inStream: InputStream,
        private val shouldExtract: ((entryName: String) -> Boolean) = { true },
        private val maxEntryBytes: Long = MAX_ENTRY_BYTES,
        private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
        private val maxEntries: Int = MAX_ENTRIES,
        private val maxMetadataBytes: Long = MAX_METADATA_BYTES,
    ) {
        class ExtractionException(message: String) : IOException(message)

        private fun process(onNextEntry: (tis: TarInputStream, entry: TarEntry, entryName: String) -> Unit) {
            TarInputStream(BufferedInputStream(inStream)).use { tis ->
                var entry: TarEntry?
                while (tis.nextEntry.also { entry = it } != null) {
                    val current = entry ?: break
                    val entryName = current.name ?: break

                    if (!shouldExtract(entryName) && entryName != FILE_METADATA) {
                        Timber.w("Entry name ignored: $entryName")
                        continue
                    }

                    onNextEntry(tis, current, entryName)
                }
            }
        }

        /**
         * Normalizes separators and verifies that [rawName] is a safe relative path:
         * no absolute paths, no `..` segments, no malformed or NUL-containing names.
         */
        internal fun validateEntryName(rawName: String): String {
            if (rawName.indexOf('\u0000') >= 0)
                throw ExtractionException("Entry contains NUL byte")

            // Trailing slashes are conventional for directory entries.
            val name = rawName.replace('\\', '/').trimEnd('/')
            if (name.isBlank())
                throw ExtractionException("Entry has an empty name")
            if (name.startsWith("/") || name.startsWith("~/") ||
                name.length > 1 && name[1] == ':' && name[0].isLetter())
                throw ExtractionException("Absolute path in entry: $rawName")

            val segments = name.split('/')
            if (segments.any { it == ".." })
                throw ExtractionException("Path traversal in entry: $rawName")
            if (segments.any { it.isEmpty() || it == "." })
                throw ExtractionException("Malformed entry name: $rawName")

            return name
        }

        fun validate(): Boolean {
            Timber.d("Validating preset")

            var knownCount = 0
            try {
                process { _, _, _ -> knownCount++ }
            }
            catch(ex: Exception) {
                Timber.e("Validation failed due to exception")
                Timber.w(ex)
                return false
            }

            if (knownCount < 1) {
                Timber.e("Archive did not contain any useful data")
                return false
            }

            return true
        }

        fun extract(targetFolder: File) : Map<String, String>? {
            targetFolder.deleteRecursively()
            targetFolder.mkdirs()

            val root = targetFolder.canonicalFile
            val metadataBytes = ByteArrayOutputStream()
            var totalBytes = 0L
            var entryCount = 0

            try {
                process { stream, entry, rawName ->
                    val name = validateEntryName(rawName)
                    val linkFlag = entry.header.linkFlag

                    if (name == FILE_METADATA) {
                        // The metadata file is collected in memory and never touches disk.
                        copyBounded(stream, metadataBytes, maxMetadataBytes, name)
                        return@process
                    }
                    if (entry.isDirectory || linkFlag == TarHeader.LF_DIR) {
                        // Directory entries only materialize their path.
                        File(root, name).mkdirs()
                        return@process
                    }

                    if (linkFlag != TarHeader.LF_NORMAL && linkFlag != TarHeader.LF_OLDNORM &&
                        linkFlag != TarHeader.LF_CONTIG) {
                        throw ExtractionException("Unsupported entry type for $rawName")
                    }

                    if (entryCount >= maxEntries)
                        throw ExtractionException("Too many entries (limit $maxEntries)")
                    entryCount++

                    // Defense in depth: even a crafted name that slipped through
                    // validation must stay below the canonical target root.
                    val destination = File(root, name).canonicalFile
                    if (destination.path != root.path &&
                        !destination.path.startsWith(root.path + File.separator)) {
                        throw ExtractionException("Entry escapes target folder: $rawName")
                    }

                    destination.parentFile?.mkdirs()
                    var entryBytes = 0L
                    BufferedOutputStream(FileOutputStream(destination)).use { dest ->
                        var count: Int
                        val data = ByteArray(2048)
                        while (stream.read(data).also { count = it } != -1) {
                            entryBytes += count
                            totalBytes += count
                            if (entryBytes > maxEntryBytes)
                                throw ExtractionException("Entry too large: $rawName")
                            if (totalBytes > maxTotalBytes)
                                throw ExtractionException("Archive too large in total")
                            dest.write(data, 0, count)
                        }
                        dest.flush()
                    }
                }
                metadataBytes.flush()
            }
            catch(ex: ErrnoException) {
                Timber.e("Extraction failed; errno=${ex.errno}")
                Timber.w(ex)
                targetFolder.deleteRecursively()
                return null
            }
            catch(ex: IOException) {
                Timber.e("Extraction failed")
                Timber.w(ex)
                targetFolder.deleteRecursively()
                return null
            }

            return mutableMapOf<String, String>().apply {
                metadataBytes.toString().lines().forEach {
                    val args = it.split("=")
                    if(args.size < 2)
                        return@forEach

                    this[args[0]] = args[1].trim()
                }
            }
        }

        private fun copyBounded(stream: InputStream, out: OutputStream, limit: Long, name: String) {
            var total = 0L
            var count: Int
            val data = ByteArray(2048)
            while (stream.read(data).also { count = it } != -1) {
                total += count
                if (total > limit)
                    throw ExtractionException("Entry too large: $name")
                out.write(data, 0, count)
            }
        }

        companion object {
            private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024      // single file
            private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024     // whole archive payload
            private const val MAX_ENTRIES = 10000                       // regular files
            private const val MAX_METADATA_BYTES = 64L * 1024
        }
    }

}