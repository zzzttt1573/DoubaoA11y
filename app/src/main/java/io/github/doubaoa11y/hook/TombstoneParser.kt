package io.github.doubaoa11y.hook

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale

/**
 * 轻量纯 Kotlin 解析器：处理 Android ApplicationExitInfo.traceInputStream。
 *
 * - 纯文本 Trace（ANR / 文本 Tombstone）：筛选信号、崩溃原因与调用栈行；
 * - Android 12+ 二进制 Protobuf Tombstone（system/core/debuggerd/proto/tombstone.proto）：
 *   精确解码 pid、tid、signal_info（含 SEGV_ACCERR/SEGV_MAPERR 与 fault_address）、
 *   abort_message、causes，以及崩溃线程 threads[tid] 的有序调用栈 #00..#23；
 * - 若流不是标准 Tombstone Protobuf，则降级提取可读关键符号。
 */
object TombstoneParser {
    private const val PROBE_BYTES = 512
    private const val MAX_TEXT_BYTES = 128 * 1024
    private const val MAX_PROTO_BYTES = 4 * 1024 * 1024
    private const val MAX_THREAD_MSG_BYTES = 256 * 1024
    private const val MAX_FALLBACK_HEAD = 64 * 1024
    private const val MAX_FRAMES = 24
    private const val MAX_TRACE_CHARS = 2800

    fun summarize(stream: InputStream): String {
        val buffered = BufferedInputStream(stream, 8192)
        val pb = PushbackInputStream(buffered, PROBE_BYTES)
        val probe = ByteArray(PROBE_BYTES)
        val probeLen = readAtMost(pb, probe)
        if (probeLen <= 0) return ""
        pb.unread(probe, 0, probeLen)

        if (looksLikeText(probe, probeLen)) {
            val raw = readUpTo(pb, MAX_TEXT_BYTES)
            return summarizeText(raw)
        }

        val headCap = ByteArrayOutputStream(minOf(MAX_FALLBACK_HEAD, 16384))
        val parsed = try {
            parseProtoTombstone(pb, headCap)
        } catch (_: Throwable) {
            null
        }
        if (parsed != null) return parsed.take(MAX_TRACE_CHARS)

        val head = headCap.toByteArray()
        return if (head.isNotEmpty()) extractReadableTokens(head) else ""
    }

    // ---------------- 纯文本 Trace ----------------

