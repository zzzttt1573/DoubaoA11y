package io.github.doubaoa11y.ui

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 音频目录、偏好和图标的同一恢复事务。调用者可在文件操作时复用 lock 避免并发导入。 */
object BackupResourceTransaction {
    val lock = Any()
    fun restore(soundDirectory: File, incoming: Map<String, File>?, replaceAll: Boolean,
        applySettings: () -> Unit, rollbackSettings: () -> Unit,
        applyIcon: () -> Unit, rollbackIcon: () -> Unit) {
        synchronized(lock) {
            val parent = soundDirectory.parentFile!!.canonicalFile
            require(soundDirectory.name == "sounds" && soundDirectory.canonicalFile.parentFile == parent &&
                !Files.isSymbolicLink(soundDirectory.toPath())) { "音频目录无效" }
            var next: File? = null
            var previous: File? = null
            var installed = false
            var originalMoved = false
            var settingsStarted = false
            var iconStarted = false
            var finished = false
            try {
                if (incoming != null) {
                    require(incoming.keys.all { ZipBackup.validSlot(it) }) { "音频槽位无效" }
                    next = Files.createTempDirectory(parent.toPath(), ".sounds-next-").toFile()
                    if (!replaceAll && soundDirectory.isDirectory) {
                        for (old in ZipBackup.audioFiles(soundDirectory)) {
                            if (old.exists()) {
                                require(old.isFile && !Files.isSymbolicLink(old.toPath()) && old.length() <= ZipBackup.MAX_AUDIO) { "现有音频无效" }
                                old.copyTo(File(next, old.name))
                            }
                        }
                    }
                    for ((slot, file) in incoming) {
                        require(file.isFile && file.length() in 1..ZipBackup.MAX_AUDIO.toLong()) { "待恢复音频无效" }
                        file.copyTo(File(next, "$slot.audio"), overwrite = true)
                    }
                    require(next.listFiles().orEmpty().sumOf { it.length() } <= ZipBackup.MAX_TOTAL) { "恢复音频总量超过 32 MB" }
                    if (soundDirectory.exists()) {
                        require(soundDirectory.isDirectory) { "音频目录被其他文件占用" }
                        previous = File(parent, ".sounds-before-${UUID.randomUUID()}")
                        Files.move(soundDirectory.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        originalMoved = true
                    }
                    Files.move(next.toPath(), soundDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    installed = true
                }
                settingsStarted = true
                applySettings()
                iconStarted = true
                applyIcon()
                finished = true
            } catch (failure: Throwable) {
                var rollbackFailed = false
                try { if (settingsStarted) rollbackSettings() } catch (t: Throwable) { failure.addSuppressed(t); rollbackFailed = true }
                try { if (iconStarted) rollbackIcon() } catch (t: Throwable) { failure.addSuppressed(t); rollbackFailed = true }
                try {
                    if (installed && !soundDirectory.deleteRecursively()) error("无法移除恢复中的音频")
                    if (originalMoved) Files.move(previous!!.toPath(), soundDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (t: Throwable) { failure.addSuppressed(t); rollbackFailed = true }
                if (rollbackFailed) throw IllegalStateException("恢复失败且回滚未完成，原音频保留在应用私有恢复目录，请检查设置", failure)
                throw failure
            } finally {
                next?.takeIf { it.exists() }?.deleteRecursively()
                // 只有成功提交时删除原音频；回滚异常时保留恢复目录。
                if (finished) previous?.deleteRecursively()
            }
        }
    }
}
