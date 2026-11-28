package io.github.doubaoa11y

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

internal object NativeRestoreFiles {
    const val FILE_LIMIT = 512
    const val ENTRY_LIMIT = FILE_LIMIT + 1 // 数据条目（含目录）和一份清单
    const val MANIFEST_LIMIT = 2 * 1024 * 1024

    fun readManifest(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, MANIFEST_LIMIT - output.size() + 1))
            if (n < 0) return output.toByteArray()
            require(output.size() + n <= MANIFEST_LIMIT) { "manifest.json 过大" }
            output.write(buffer, 0, n)
        }
    }

    // 同进程线程先串行，再与其他豆包进程争用同一把冷启动恢复锁。
    @Synchronized
    fun <T> withPending(pending: File, action: () -> T): T {
        val previous = previous(pending)
        return RandomAccessFile(File(pending.parentFile, "doubaoa11y_restore.lock"), "rw").use { file ->
            file.channel.lock().use {
                if (previous.exists()) {
                    if (pending.exists()) check(previous.deleteRecursively()) { "无法清理旧暂存包" }
                    else check(previous.renameTo(pending)) { "无法恢复上一份暂存包" }
                }
                action()
            }
        }
    }

    private fun previous(pending: File) = File(pending.parentFile, pending.name + ".previous")

    fun publish(staged: File, pending: File) = withPending(pending) {
        val previous = previous(pending)
        if (pending.exists()) check(pending.renameTo(previous)) { "无法移开已有暂存包" }
        if (!staged.renameTo(pending)) {
            check(!previous.exists() || previous.renameTo(pending)) { "恢复旧暂存包失败，下次访问时重试" }
            error("无法提交新暂存包")
        }
        // 新包已生效；清理中断时，下一次持锁访问会继续清理。
        if (previous.exists()) previous.deleteRecursively()
        Unit
    }
}
