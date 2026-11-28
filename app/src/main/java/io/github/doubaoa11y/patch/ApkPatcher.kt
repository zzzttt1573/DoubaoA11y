package io.github.doubaoa11y.patch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import io.github.doubaoa11y.ConfigSpec
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 本地 LSPatch 集成模式修补引擎（基于 JingMatrix/LSPatch v1.2-492 运行时资产）。
 *
 * 特点：
 * 1. 全程在用户手机本地流式处理，不分发任何豆包安装包。
 * 2. 首次修补嵌入本模块并写入本机持久化密钥签名；后续本模块更新时，直接热替换
 *    assets/lspatch/modules/ 下的模块 APK 并用同一密钥重签名，可覆盖安装、不丢词库。
 * 3. 完整支持单 APK 及应用商店分发的 Split APKs。
 */
object ApkPatcher {
    private const val LSPATCH_DIR = "assets/lspatch/"
    private const val CONFIG_PATH = "assets/lspatch/config.json"
    private const val LOADER_DEX_PATH = "assets/lspatch/loader.dex"
    private const val METALOADER_DEX_PATH = "assets/lspatch/metaloader.dex"
    private const val ORIGIN_APK_PATH = "assets/lspatch/origin.apk"
    private const val MODULE_DIR_PATH = "assets/lspatch/modules/"
    private const val PROXY_FACTORY = "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub"
    private const val EMBEDDED_VER_ENTRY = "assets/doubaoa11y_embedded_version.txt"
    private const val EMBEDDED_FP_ENTRY = "assets/doubaoa11y_embedded_fp.txt"

    private val LSPATCH_SO_ASSETS = listOf(
        "assets/lspatch/so/arm64-v8a/liblspatch.so",
        "assets/lspatch/so/armeabi-v7a/liblspatch.so"
    )

    /** 注入到修补后豆包清单的读屏包可见性，避免 Android 11+ 拦截天坦/解说/保益/点明接口 */
    private val READER_QUERY_PACKAGES = listOf(
        "com.android.tback",
        "com.nirenr.talkman",
        "com.bjbyhd.voiceback",
        "com.bjbyhd.screenreader",
        "com.dianming.phoneapp"
    )

    // Android framework 资源 ID（固定不变）
    private const val RES_ID_NAME = 0x01010003
    private const val RES_ID_VALUE = 0x01010024
    private const val RES_ID_APP_COMPONENT_FACTORY = 0x0101057a
    private const val RES_ID_USE_EMBEDDED_DEX = 0x0101059e

    /** LSPatch v1.2-492 运行时元信息 */
    private const val LSP_API_CODE = 93
    private const val LSP_VERSION_CODE = 492
    private const val LSP_VERSION_NAME = "1.2"
    private const val LSP_CORE_VERSION_CODE = 7024
    private const val LSP_CORE_VERSION_NAME = "1.9.2"

    enum class PatchKind {
        NOT_INSTALLED,
        ORIGINAL,
        LOCAL_INTEGRATED,
        OTHER_PATCHED
    }

    data class DoubaoStatus(
        val kind: PatchKind,
        val versionCode: Long = 0L,
        val versionName: String = "",
        val supported: Boolean = false,
        val baseApk: File? = null,
        val splitApks: List<File> = emptyList(),
        val embeddedModuleVersion: Int = -1,
        val embeddedModuleMatchesCurrent: Boolean = false,
        val sourceIdentity: String = "",
        val signedByLocalKey: Boolean = false
    )

    data class PreparedOutput(
        val dir: File,
        val baseApk: File,
        val splitApks: List<File>,
        val isHotUpdate: Boolean,
        val doubaoVersionCode: Long,
        val doubaoVersionName: String,
        val moduleVersionCode: Int,
        val sourceSplitCount: Int
    ) {
        val singleApk get() = splitApks.isEmpty() && sourceSplitCount == 0
    }

    fun outputDir(context: Context): File = File(context.cacheDir, "patched_doubao")

    private fun pointer(context: Context) = File(outputDir(context), "current")

    @Synchronized
    fun clearPrepared(context: Context, prepared: PreparedOutput) {
        val current = pointer(context)
        if (current.isFile && current.readText() == prepared.dir.name) current.delete()
        prepared.dir.deleteRecursively()
    }

