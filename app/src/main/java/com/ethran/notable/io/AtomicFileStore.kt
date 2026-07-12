package com.ethran.notable.io

import io.shipbook.shipbooksdk.ShipBook
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TEMP_MARKER = ".notable-tmp-"
private const val BACKUP_SUFFIX = ".notable-backup"
private const val STALE_TEMP_AGE_MS = 10 * 60 * 1000L
private val atomicFileStoreLog by lazy { ShipBook.getLogger("AtomicFileStore") }

/** Checked, crash-resistant writes for app-owned local files. */
object AtomicFileStore {
    private val activeTemporaryFiles = ConcurrentHashMap.newKeySet<String>()

    fun write(target: File, writer: (OutputStream) -> Unit) {
        val parent = target.parentFile
            ?: throw IOException("Destination has no parent directory: ${target.absolutePath}")
        ensureDirectory(parent)

        val temp = File(parent, ".${target.name}$TEMP_MARKER${UUID.randomUUID()}")
        activeTemporaryFiles.add(temp.absolutePath)

        try {
            FileOutputStream(temp).use { output ->
                writer(output)
                output.flush()
                output.fd.sync()
            }
            if (!temp.isFile || temp.length() == 0L) {
                throw IOException("Temporary write produced an empty file: ${target.name}")
            }

            commitPrepared(temp, target)
        } catch (e: Exception) {
            if (temp.exists() && !temp.delete()) temp.deleteOnExit()
            throw e
        } finally {
            activeTemporaryFiles.remove(temp.absolutePath)
        }
    }

    /** Commit a fully written and fsynced same-directory temporary file. */
    @Synchronized
    fun commitPrepared(temp: File, target: File) {
        val parent = target.parentFile
            ?: throw IOException("Destination has no parent directory: ${target.absolutePath}")
        if (temp.parentFile?.canonicalFile != parent.canonicalFile) {
            throw IOException("Atomic replacement requires a same-directory temporary file")
        }
        if (!temp.isFile || temp.length() == 0L) {
            throw IOException("Prepared temporary file is missing or empty: ${temp.absolutePath}")
        }
        val backup = File(parent, target.name + BACKUP_SUFFIX)
        if (!tryAtomicReplace(temp, target)) replaceWithRollback(temp, target, backup)
        // The write itself succeeded at this point; a leftover backup is self-healed by the
        // next recoverStaleFiles pass and must not fail the caller.
        if (backup.exists() && !backup.delete()) {
            atomicFileStoreLog.w("Could not remove completed-write backup: ${backup.absolutePath}")
        }
    }

    @Synchronized
    fun recoverStaleFiles(directory: File) {
        if (!directory.exists()) return
        if (!directory.isDirectory) throw IOException("Not a directory: ${directory.absolutePath}")

        val staleBefore = System.currentTimeMillis() - STALE_TEMP_AGE_MS
        safeListFiles(directory).filter {
            it.name.contains(TEMP_MARKER) &&
                    it.lastModified() <= staleBefore &&
                    it.absolutePath !in activeTemporaryFiles
        }.forEach { temp ->
            if (!temp.delete() && temp.exists()) {
                atomicFileStoreLog.w("Could not remove stale temporary file: ${temp.absolutePath}")
            }
        }

        // Recovery is best-effort: a backup that cannot be cleaned or restored right now stays
        // on disk for the next pass instead of failing the caller's unrelated operation.
        safeListFiles(directory).filter { it.name.endsWith(BACKUP_SUFFIX) }.forEach { backup ->
            val target = File(directory, backup.name.removeSuffix(BACKUP_SUFFIX))
            if (target.exists()) {
                if (!backup.delete() && backup.exists()) {
                    atomicFileStoreLog.w("Could not remove stale backup: ${backup.absolutePath}")
                }
            } else {
                runCatching { moveChecked(backup, target, replace = false) }.onFailure {
                    atomicFileStoreLog.w("Could not restore backup ${backup.absolutePath}: ${it.message}")
                }
            }
        }
    }

    fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create directory: ${directory.absolutePath}")
        }
        if (!directory.isDirectory || !directory.canWrite()) {
            throw IOException("Directory is not writable: ${directory.absolutePath}")
        }
    }

    private fun tryAtomicReplace(source: File, target: File): Boolean = try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    } catch (_: AtomicMoveNotSupportedException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun replaceWithRollback(temp: File, target: File, backup: File) {
        if (backup.exists() && !backup.delete()) {
            throw IOException("Could not clear stale backup: ${backup.absolutePath}")
        }
        if (target.exists()) moveChecked(target, backup, replace = false)
        try {
            moveChecked(temp, target, replace = false)
        } catch (e: Exception) {
            if (!target.exists() && backup.exists()) moveChecked(backup, target, replace = false)
            throw e
        }
    }

    private fun moveChecked(source: File, target: File, replace: Boolean) {
        try {
            if (replace) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(source.toPath(), target.toPath())
            }
        } catch (e: Exception) {
            throw IOException(
                "Could not move ${source.absolutePath} to ${target.absolutePath}",
                e,
            )
        }
        if (!target.exists() || source.exists()) {
            throw IOException("File replacement did not complete for ${target.absolutePath}")
        }
    }
}

fun safeListFiles(directory: File): List<File> = try {
    directory.listFiles()?.toList().orEmpty()
} catch (_: SecurityException) {
    emptyList()
}

/** Extract a provider-controlled path as a safe local filename, or reject it. */
fun safeLeafName(value: String): String? {
    val leaf = value.replace('\\', '/').substringAfterLast('/')
    return leaf.takeIf {
        it.isNotBlank() && it != "." && it != ".." && it.length <= 255 && '\u0000' !in it
    }
}
