package io.github.doubaoa11y.hook

/**
 * 把编辑面板挂到豆包的“单手模式”工具上（用户选的）：工具栏按钮、定制工具栏、菜单里的单手模式都改成编辑面板。
 * 菜单里的“移动光标”也打开编辑面板。豆包的工具栏列表里没有“移动光标”，所以不能直接加它。
 */
object EditHooks {
    fun install(cl: ClassLoader) {
        // 工具栏按钮点击：CandidateIdleView 里唯一的 (String 按钮标识, boolean) -> void 方法（1.5.3 叫 N，1.5.4 叫 Q）
        val idle = Hooker.findClassQuiet("com.bytedance.common_biz.tool_bar.view.views.CandidateIdleView", cl)
        val click = idle?.declaredMethods?.filter {
            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(String::class.java, Boolean::class.javaPrimitiveType))
        }.orEmpty()
        if (click.size == 1) {
            L.i("工具栏点击方法：${click[0].name}")
            Hooker.hookMember(click[0], SafeHook("CandidateIdleView.${click[0].name}", before = { p ->
                if ((p.args[0] == "btn_toolbar_one_hand" || p.args[0] == "btn_toolbar_move_cursor") && Config.b("editPanel")) {
                    if (EditPanel.open()) p.result = null
                }
            }))
        } else {
            L.w("找不到工具栏点击方法，候选 ${click.size} 个，编辑面板只能从菜单打开")
        }
        // 菜单（工具箱）里的“单手模式”“移动光标”
        val action = Hooker.findClassQuiet("com.bytedance.common_biz.tool_box.ToolboxItemAction", cl) ?: return
        val handler = Hooker.findClassQuiet("com.bytedance.android.input.keyboard.areacontrol.v", cl)
        val old = handler?.let { de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(it, "invoke", action) }
        if (old != null) {
            // 1.5.3：菜单点击的处理函数 invoke(ToolboxItemAction)，返回 Kotlin Unit
            Hooker.hookMember(old, SafeHook("Toolbox.invoke", before = { p ->
                if (wantsEditPanel(p.args[0])) {
                    val unit = unitOf((p.method as java.lang.reflect.Method).returnType) ?: return@SafeHook
                    p.result = unit
                    EditPanel.closeToolboxThenOpen()
                }
            }))
            return
        }
        // 1.5.4 起：ToolboxKeyboardView 的静态方法 (ToolboxKeyboardView, ToolboxItemAction) -> void 把点击转给处理函数
        val view = Hooker.findClassQuiet("com.bytedance.common_biz.tool_box.ToolboxKeyboardView", cl) ?: return
        val dispatch = view.declaredMethods.filter {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(view, action))
        }
        if (dispatch.size != 1) {
            L.w("找不到菜单点击方法，候选 ${dispatch.size} 个，菜单里的单手模式不会改成编辑面板")
            return
        }
        L.i("菜单点击方法：${dispatch[0].name}")
        Hooker.hookMember(dispatch[0], SafeHook("Toolbox.${dispatch[0].name}", before = { p ->
            if (wantsEditPanel(p.args[1])) {
                p.result = null
                EditPanel.closeToolboxThenOpen()
            }
        }))
    }

    private fun wantsEditPanel(a: Any?): Boolean {
        val n = (a as? Enum<*>)?.name
        return (n == "ONE_HAND" || n == "MOVE_CURSOR") && Config.b("editPanel")
    }

    /** 豆包混淆过的 Kotlin Unit：返回类型里那个单例 */
    private fun unitOf(c: Class<*>): Any? = try {
        c.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == c }?.apply { isAccessible = true }?.get(null)
    } catch (_: Throwable) {
        null
    }

    private const val KEY = "btn_toolbar_move_cursor"

    /**
     * 豆包的“定制工具栏”里没有“移动光标”（它只在菜单里），这里直接调用豆包定制工具栏用的原生接口把它加上或去掉。
     * 返回给设置页朗读的结果。
     */
    fun setToolbar(add: Boolean): String {
        return try {
            val cls = JniHooks.jniClass ?: return "失败：找不到豆包的键盘接口"
            val j = de.robv.android.xposed.XposedHelpers.callStaticMethod(cls, "getKeyboardJni") ?: return "失败：键盘接口为空"
            de.robv.android.xposed.XposedHelpers.callMethod(j, "enterCustomizeToolbar")
            de.robv.android.xposed.XposedHelpers.callMethod(j, "customizeToolbarButton", add, KEY)
            de.robv.android.xposed.XposedHelpers.callMethod(j, "confirmCustomizeToolbar")
            de.robv.android.xposed.XposedHelpers.callMethod(j, "exitCustomizeToolbar")
            val cfg = de.robv.android.xposed.XposedHelpers.callMethod(j, "getToolbarConfig") as? String ?: ""
            L.i("工具栏设置（${if (add) "添加" else "移除"}编辑面板）后：$cfg")
            val visible = try {
                val arr = org.json.JSONObject(cfg).optJSONArray("buttons")
                var v = false
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("name") == KEY) v = o.optBoolean("visible", false)
                }
                v
            } catch (_: Throwable) {
                null
            }
            when {
                visible == add -> if (add) "已把编辑面板加到工具栏，收起键盘再弹出就能看到" else "已从工具栏移除编辑面板"
                visible == null -> "已发送请求，但读不到工具栏设置，请弹出键盘看看"
                else -> "豆包没有接受这个修改"
            }
        } catch (t: Throwable) {
            L.e("修改工具栏失败", t)
            "失败：${t.javaClass.simpleName}"
        }
    }
}
