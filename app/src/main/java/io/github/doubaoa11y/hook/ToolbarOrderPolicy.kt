package io.github.doubaoa11y.hook

/**
 * 顶部工具栏按钮顺序合并纯规则层（脱离 Android/Xposed，便于 JVM 回归验证）：
 * 同时产出“界面展示用原生+模块混排顺序”与“写回豆包的纯原生按钮顺序”。
 */
internal object ToolbarOrderPolicy {
    @JvmStatic
    fun mergeInitialOrder(
        nativeVisibleWithOrder: List<Pair<String, Int>>,
        enabledCustomKeys: List<String>,
        savedOrder: List<String>?
    ): MutableList<String> {
        val visibleSet = LinkedHashSet<String>()
        for (k in nativeVisibleWithOrder.sortedBy { it.second }.map { it.first }) {
            if (k.isNotEmpty()) visibleSet.add(k)
        }
        for (k in enabledCustomKeys) {
            if (k.isNotEmpty()) visibleSet.add(k)
        }
        val out = ArrayList<String>(visibleSet.size)
        if (!savedOrder.isNullOrEmpty()) {
            for (k in savedOrder) {
                if (k in visibleSet && k !in out) out.add(k)
            }
        }
        for (k in visibleSet) {
            if (k !in out) out.add(k)
        }
        return out
    }

    @JvmStatic
    fun nativeOnly(mixedOrder: List<String>, customKeys: Set<String>): Array<String> =
        mixedOrder.filter { it !in customKeys }.toTypedArray()

    @JvmStatic
    fun applyToggle(current: List<String>, name: String, visible: Boolean): MutableList<String> {
        val out = current.toMutableList()
        if (visible) {
            if (name !in out) out.add(name)
        } else {
            out.remove(name)
        }
        return out
    }
}