    fun confirmUninstall(prepared: PreparedOutput) {
        File(prepared.dir, "verified").writeText("ok")
    }

    fun loadPrepared(context: Context, status: DoubaoStatus): PreparedOutput? {
        return try {
            val generation = pointer(context).readText()
            val dir = File(outputDir(context), generation)
            val j = JSONObject(File(dir, "meta.json").readText())
            if (j.getString("moduleDex") != computeDexFingerprint(File(context.applicationInfo.sourceDir))) return null
            if (status.kind == PatchKind.NOT_INSTALLED) {
                if (!File(dir, "verified").isFile || j.getBoolean("isHotUpdate")) return null
            } else if (j.getString("sourceIdentity") != status.sourceIdentity) return null
            val modVer = j.optInt("moduleVersionCode", -1)
            if (modVer != ConfigSpec.MODULE_VERSION_CODE) return null
            val base = File(dir, j.getString("baseName"))
            if (!base.isFile) return null
            val splits = mutableListOf<File>()
            val arr = j.optJSONArray("splitNames")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val f = File(dir, arr.getString(i))
                    if (!f.isFile) return null
                    splits.add(f)
                }
            }
            val sizes = j.getJSONArray("sizes")
            val files = listOf(base) + splits
            if (sizes.length() != files.size || files.withIndex().any { (i, f) -> f.length() <= 0 || f.length() != sizes.getLong(i) }) return null
            PreparedOutput(
                dir = dir,
                baseApk = base,
                splitApks = splits,
                isHotUpdate = j.optBoolean("isHotUpdate", false),
                doubaoVersionCode = j.optLong("doubaoVersionCode", 0L),
                doubaoVersionName = j.optString("doubaoVersionName", ""),
                sourceSplitCount = j.getInt("sourceSplitCount"),
                moduleVersionCode = modVer
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 探测手机上当前豆包输入法的安装与修补状态 */
    fun inspect(context: Context): DoubaoStatus {
        val pkgInfo = getDoubaoPackageInfo(context) ?: return DoubaoStatus(PatchKind.NOT_INSTALLED)
        val appInfo = pkgInfo.applicationInfo ?: return DoubaoStatus(PatchKind.NOT_INSTALLED)
        val vCode = if (Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
        val vName = pkgInfo.versionName ?: ""
        val supported = ConfigSpec.SUPPORTED_VERSIONS.containsKey(vCode)
        val baseApk = File(appInfo.sourceDir)
        val splitApks = appInfo.splitSourceDirs?.map { File(it) } ?: emptyList()

        val localKey = LocalKeystore.loadIfExists(context)
        val installedSha256 = getInstalledCertSha256(pkgInfo)
        val signedByLocal = localKey != null && installedSha256 != null &&
            localKey.certSha256.contentEquals(installedSha256)

        var hasLspatchConfig = false
        var useManager = true
        var embeddedVer = -1
        var embeddedMatches = false
        val currentFp = computeDexFingerprint(File(context.applicationInfo.sourceDir))
        if (baseApk.isFile) {
            try {
                ZipFile(baseApk).use { zf ->
                    val cfgEntry = zf.getEntry(CONFIG_PATH)
                    if (cfgEntry != null) {
                        hasLspatchConfig = true
                        val cfgText = zf.getInputStream(cfgEntry).bufferedReader().use { it.readText() }
                        val j = JSONObject(cfgText)
                        useManager = j.optBoolean("useManager", false)
                    }
                    val modEntry = zf.getEntry("$MODULE_DIR_PATH${context.packageName}.apk")
                    if (modEntry != null) {
                        val probe = probeEmbeddedModule(zf, modEntry)
                        embeddedVer = probe.first
                        val embeddedFp = probe.second
                        embeddedMatches = currentFp != null && embeddedFp != null &&
                            currentFp == embeddedFp &&
                            (embeddedVer == -1 || embeddedVer == ConfigSpec.MODULE_VERSION_CODE)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val kind = when {
            !hasLspatchConfig -> PatchKind.ORIGINAL
            signedByLocal && !useManager -> PatchKind.LOCAL_INTEGRATED
            else -> PatchKind.OTHER_PATCHED
        }

        return DoubaoStatus(
            kind = kind,
            versionCode = vCode,
            versionName = vName,
            supported = supported,
            baseApk = baseApk,
            splitApks = splitApks,
            embeddedModuleVersion = embeddedVer,
            embeddedModuleMatchesCurrent = embeddedMatches,
            sourceIdentity = JSONObject().apply {
                put("package", pkgInfo.packageName)
                put("version", vCode)
                put("updated", pkgInfo.lastUpdateTime)
                put("certificate", installedSha256?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                put("files", org.json.JSONArray((listOf(baseApk) + splitApks).map {
                    listOf(it.absolutePath, it.length(), it.lastModified()).joinToString("|")
                }))
            }.toString(),
            signedByLocalKey = signedByLocal
        )
    }

    /**
     * 生成修补后的豆包 APK（自动判断首次完整修补还是模块升级热替换）。
     * 必须在后台线程调用。
     */
    fun patchDoubao(
        context: Context,
        onProgress: (String) -> Unit
    ): PreparedOutput {
        val st = inspect(context)
        val baseApk = st.baseApk ?: error("未检测到已安装的豆包输入法")
        if (!baseApk.isFile || !baseApk.canRead()) {
            error("无法读取豆包输入法安装包")
        }
        if (st.kind == PatchKind.OTHER_PATCHED) {
            error("当前豆包已被其他方式修补，请先卸载后从应用商店安装官方版豆包输入法，再进行一键修补")
        }

        onProgress("正在准备本机签名密钥…")
        val material = LocalKeystore.loadOrCreate(context)

        val outDir = File(outputDir(context), java.util.UUID.randomUUID().toString())
        check(outDir.mkdirs()) { "无法创建修补目录" }
        val slimModule = File(outDir, "module_slim.apk")
        return try {

            onProgress("正在准备无障碍模块副本…")
            buildSlimModuleApk(context, slimModule)

            val t0 = System.currentTimeMillis()
            PatchLog.i(
                context,
                "开始修补豆包：kind=${st.kind} version=${st.versionName}(${st.versionCode}) " +
                    "base=${baseApk.length() / (1024 * 1024)}MB splits=${st.splitApks.size} " +
                    "device=${PatchLog.deviceSummary(context)}"
            )
            val out = if (st.kind == PatchKind.LOCAL_INTEGRATED) {
                hotUpdateModule(context, st, baseApk, slimModule, material, outDir, onProgress)
            } else {
                fullPatch(context, st, baseApk, slimModule, material, outDir, onProgress)
            }
            PatchLog.i(
                context,
                "修补完成（耗时 ${(System.currentTimeMillis() - t0) / 1000.0}s）：" +
                    "hotUpdate=${out.isHotUpdate} base=${out.baseApk.length() / (1024 * 1024)}MB splits=${out.splitApks.size}"
            )
            check(inspect(context).sourceIdentity == st.sourceIdentity) { "豆包已变更，请重新修补" }
            savePreparedMeta(context, out, st)
            out
        } catch (t: Throwable) {
            outDir.deleteRecursively()
            PatchLog.e(context, "修补豆包失败", t)
            throw t
        } finally {
            slimModule.delete()
        }
    }

    /**
     * 首次完整修补：官方豆包 → LSPatch 集成版豆包（含 sigBypassLevel=2 与所有 Split 重签名）。
     */
    private fun fullPatch(
        context: Context,
        st: DoubaoStatus,
        origBaseApk: File,
        slimModule: File,
        material: LocalKeystore.Material,
        outDir: File,
        onProgress: (String) -> Unit
    ): PreparedOutput {
        onProgress("正在读取豆包原始签名…")
        val origSigHex = readOriginalSignatureHex(context, origBaseApk)

        onProgress("正在修改豆包应用清单…")
        val manifestResult = patchManifest(origBaseApk, origSigHex)

        onProgress("正在写入修补组件与无障碍模块…")
        val unsignedBase = File(outDir, "base_unsigned.apk")
        val signedBase = File(outDir, "base.apk")
        try {
            repackBaseApkFull(
                context = context,
                origBaseApk = origBaseApk,
                patchedManifest = manifestResult.manifestBytes,
                configJsonBytes = manifestResult.configJsonBytes,
                slimModuleApk = slimModule,
                outFile = unsignedBase
            )
            onProgress("正在为修补后的豆包签名…")
            signApk(unsignedBase, signedBase, material)
        } finally {
            unsignedBase.delete()
        }

        val signedSplits = mutableListOf<File>()
        for ((idx, split) in st.splitApks.withIndex()) {
            onProgress("正在签名分包 (${idx + 1}/${st.splitApks.size})…")
            val unsignedSplit = File(outDir, "split_${idx}_unsigned.apk")
            val signedSplit = File(outDir, "split_$idx.apk")
            try {
                stripSignatureAndAlign(split, unsignedSplit)
                signApk(unsignedSplit, signedSplit, material)
                signedSplits.add(signedSplit)
            } finally {
                unsignedSplit.delete()
            }
        }

        val prepared = PreparedOutput(
            dir = outDir,
            baseApk = signedBase,
            splitApks = signedSplits,
            isHotUpdate = false,
            doubaoVersionCode = st.versionCode,
            doubaoVersionName = st.versionName,
            moduleVersionCode = ConfigSpec.MODULE_VERSION_CODE,
            sourceSplitCount = st.splitApks.size
        )
        return prepared
    }

    /**
     * 模块升级热替换：仅替换已修补豆包里的内嵌模块 APK 并用同一把本机密钥重签名，可直接覆盖安装！
     */
    private fun hotUpdateModule(
        context: Context,
        st: DoubaoStatus,
        installedBaseApk: File,
        slimModule: File,
        material: LocalKeystore.Material,
        outDir: File,
        onProgress: (String) -> Unit
    ): PreparedOutput {
        val moduleEntryName = "$MODULE_DIR_PATH${context.packageName}.apk"
        val unsignedBase = File(outDir, "base_unsigned.apk")
        val signedBase = File(outDir, "base.apk")
        onProgress("正在将新版模块同步进豆包…")
        try {
            ZipFile(installedBaseApk).use { zf ->
                // 若旧修补包的 config.json 中 originalSignature 非 Hex（兼容历史修补包），自动从 origin.apk 修复
                var fixedConfigBytes: ByteArray? = null
                var fixedManifestBytes: ByteArray? = null
                val cfgEntry = zf.getEntry(CONFIG_PATH)
                val originEntry = zf.getEntry(ORIGIN_APK_PATH)
                if (cfgEntry != null && originEntry != null) {
                    val cfgJson = JSONObject(zf.getInputStream(cfgEntry).bufferedReader().use { it.readText() })
                    val oldSig = cfgJson.optString("originalSignature", "")
                    if (oldSig.isNotEmpty() && !isValidHex(oldSig)) {
                        val tmpOrigin = File(outDir, "origin_probe.apk")
                        try {
                            zf.getInputStream(originEntry).use { input ->
                                tmpOrigin.outputStream().use { input.copyTo(it, 64 * 1024) }
                            }
                            val realHex = readOriginalSignatureHex(context, tmpOrigin)
                            cfgJson.put("originalSignature", realHex)
                            val newCfgBytes = cfgJson.toString().toByteArray(Charsets.UTF_8)
                            fixedConfigBytes = newCfgBytes
                            fixedManifestBytes = updateManifestLspatchMeta(zf, newCfgBytes)
                        } finally {
                            tmpOrigin.delete()
                        }
                    }
                }
                if (fixedManifestBytes == null) {
                    fixedManifestBytes = ensureManifestQueriesFromZip(zf)
                }

                AlignedZipWriter(unsignedBase).use { out ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (isSignatureEntry(e.name) || e.name == moduleEntryName) continue
                        if (fixedConfigBytes != null && e.name == CONFIG_PATH) {
                            out.writeDeflatedBytes(CONFIG_PATH, fixedConfigBytes)
                        } else if (fixedManifestBytes != null && e.name == "AndroidManifest.xml") {
                            out.writeDeflatedBytes("AndroidManifest.xml", fixedManifestBytes)
                        } else {
                            out.copyEntry(zf, e)
                        }
                    }
                    out.writeStoredFile(moduleEntryName, slimModule, align = 4)
                }
            }
            onProgress("正在使用本机密钥重签名…")
            signApk(unsignedBase, signedBase, material)
        } finally {
            unsignedBase.delete()
        }

        val prepared = PreparedOutput(
            dir = outDir,
            baseApk = signedBase,
            splitApks = emptyList(), // 覆盖更新走 MODE_INHERIT_EXISTING，复用已安装的同签名分包
            isHotUpdate = true,
            doubaoVersionCode = st.versionCode,
            doubaoVersionName = st.versionName,
            moduleVersionCode = ConfigSpec.MODULE_VERSION_CODE,
            sourceSplitCount = st.splitApks.size
        )
        return prepared
    }

    private fun savePreparedMeta(context: Context, p: PreparedOutput, status: DoubaoStatus) {
        val j = JSONObject().apply {
            put("sourceIdentity", status.sourceIdentity)
            put("sourceSplitCount", p.sourceSplitCount)
            put("moduleDex", requireNotNull(computeDexFingerprint(File(context.applicationInfo.sourceDir))))
            put("sizes", org.json.JSONArray((listOf(p.baseApk) + p.splitApks).map { it.length() }))
            put("baseName", p.baseApk.name)
            put("isHotUpdate", p.isHotUpdate)
            put("doubaoVersionCode", p.doubaoVersionCode)
            put("doubaoVersionName", p.doubaoVersionName)
            put("moduleVersionCode", p.moduleVersionCode)
            put("splitNames", org.json.JSONArray(p.splitApks.map { it.name }))
        }
        File(p.dir, "meta.json").writeText(j.toString())
        val next = File(p.dir, "publish")
        next.writeText(p.dir.name)
        synchronized(this) { android.system.Os.rename(next.path, pointer(context).path) }
    }

    // ---------------- Manifest 与配置构造 ----------------

    private data class ManifestPatchResult(
        val manifestBytes: ByteArray,
        val configJsonBytes: ByteArray
    )

    private fun readManifest(zf: ZipFile, entry: ZipEntry): AndroidManifestBlock {
        val raw = zf.getInputStream(entry).use { it.readBytes() }
        return AndroidManifestBlock().apply { readBytes(ByteArrayInputStream(raw)) }
    }

    private fun patchManifest(origBaseApk: File, origSigB64: String): ManifestPatchResult {
        val manifest = ZipFile(origBaseApk).use { zf ->
            val e = zf.getEntry("AndroidManifest.xml") ?: error("豆包安装包缺少 AndroidManifest.xml")
            readManifest(zf, e)
        }
        val app = manifest.applicationElement ?: error("AndroidManifest.xml 缺少 application 节点")

        val origFactory = app.searchAttributeByResourceId(RES_ID_APP_COMPONENT_FACTORY)?.valueAsString
        val configJson = buildPatchConfigJson(origFactory, origSigB64)
        val configBytes = configJson.toByteArray(Charsets.UTF_8)
        val configBase64 = Base64.encodeToString(configBytes, Base64.NO_WRAP)

        app.getOrCreateAndroidAttribute("appComponentFactory", RES_ID_APP_COMPONENT_FACTORY)
            .setValueAsString(PROXY_FACTORY)
        app.searchAttributeByResourceId(RES_ID_USE_EMBEDDED_DEX)?.setValueAsBoolean(false)

        // 写入 <meta-data android:name="lspatch" android:value="..." />
        val meta = app.newElement("meta-data")
        meta.getOrCreateAndroidAttribute("name", RES_ID_NAME).setValueAsString("lspatch")
        meta.getOrCreateAndroidAttribute("value", RES_ID_VALUE).setValueAsString(configBase64)

        ensureReaderQueries(manifest)

        manifest.refresh()
        return ManifestPatchResult(manifest.bytes, configBytes)
    }

    private fun buildPatchConfigJson(origFactory: String?, origSigB64: String): String {
        val lspConfig = JSONObject().apply {
            put("API_CODE", LSP_API_CODE)
            put("VERSION_CODE", LSP_VERSION_CODE)
            put("VERSION_NAME", LSP_VERSION_NAME)
            put("CORE_VERSION_CODE", LSP_CORE_VERSION_CODE)
            put("CORE_VERSION_NAME", LSP_CORE_VERSION_NAME)
        }
        return JSONObject().apply {
            put("useManager", false)
            put("debuggable", false)
            put("sigBypassLevel", 2)
            put("originalSignature", origSigB64)
            if (origFactory != null) put("appComponentFactory", origFactory)
            else put("appComponentFactory", JSONObject.NULL)
            put("lspConfig", lspConfig)
        }.toString()
    }

    // ---------------- ZIP 重打包与瘦身 ----------------

    private fun repackBaseApkFull(
        context: Context,
        origBaseApk: File,
        patchedManifest: ByteArray,
        configJsonBytes: ByteArray,
        slimModuleApk: File,
        outFile: File
    ) {
        val dexPattern = Regex("""^classes(\d*)\.dex$""")
        var maxDex = 1
        ZipFile(origBaseApk).use { zf ->
            AlignedZipWriter(outFile).use { out ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name
                    if (isSignatureEntry(name)) continue
                    val m = dexPattern.matchEntire(name)
                    if (m != null) {
                        val n = if (m.groupValues[1].isEmpty()) 1 else m.groupValues[1].toIntOrNull() ?: 1
                        if (n > maxDex) maxDex = n
                    }
                    if (name == "AndroidManifest.xml") {
                        out.writeDeflatedBytes("AndroidManifest.xml", patchedManifest)
                    } else {
                        out.copyEntry(zf, e)
                    }
                }

                // 注入 metaloader.dex 为下一个 classesN.dex
                val metaLoaderBytes = context.assets.open(METALOADER_DEX_PATH.removePrefix("assets/")).use { it.readBytes() }
                out.writeDeflatedBytes("classes${maxDex + 1}.dex", metaLoaderBytes)

                // 注入 config.json 与 loader.dex
                out.writeDeflatedBytes(CONFIG_PATH, configJsonBytes)
                val loaderBytes = context.assets.open(LOADER_DEX_PATH.removePrefix("assets/")).use { it.readBytes() }
                out.writeDeflatedBytes(LOADER_DEX_PATH, loaderBytes)

                // 注入 liblspatch.so（必须 STORED 且 4096 页对齐，供 metaloader 直接 dlopen）
                for (soPath in LSPATCH_SO_ASSETS) {
                    val bytes = context.assets.open(soPath.removePrefix("assets/")).use { it.readBytes() }
                    out.writeStoredBytes(soPath, bytes, align = 4096)
                }

                // 注入原始豆包 APK（供 sigBypassLevel=2 openat 重定向）
                out.writeStoredFile(ORIGIN_APK_PATH, origBaseApk, align = 4)

                // 注入瘦身后的无障碍模块 APK
                out.writeStoredFile("$MODULE_DIR_PATH${context.packageName}.apk", slimModuleApk, align = 4)
            }
        }
    }

    /**
     * 瘦身本模块 APK：剔除 assets/lspatch/ 修补引擎资产，避免套娃膨胀；
     * 同时写入 assets/doubaoa11y_embedded_version.txt 方便后续检测内嵌模块版本。
     */
    private fun buildSlimModuleApk(context: Context, outFile: File) {
        val selfApk = File(context.applicationInfo.sourceDir)
        val selfFp = computeDexFingerprint(selfApk) ?: ""
        ZipFile(selfApk).use { zf ->
            AlignedZipWriter(outFile).use { out ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.startsWith(LSPATCH_DIR) ||
                        e.name == EMBEDDED_VER_ENTRY ||
                        e.name == EMBEDDED_FP_ENTRY ||
                        isSignatureEntry(e.name)
                    ) continue
                    out.copyEntry(zf, e)
                }
                out.writeDeflatedBytes(
                    EMBEDDED_VER_ENTRY,
                    ConfigSpec.MODULE_VERSION_CODE.toString().toByteArray(Charsets.UTF_8)
                )
                if (selfFp.isNotEmpty()) {
                    out.writeDeflatedBytes(
                        EMBEDDED_FP_ENTRY,
                        selfFp.toByteArray(Charsets.UTF_8)
                    )
                }
            }
        }
    }

    private fun stripSignatureAndAlign(srcApk: File, outFile: File) {
        ZipFile(srcApk).use { zf ->
            AlignedZipWriter(outFile).use { out ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (isSignatureEntry(e.name)) continue
                    out.copyEntry(zf, e)
                }
            }
        }
    }

    private fun isSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC")
    }

    // ---------------- 签名与证书工具 ----------------

    private fun signApk(unsignedFile: File, signedFile: File, material: LocalKeystore.Material) {
        val config = ApkSigner.SignerConfig.Builder(
            "doubaoa11y",
            material.privateKey,
            listOf(material.certificate)
        ).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(unsignedFile)
            .setOutputApk(signedFile)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            // 使用 21 强制同时写出 V1 + V2 + V3 签名，避免旧版安卓或国产安全扫描器要求 V1 校验失败
            .setMinSdkVersion(21)
            .build()
            .sign()
    }

    private fun updateManifestLspatchMeta(zf: ZipFile, newConfigBytes: ByteArray): ByteArray {
        val e = zf.getEntry("AndroidManifest.xml") ?: error("缺少 AndroidManifest.xml")
        val manifest = readManifest(zf, e)
        val app = manifest.applicationElement ?: error("AndroidManifest.xml 缺少 application 节点")
        val configBase64 = Base64.encodeToString(newConfigBytes, Base64.NO_WRAP)
        var updated = false
        for (child in app.listElements("meta-data")) {
            val nameAttr = child.searchAttributeByResourceId(RES_ID_NAME)
            if (nameAttr?.valueAsString == "lspatch") {
                child.getOrCreateAndroidAttribute("value", RES_ID_VALUE).setValueAsString(configBase64)
                updated = true
                break
            }
        }
        if (!updated) {
            val meta = app.newElement("meta-data")
            meta.getOrCreateAndroidAttribute("name", RES_ID_NAME).setValueAsString("lspatch")
            meta.getOrCreateAndroidAttribute("value", RES_ID_VALUE).setValueAsString(configBase64)
        }
        ensureReaderQueries(manifest)
        manifest.refresh()
        return manifest.bytes
    }

    private fun ensureManifestQueriesFromZip(zf: ZipFile): ByteArray? {
        val e = zf.getEntry("AndroidManifest.xml") ?: return null
        val manifest = readManifest(zf, e)
        if (!ensureReaderQueries(manifest)) return null
        manifest.refresh()
        return manifest.bytes
    }

    private fun ensureReaderQueries(manifest: AndroidManifestBlock): Boolean {
        val root = manifest.manifestElement ?: return false
        val existing = HashSet<String>()
        var queriesEl = root.getElement("queries")
        for (q in root.listElements("queries")) {
            if (queriesEl == null) queriesEl = q
            for (pkgEl in q.listElements("package")) {
                pkgEl.searchAttributeByResourceId(RES_ID_NAME)?.valueAsString?.let { existing.add(it) }
            }
        }
        var changed = false
        for (pkg in READER_QUERY_PACKAGES) {
            if (pkg in existing) continue
            val q = queriesEl ?: root.newElement("queries").also { queriesEl = it }
            val pkgEl = q.newElement("package")
            pkgEl.getOrCreateAndroidAttribute("name", RES_ID_NAME).setValueAsString(pkg)
            changed = true
        }
        return changed
    }

    private fun isValidHex(s: String): Boolean {
        if (s.length % 2 != 0) return false
        for (c in s) {
            val ok = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            if (!ok) return false
        }
        return true
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun readOriginalSignatureHex(context: Context, apkFile: File): String {
        try {
            val result = ApkVerifier.Builder(apkFile).build().verify()
            val cert = result.signerCertificates.firstOrNull()
            if (cert != null) {
                return toHex(cert.encoded)
            }
        } catch (_: Throwable) {
        }
        val pkgInfo = getDoubaoPackageInfo(context)
        val der = getInstalledCertDer(pkgInfo) ?: error("无法读取豆包输入法的原始签名证书")
        return toHex(der)
    }

    private fun getDoubaoPackageInfo(context: Context): PackageInfo? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(ConfigSpec.TARGET_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ConfigSpec.TARGET_PACKAGE, PackageManager.GET_SIGNATURES)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getInstalledCertDer(pkgInfo: PackageInfo?): ByteArray? {
        if (pkgInfo == null) return null
        return if (Build.VERSION.SDK_INT >= 28) {
            val si = pkgInfo.signingInfo ?: return null
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            sigs?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.firstOrNull()?.toByteArray()
        }
    }

    private fun getInstalledCertSha256(pkgInfo: PackageInfo?): ByteArray? {
        val der = getInstalledCertDer(pkgInfo) ?: return null
        return LocalKeystore.sha256(der)
    }

    /** 计算 APK 中所有顶层 classes*.dex 的组合 SHA-256 指纹，用于精准判断是否为同一模块构建 */
    fun computeDexFingerprint(apkFile: File): String? {
        if (!apkFile.isFile) return null
        return try {
            ZipFile(apkFile).use { computeDexFingerprint(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeDexFingerprint(zf: ZipFile): String? {
        val dexPattern = Regex("""^classes\d*\.dex$""")
        val dexNames = mutableListOf<String>()
        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (dexPattern.matches(name)) dexNames.add(name)
        }
        if (dexNames.isEmpty()) return null
        dexNames.sort()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        for (name in dexNames) {
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(0)
            val e = zf.getEntry(name) ?: continue
            zf.getInputStream(e).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
        }
        return toHex(md.digest())
    }

    /** 从豆包内嵌的模块 APK 中读取写入的版本号与 DEX 指纹 */
    private fun probeEmbeddedModule(outerZip: ZipFile, modEntry: ZipEntry): Pair<Int, String?> {
        return try {
            val tmp = File.createTempFile("mod_probe_", ".apk")
            try {
                outerZip.getInputStream(modEntry).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                ZipFile(tmp).use { inner ->
                    val ver = inner.getEntry(EMBEDDED_VER_ENTRY)?.let { ve ->
                        inner.getInputStream(ve).bufferedReader().use { it.readText().trim().toIntOrNull() }
                    } ?: -1
                    val fp = inner.getEntry(EMBEDDED_FP_ENTRY)?.let { fe ->
                        inner.getInputStream(fe).bufferedReader().use { it.readText().trim() }.ifEmpty { null }
                    } ?: computeDexFingerprint(inner)
                    ver to fp
                }
            } finally {
                tmp.delete()
            }
        } catch (_: Throwable) {
            -1 to null
        }
    }

    // ---------------- 精确流式对齐 ZIP 写入器 ----------------

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private class AlignedZipWriter(file: File) : AutoCloseable {
        private val counter = CountingOutputStream(BufferedOutputStream(FileOutputStream(file), 64 * 1024))
        private val zos = ZipOutputStream(counter)
        private val buf = ByteArray(64 * 1024)

        fun copyEntry(zf: ZipFile, src: ZipEntry) {
            if (src.isDirectory) return
            val mustStore = src.method == ZipEntry.STORED || src.name == "resources.arsc"
            if (mustStore) {
                val align = if (src.name.endsWith(".so")) 4096 else 4
                val size = src.size
                val crc = src.crc
                if (size >= 0 && crc >= 0) {
                    putStoredHeader(src.name, size, crc, align)
                    zf.getInputStream(src).use { copyStream(it) }
                    zos.closeEntry()
                } else {
                    val bytes = zf.getInputStream(src).use { it.readBytes() }
                    writeStoredBytes(src.name, bytes, align)
                }
            } else {
                val dst = ZipEntry(src.name).apply {
                    method = ZipEntry.DEFLATED
                    time = 0L
                }
                zos.putNextEntry(dst)
                zf.getInputStream(src).use { copyStream(it) }
                zos.closeEntry()
            }
        }

        fun writeDeflatedBytes(name: String, data: ByteArray) {
            val e = ZipEntry(name).apply {
                method = ZipEntry.DEFLATED
                time = 0L
            }
            zos.putNextEntry(e)
            zos.write(data)
            zos.closeEntry()
        }

        fun writeStoredBytes(name: String, data: ByteArray, align: Int) {
            val crc = CRC32().apply { update(data) }.value
            putStoredHeader(name, data.size.toLong(), crc, align)
            zos.write(data)
            zos.closeEntry()
        }

        fun writeStoredFile(name: String, file: File, align: Int) {
            val crc = CRC32()
            FileInputStream(file).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            putStoredHeader(name, file.length(), crc.value, align)
            FileInputStream(file).use { copyStream(it) }
            zos.closeEntry()
        }

        private fun putStoredHeader(name: String, size: Long, crc: Long, align: Int) {
            zos.flush()
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val dataOffsetNoExtra = counter.count + 30L + nameLen
            val rem = (dataOffsetNoExtra % align).toInt()
            val pad = if (rem == 0) 0 else (align - rem)
            val e = ZipEntry(name).apply {
                method = ZipEntry.STORED
                this.size = size
                this.compressedSize = size
                this.crc = crc
                this.time = 0L
                if (pad > 0) this.extra = ByteArray(pad)
            }
            zos.putNextEntry(e)
        }

        private fun copyStream(input: InputStream) {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zos.write(buf, 0, n)
            }
        }

        override fun close() {
            zos.close()
        }
    }
}