    private fun looksLikeText(bytes: ByteArray, len: Int): Boolean {
        var nul = 0
        var ctrl = 0
        for (i in 0 until len) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) nul++
            else if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) ctrl++
        }
        return nul == 0 && ctrl * 10 < len
    }

    private fun summarizeText(raw: ByteArray): String {
        val text = String(raw, Charsets.UTF_8).replace("\r", "")
        val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.toList()
        val interesting = lines.filter { l ->
            l.startsWith("signal ") || l.startsWith("Abort message:") ||
                l.startsWith("Cause:") || l.startsWith("pid:") ||
                l.startsWith("Cmd line:") || l.startsWith("#") ||
                l.startsWith("backtrace:") || l.contains(".so") ||
                l.startsWith("\"main\"") || l.startsWith("  at ") ||
                l.startsWith("Caused by:") || l.contains("Exception")
        }
        val chosen = if (interesting.size >= 3) interesting.take(45) else lines.take(45)
        return chosen.joinToString("\n") { "  $it" }.take(MAX_TRACE_CHARS)
    }

    // ---------------- Android 12+ Protobuf Tombstone ----------------

    private data class SignalInfo(
        val number: Int = 0,
        val name: String = "",
        val code: Int = 0,
        val codeName: String = "",
        val hasFaultAddress: Boolean = false,
        val faultAddress: Long = 0L,
    )

    private data class BacktraceFrame(
        val relPc: Long = 0L,
        val functionName: String = "",
        val functionOffset: Long = 0L,
        val fileName: String = "",
        val buildId: String = "",
    )

    private data class ThreadInfo(
        val mapKey: Int = 0,
        val id: Int = 0,
        val name: String = "",
        val notes: List<String> = emptyList(),
        val frames: List<BacktraceFrame> = emptyList(),
        val hasMemoryDump: Boolean = false,
        val dumpMappings: List<String> = emptyList(),
    )

    private fun archName(code: Int): String = when (code) {
        0 -> "arm32"
        1 -> "arm64"
        2 -> "x86"
        3 -> "x86_64"
        4 -> "riscv64"
        else -> ""
    }

    private fun parseProtoTombstone(input: InputStream, headCap: ByteArrayOutputStream): String? {
        val reader = ProtoStream(input, MAX_PROTO_BYTES, headCap, MAX_FALLBACK_HEAD)
        var arch = -1
        var pid = 0
        var tid = 0
        var processName = ""
        var cmdLine = ""
        var signal: SignalInfo? = null
        var abortMsg = ""
        val causes = ArrayList<String>(4)
        var crashingThread: ThreadInfo? = null

        while (true) {
            val tag = reader.readVarintOrEof()
            if (tag <= 0L) break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field <= 0 || wire > 5 || wire == 3 || wire == 4) return null

            when (field) {
                1 -> if (wire == 0) arch = reader.readVarint().toInt() else if (!reader.skipField(wire)) break
                5 -> if (wire == 0) pid = reader.readVarint().toInt() else if (!reader.skipField(wire)) break
                6 -> if (wire == 0) tid = reader.readVarint().toInt() else if (!reader.skipField(wire)) break
                7 -> if (wire == 2) processName = reader.readString(256) ?: break else if (!reader.skipField(wire)) break
                8 -> if (wire == 2) {
                    val s = reader.readString(256) ?: break
                    if (cmdLine.isEmpty() && s.isNotEmpty()) cmdLine = s
                } else if (!reader.skipField(wire)) break
                10 -> if (wire == 2) {
                    val b = reader.readLengthDelimited(4096) ?: break
                    signal = parseSignal(b)
                } else if (!reader.skipField(wire)) break
                14 -> if (wire == 2) abortMsg = reader.readString(512) ?: break else if (!reader.skipField(wire)) break
                15 -> if (wire == 2) {
                    val b = reader.readLengthDelimited(2048) ?: break
                    val c = parseCause(b)
                    if (c.isNotEmpty() && causes.size < 4) causes.add(c)
                } else if (!reader.skipField(wire)) break
                16 -> if (wire == 2) {
                    val len = reader.readVarint()
                    if (len < 0L) break
                    if (len > MAX_THREAD_MSG_BYTES) {
                        if (!reader.skipExact(len)) break
                    } else {
                        val b = reader.readExact(len.toInt()) ?: break
                        val t = parseThreadMapEntry(b, targetTid = tid)
                        if (t != null) {
                            if (tid > 0 && (t.mapKey == tid || t.id == tid)) {
                                crashingThread = t
                                // 已精确拿到崩溃线程，无需继续读后续其他线程与大块 memory_mappings
                                break
                            } else if (crashingThread == null && t.hasMemoryDump) {
                                crashingThread = t
                            }
                        }
                    }
                } else if (!reader.skipField(wire)) break
                else -> {
                    if (field > 16 && (crashingThread != null || signal != null)) break
                    if (!reader.skipField(wire)) break
                }
            }
        }

        if (signal == null && crashingThread == null && abortMsg.isEmpty() && causes.isEmpty()) {
            return null
        }

        val sb = StringBuilder()
        val archStr = archName(arch)
        val tName = crashingThread?.name?.takeIf { it.isNotEmpty() } ?: processName.ifEmpty { cmdLine }
        val finalTid = if (tid > 0) tid else (crashingThread?.id ?: 0)
        sb.append("  pid: ").append(pid)
            .append(", tid: ").append(finalTid)
            .append(", name: ").append(tName.ifEmpty { "unknown" })
        if (archStr.isNotEmpty()) sb.append(", arch: ").append(archStr)
        sb.append('\n')

        signal?.let { s ->
            sb.append("  signal ").append(s.number)
            if (s.name.isNotEmpty()) sb.append(" (").append(s.name).append(')')
            sb.append(", code ").append(s.code)
            if (s.codeName.isNotEmpty()) sb.append(" (").append(s.codeName).append(')')
            if (s.hasFaultAddress) {
                sb.append(", fault addr 0x").append(java.lang.Long.toHexString(s.faultAddress))
            }
            sb.append('\n')
        }

        if (abortMsg.isNotEmpty()) {
            sb.append("  Abort message: ").append(abortMsg).append('\n')
        }
        for (c in causes) {
            sb.append("  Cause: ").append(c).append('\n')
        }

        val ct = crashingThread
        if (ct != null) {
            for (n in ct.notes) {
                sb.append("  Note: ").append(n).append('\n')
            }
            if (ct.frames.isNotEmpty()) {
                val is64 = arch == 1 || arch == 3 || arch == 4 || arch < 0
                val pcFmt = if (is64) "%016x" else "%08x"
                sb.append("  backtrace:\n")
                ct.frames.forEachIndexed { idx, f ->
                    sb.append(String.format(Locale.US, "    #%02d pc ", idx))
                    sb.append(String.format(Locale.US, pcFmt, f.relPc))
                    sb.append("  ").append(f.fileName.ifEmpty { "<unknown>" })
                    if (f.functionName.isNotEmpty()) {
                        sb.append(" (").append(f.functionName)
                        if (f.functionOffset > 0L) sb.append('+').append(f.functionOffset)
                        sb.append(')')
                    }
                    sb.append('\n')
                }
            }
            if (ct.frames.size <= 3 && ct.dumpMappings.isNotEmpty()) {
                sb.append("  reg_maps: ").append(ct.dumpMappings.joinToString(", ")).append('\n')
            }
        } else {
            sb.append("  (未在Tombstone前4MB内定位到崩溃线程 tid=").append(tid).append(")\n")
        }

        return sb.toString().trimEnd()
    }

    private fun parseSignal(bytes: ByteArray): SignalInfo {
        val r = ByteSlice(bytes, 0, bytes.size)
        var number = 0
        var name = ""
        var code = 0
        var codeName = ""
        var hasFault = false
        var faultAddr = 0L
        while (r.hasRemaining()) {
            val tag = r.readVarint()
            if (tag <= 0L) break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (field) {
                1 -> if (wire == 0) number = r.readVarint().toInt() else if (!r.skipField(wire)) break
                2 -> if (wire == 2) name = r.readString(64) else if (!r.skipField(wire)) break
                3 -> if (wire == 0) code = r.readVarint().toInt() else if (!r.skipField(wire)) break
                4 -> if (wire == 2) codeName = r.readString(64) else if (!r.skipField(wire)) break
                8 -> if (wire == 0) hasFault = r.readVarint() != 0L else if (!r.skipField(wire)) break
                9 -> if (wire == 0) faultAddr = r.readVarint() else if (!r.skipField(wire)) break
                else -> if (!r.skipField(wire)) break
            }
        }
        return SignalInfo(number, name, code, codeName, hasFault, faultAddr)
    }

    private fun parseCause(bytes: ByteArray): String {
        val r = ByteSlice(bytes, 0, bytes.size)
        while (r.hasRemaining()) {
            val tag = r.readVarint()
            if (tag <= 0L) break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 1 && wire == 2) return r.readString(256)
            if (!r.skipField(wire)) break
        }
        return ""
    }

    private fun parseThreadMapEntry(bytes: ByteArray, targetTid: Int): ThreadInfo? {
        val r = ByteSlice(bytes, 0, bytes.size)
        var key = 0
        var valStart = -1
        var valEnd = -1
        while (r.hasRemaining()) {
            val tag = r.readVarint()
            if (tag <= 0L) break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (field) {
                1 -> if (wire == 0) {
                    key = r.readVarint().toInt()
                    if (targetTid > 0 && key != targetTid) return null
                } else if (!r.skipField(wire)) break
                2 -> if (wire == 2) {
                    val len = r.readLength() ?: return null
                    valStart = r.pos
                    valEnd = r.pos + len
                    r.pos = valEnd
                } else if (!r.skipField(wire)) break
                else -> if (!r.skipField(wire)) break
            }
        }
        if (valStart < 0) return null
        if (targetTid > 0 && key != 0 && key != targetTid) return null
        return parseThread(bytes, valStart, valEnd, key)
    }

    private fun parseThread(bytes: ByteArray, start: Int, end: Int, mapKey: Int): ThreadInfo {
        val r = ByteSlice(bytes, start, end)
        var id = mapKey
        var name = ""
        val notes = ArrayList<String>(4)
        val frames = ArrayList<BacktraceFrame>(16)
        var hasMemoryDump = false
        val dumpMappings = LinkedHashSet<String>()

        while (r.hasRemaining()) {
            val tag = r.readVarint()
            if (tag <= 0L) break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (field) {
                1 -> if (wire == 0) id = r.readVarint().toInt() else if (!r.skipField(wire)) break
                2 -> if (wire == 2) name = r.readString(128) else if (!r.skipField(wire)) break
                4 -> if (wire == 2) {
                    val note = r.readString(160)
                    if (note.isNotEmpty() && notes.size < 4) notes.add(note)
                } else if (!r.skipField(wire)) break
                6 -> if (wire == 2) {
                    val len = r.readLength() ?: break
                    val fStart = r.pos
                    val fEnd = r.pos + len
                    r.pos = fEnd
                    if (frames.size < MAX_FRAMES) {
                        frames.add(parseBacktraceFrame(bytes, fStart, fEnd))
                    }
                } else if (!r.skipField(wire)) break
                7 -> if (wire == 2) {
                    hasMemoryDump = true
                    val len = r.readLength() ?: break
                    val mStart = r.pos
                    val mEnd = r.pos + len
                    r.pos = mEnd
                    if (dumpMappings.size < 6) {
                        val mapName = parseMemoryDumpMapping(bytes, mStart, mEnd)
                        if (mapName.isNotEmpty()) dumpMappings.add(mapName)
                    }
                } else if (!r.skipField(wire)) break
                else -> if (!r.skipField(wire)) break
            }
        }
        return ThreadInfo(mapKey, id, name, notes, frames, hasMemoryDump, dumpMappings.toList())
    }

    private fun parseBacktraceFrame(bytes: ByteArray, start: Int, end: Int): BacktraceFrame {
        val r = ByteSlice(bytes, start, end)
        var relPc = 0L
        var fn = ""
        var fnOff = 0L
        var file = ""
        var buildId = ""
        while (r.hasRemaining()) {
            val tag = r.readVarint()
            if (tag <= 0L) break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (field) {
                1 -> if (wire == 0) relPc = r.readVarint() else if (!r.skipField(wire)) break
                4 -> if (wire == 2) fn = r.readString(200) else if (!r.skipField(wire)) break
                5 -> if (wire == 0) fnOff = r.readVarint() else if (!r.skipField(wire)) break
                6 -> if (wire == 2) file = r.readString(240) else if (!r.skipField(wire)) break
                8 -> if (wire == 2) buildId = r.readString(64) else if (!r.skipField(wire)) break
                else -> if (!r.skipField(wire)) break
            }
        }
        return BacktraceFrame(relPc, fn, fnOff, file, buildId)
    }

    private fun parseMemoryDumpMapping(bytes: ByteArray, start: Int, end: Int): String {
        val r = ByteSlice(bytes, start, end)
        while (r.hasRemaining()) {
            val tag = r.readVarint()
            if (tag <= 0L) break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 2 && wire == 2) return r.readString(180)
            if (!r.skipField(wire)) break
        }
        return ""
    }

    // ---------------- 底层 Protobuf 读入器 ----------------

    private class ProtoStream(
        private val input: InputStream,
        private val maxBytes: Int,
        private val headCap: ByteArrayOutputStream,
        private val headLimit: Int,
    ) {
        private var totalRead = 0
        private val scratch = ByteArray(4096)
        var eof = false
            private set

        private fun readOne(): Int {
            if (totalRead >= maxBytes) {
                eof = true
                return -1
            }
            val b = input.read()
            if (b >= 0) {
                if (headCap.size() < headLimit) headCap.write(b)
                totalRead++
            } else {
                eof = true
            }
            return b
        }

        fun readVarintOrEof(): Long {
            val first = readOne()
            if (first < 0) return -1L
            var result = (first and 0x7F).toLong()
            if ((first and 0x80) == 0) return result
            var shift = 7
            while (shift < 64) {
                val b = readOne()
                if (b < 0) return -1L
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            eof = true
            return -1L
        }

        fun readVarint(): Long = readVarintOrEof()

        fun readExact(len: Int): ByteArray? {
            if (len < 0 || len > maxBytes - totalRead) {
                eof = true
                return null
            }
            val out = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(out, off, len - off)
                if (n < 0) {
                    eof = true
                    return null
                }
                val capLeft = headLimit - headCap.size()
                if (capLeft > 0) headCap.write(out, off, minOf(n, capLeft))
                off += n
                totalRead += n
            }
            return out
        }

        fun readLengthDelimited(maxLen: Int): ByteArray? {
            val len = readVarint()
            if (eof || len < 0L || len > maxLen) return null
            return readExact(len.toInt())
        }

        fun readString(maxLen: Int): String? {
            val len = readVarint()
            if (eof || len < 0L) return null
            if (len > maxLen) {
                val keep = readExact(maxLen) ?: return null
                if (!skipExact(len - maxLen)) return null
                return String(keep, Charsets.UTF_8)
            }
            val b = readExact(len.toInt()) ?: return null
            return String(b, Charsets.UTF_8)
        }

        fun skipExact(len: Long): Boolean {
            if (len < 0L || len > maxBytes - totalRead) {
                eof = true
                return false
            }
            var rem = len
            while (rem > 0L) {
                val chunk = minOf(rem, scratch.size.toLong()).toInt()
                val n = input.read(scratch, 0, chunk)
                if (n < 0) {
                    eof = true
                    return false
                }
                val capLeft = headLimit - headCap.size()
                if (capLeft > 0) headCap.write(scratch, 0, minOf(n, capLeft))
                rem -= n
                totalRead += n
            }
            return true
        }

        fun skipField(wire: Int): Boolean = when (wire) {
            0 -> {
                readVarint()
                !eof
            }
            1 -> skipExact(8L)
            2 -> {
                val len = readVarint()
                !eof && len >= 0L && skipExact(len)
            }
            5 -> skipExact(4L)
            else -> false
        }
    }

    private class ByteSlice(
        private val buf: ByteArray,
        var pos: Int,
        val end: Int,
    ) {
        var eof = false
            private set

        fun hasRemaining(): Boolean = !eof && pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64 && pos < end) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            eof = true
            return 0L
        }

        fun readLength(): Int? {
            val len = readVarint()
            if (eof || len < 0 || len > end - pos) {
                eof = true
                return null
            }
            return len.toInt()
        }

        fun readString(maxLen: Int): String {
            val len = readLength() ?: return ""
            val actual = minOf(len, maxLen)
            val s = String(buf, pos, actual, Charsets.UTF_8)
            pos += len
            return s
        }

        fun skipField(wire: Int): Boolean = when (wire) {
            0 -> {
                readVarint()
                !eof
            }
            1 -> if (8 <= end - pos) { pos += 8; true } else { pos = end; eof = true; false }
            2 -> {
                val len = readLength()
                if (len == null) false else { pos += len; true }
            }
            5 -> if (4 <= end - pos) { pos += 4; true } else { pos = end; eof = true; false }
            else -> false
        }
    }

    // ---------------- 降级可读符号提取 ----------------

    private fun extractReadableTokens(bytes: ByteArray): String {
        val tokens = LinkedHashSet<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.length >= 4) {
                val s = cur.toString().trim()
                if (s.length >= 4) {
                    val relevant = s.contains(".so") || s.contains("SIG") || s.contains("SEGV") ||
                        s.contains("signal", true) || s.contains("doubao", true) ||
                        s.contains("lspatch", true) || s.contains("bytedance", true) ||
                        s.contains("metasec", true) || s.contains("abort", true) ||
                        s.contains("art", true) || s.contains("jni", true) ||
                        s.startsWith("/") || s.contains("::")
                    if (relevant) tokens.add(s.take(160))
                }
            }
            cur.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) cur.append(c.toChar()) else flush()
            if (tokens.size >= 40) break
        }
        flush()
        if (tokens.isEmpty()) return "  (二进制Trace，共 ${bytes.size} B，未提取到关键符号)"
        return ("  (二进制Trace降级符号，共 ${bytes.size} B)\n  " + tokens.joinToString(" | ")).take(MAX_TRACE_CHARS)
    }

    private fun readAtMost(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
