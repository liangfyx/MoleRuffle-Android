package rs.ruffle

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.ViewTreeObserver
import android.widget.CheckBox
import android.widget.SeekBar
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.LinearLayout
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import java.io.DataInputStream
import java.io.File
import java.io.IOException

class PlayerActivity : GameActivity() {
    @Suppress("unused")
    // Used by Rust
    private val swfBytes: ByteArray?
        get() {
            val uri = intent.data
            if (uri?.scheme == "content") {
                try {
                    contentResolver.openInputStream(uri).use { inputStream ->
                        if (inputStream == null) {
                            return null
                        }
                        val bytes = ByteArray(inputStream.available())
                        val dataInputStream = DataInputStream(inputStream)
                        dataInputStream.readFully(bytes)
                        return bytes
                    }
                } catch (ignored: IOException) {
                }
            }
            return null
        }

    @Suppress("unused")
    // Used by Rust
    private val swfUri: String?
        get() {
            return intent.dataString
        }

    @Suppress("unused")
    // Used by Rust
    private val traceOutput: String?
        get() {
            return intent.getStringExtra("traceOutput")
        }

    companion object {
        init {
            // load the native activity
            System.loadLibrary("ruffle_android")
        }

        // ★ 内核来源(「关于」里显示)。目前只有 APK 内置一种;
        //   主人已明确"暂时不做检查更新/热更新 app",所以不再做外部 .so 覆盖那套。
        const val CORE_SOURCE: String = "内置(APK)"

        @JvmStatic
        private external fun nativeInit(crashCallback: CrashCallback)

        // MoleRuffle:版本表 —— key 为 moleverse 路径段(与平行服网页版一一对应)。
        //   ★ "official" 是**淘米官方服**:走 mole.61.com(淘米 = 61.com),不是平行服域名,
        //     所以不能拼 /moleverse/ 路径,见 gameSwfUrl() 里的分支。
        //     注:官方服 GameSer 与平行服不同(平行服 Server.xml 里是 127.0.0.1:3200 的本地桥),
        //     本地代理会自动探测真实游戏服;官方服是否已停运以实际连接结果为准。
        private val VERSIONS: List<Pair<String, String>> = listOf(
            "" to "主版本(默认·平行服)",
            "official" to "淘米官方服",
            "20090626" to "骑士版",
            "20111225" to "圣诞版",
            "20190815" to "万圣版",
            "20120128" to "新春版",
            "2025hsb" to "2025火神杯版",
            "taoyuan" to "桃源版"
        )

        private const val PARALLEL_BASE = "http://mole.61player.com"
        // 淘米官方服(61.com)。
        private const val OFFICIAL_BASE = "http://mole.61.com"
        // ★ 编译时依赖的 ruffle 内核版本(「关于」里显示)。升级 ruffle 时记得同步这里。
        private const val RUFFLE_REV = "d7231d0c (2026-07-05)"
    }

    @Suppress("unused")
    // MoleRuffle:Rust 经 JNI 读取的游戏加载地址(按设置里的版本选择拼接)。
    private fun gameSwfUrl(): String {
        val v = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("version", "") ?: ""
        return when {
            v.isNullOrEmpty() -> "$PARALLEL_BASE/Client.swf"
            // ★ 淘米官方服:换域名,不拼 moleverse 路径。
            v == "official" -> "$OFFICIAL_BASE/Client.swf"
            else -> "$PARALLEL_BASE/moleverse/$v/Client.swf"
        }
    }

    @Suppress("unused")
    // MoleRuffle:Rust 启动时读取的渲染后端偏好("vulkan" / "gl"),重启生效。
    // ★ v5.6:默认改为 GL —— 小米 Adreno 750 的 Vulkan 驱动在 encode_render_pass/set_index_buffer
    //   反复原生崩(日志7/8/9/11),GL/ANGLE 完全绕开 vulkan.adreno.so。想要更好画质可在
    //   「图像设置 → 渲染后端」切回 Vulkan(设备驱动稳定时)。
    private fun renderBackendPref(): String =
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("render_backend", "gl") ?: "gl"

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    @Suppress("unused")
    // Used by Rust
    private fun navigateToUrl(url: String?) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private var loc = IntArray(2)

    @Suppress("unused")
    // Handle of an EventLoopProxy over in rust-land
    private val eventLoopHandle: Long = 0

    @Suppress("unused")
    // Used by Rust
    private val locInWindow: IntArray
        get() {
            mSurfaceView.getLocationInWindow(loc)
            return loc
        }

    @Suppress("unused")
    // Used by Rust
    private val surfaceWidth: Int
        get() = mSurfaceView.width

    @Suppress("unused")
    // Used by Rust
    private val surfaceHeight: Int
        get() = mSurfaceView.height

    private external fun keydown(keyTag: String)
    private external fun keyup(keyTag: String)
    private external fun requestContextMenu()
    private external fun runContextMenuCallback(index: Int)
    private external fun clearContextMenu()

    // MoleRuffle:显/隐系统自带软键盘。
    private external fun toggleSoftKeyboard()

    // MoleRuffle:迷你输入框 → Rust:删除了 n 个字符 / 新增了这段文本。
    private external fun imeDelete(count: Int)
    private external fun imeText(text: String)

    // MoleRuffle:日志开关/保留天数改动后即时通知 Rust(无需重启)。
    private external fun setLogging(enabled: Boolean, days: Int)

    // MoleRuffle:监听到 IME 收起(返回键/键盘收起钮)→ 通知 Rust 同步键盘显示状态。
    private external fun syncKeyboardHidden()

    // MoleRuffle:性能 HUD 的 Rust 侧数据(FPS/帧时间/后端/分辨率)。
    private external fun getPerfStats(): String

    // MoleRuffle:性能 HUD 500ms 轮询。
    private val hudHandler = Handler(Looper.getMainLooper())
    private var perfHud: TextView? = null
    // MoleRuffle:游戏覆盖层根布局(设置面板里按 id 找控件用)。
    private var overlayLayout: ConstraintLayout? = null
    // MoleRuffle:上一次 IME 可见状态(insets 监听去重用)。
    private var lastImeVisible = false
    // MoleRuffle:迷你输入框(键盘上方的输入条)—— 真实 InputConnection,能删也能看见在打什么。
    private var imeProxy: EditText? = null
    // 内部清空输入框时屏蔽 TextWatcher(避免把游戏里已经打好的字也删掉)。
    private var imeInternalEdit = false
    // 当前输入法高度(px),用于把输入条顶到键盘上方。
    private var imeHeightPx = 0
    // "程序以为键盘开着,但系统里键盘其实已经关了"的起始时刻,用于自愈。
    private var kbPendingSince = 0L
    // 脱钩看门狗的"强补弹"是否已做过一次(1.2s 补弹、2.6s 还不行才退场)。
    private var kbDriftRetried = false
    // 上一次"窗口可见区域高度",用于可见区域法检测键盘收起。
    private var lastVisibleHeight = 0

    // showImeProxy 里排出去的延迟重试,hideImeProxy 时要全部取消,
    // 否则键盘关掉后它们还会把键盘又弹起来(状态机脱钩的重要来源)。
    private val imeRetryRunnables = ArrayList<Runnable>()

    /**
     * 系统里软键盘**真实**是否可见。
     * ★ 不要依赖自己缓存的 imeHeightPx/insets 回调 —— 回调偶发丢失时,
     *   程序就会"以为键盘还开着",而游戏还停留在输入态,其它元素点不了。
     *   这里直接读 decorView 当前的 rootWindowInsets,是唯一可信来源。
     */
    private fun imeActuallyVisible(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(window.decorView) ?: return imeHeightPx > 0
        return insets.isVisible(WindowInsetsCompat.Type.ime())
    }

    /** 强制退出键盘模式:收输入条 + 通知 Rust 清游戏文本框聚焦。 */
    private fun forceExitKeyboardMode(reason: String) {
        logToRust("强制退出键盘模式($reason)")
        hideImeProxy()
        try {
            syncKeyboardHidden()
        } catch (_: Throwable) {
        }
        // ★ 补一刀:有些 ROM 上"收起"是动画式的,第一次同步时 insets 还没落定,
        //   Rust 那边可能又立刻被游戏的聚焦脉冲顶回输入态。600ms 后再确认一次,
        //   Rust 侧是自愈守卫(每 300ms 清一次聚焦),重复调用无副作用。
        window.decorView.postDelayed({
            if (!imeActuallyVisible()) {
                try {
                    syncKeyboardHidden()
                } catch (_: Throwable) {
                }
            }
        }, 600)
    }
    // 输入条被用户拖动出来的偏移(不含键盘顶起量)。
    private var imeBarUserDy = 0f
    // 布局调整模式:悬浮球里点「调整布局」进入,可直接拖动所有浮层控件并保存。
    private var layoutMode = false
    private var layoutHint: View? = null
    // 进入布局调整前的坐标快照(点「放弃并退出」时还原)。
    private var layoutSnapshot: LinkedHashMap<View, FloatArray>? = null
    private var layoutSnapshotDy = 0f

    // ── 布局编辑界面(王者/和平精英那种「自定义布局」)──────────────
    // 顶部操作条 = layoutHint;底部「按键大小」面板 = layoutPanel。
    private var layoutPanel: View? = null
    // 当前点选的控件(点选后底部滑块调的就是它的大小);null = 滑块调「整体」。
    private var layoutSelectedView: View? = null
    private var layoutSelectedKey: String? = null
    private var layoutOrigBg: android.graphics.drawable.Drawable? = null
    private var layoutSizeLabel: TextView? = null
    private var layoutSizeSeek: SeekBar? = null
    // 「控件与按键」对话框引用 —— 点「调整布局」时要把它关掉,否则挡着没法拖。
    private var controlsDialog: android.app.AlertDialog? = null

    // ── 图标(替代 Emoji)──────────────────────────────────────
    // 用矢量 drawable 着色后当 compound icon,比 Emoji 在各 ROM 上表现一致。
    private fun icon(resId: Int, tint: Int, sizeDp: Int = 18): Drawable? {
        val d = AppCompatResources.getDrawable(this, resId) ?: return null
        val wrapped = DrawableCompat.wrap(d.mutate())
        DrawableCompat.setTint(wrapped, tint)
        val px = dp(sizeDp)
        wrapped.setBounds(0, 0, px, px)
        return wrapped
    }

    /** 给 TextView/Button/CheckBox 加左侧图标(同时清掉 Emoji 文本)。 */
    private fun withIcon(v: TextView, resId: Int, label: String, tint: Int = 0xFF455A64.toInt()) {
        v.text = label
        v.compoundDrawablePadding = dp(8)
        v.setCompoundDrawables(icon(resId, tint), null, null, null)
    }

    // MoleRuffle:日志设置(默认关闭,保留 7 天)。
    @Suppress("unused")
    // Used by Rust
    private fun isLogEnabled(): Boolean =
        getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("log_enabled", false)

    @Suppress("unused")
    // Used by Rust
    private fun getLogRetentionDays(): Int =
        getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("log_retention_days", 7)

    private val hudRunnable = object : Runnable {
        private var tick = 0
        override fun run() {
            // ★ tick 只能在这里自增一次:以前两个判断里各写了一次 ++tick,
            //   结果它永远是奇数,永远不可能 %20==0 → 内存日志一次都没输出过。
            tick++
            // ★ 状态框隐藏时就不做 HUD 轮询(JNI getPerfStats + 内存/网络/温度读取),
            //   省掉这段每 500ms 一次的无谓开销,把 CPU 全留给游戏。
            if (perfHud?.visibility == View.VISIBLE) updatePerfHud()
            // ★ 每 500ms 对一次"程序认为的键盘状态"和"系统真实键盘状态":
            //   输入条还在显示(= 我们想让键盘开着)但系统里键盘已经关了
            //   → 说明脱钩了,必须强制退出键盘模式,否则游戏里其它元素点不了。
            checkKeyboardStateDrift()
            checkInputBridge()
            // ★ 每 5s(10 × 500ms)记一次内存,用来坐实"餐厅闪退是不是内存打爆"。
            if (isLogEnabled() && tick % 10 == 0) {
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val mi = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    val rt = Runtime.getRuntime()
                    logToRust(
                        "内存: 可用 ${mi.availMem / 1048576}MB / 共 ${mi.totalMem / 1048576}MB, " +
                            "低内存=${mi.lowMemory}, 阈值 ${mi.threshold / 1048576}MB, " +
                            "Java堆已用 ${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB/" +
                            "${rt.maxMemory() / 1048576}MB"
                    )
                } catch (_: Throwable) {
                }
            }
            // 每 ~2s 做一次浮层自愈(4 × 500ms)。
            if (tick % 4 == 0) healOverlay()
            hudHandler.postDelayed(this, 500)
        }
    }

    private fun checkKeyboardStateDrift() {
        val bar = imeProxy ?: return
        if (bar.visibility != View.VISIBLE) {
            kbPendingSince = 0L
            kbDriftRetried = false
            return
        }
        if (imeActuallyVisible()) {
            kbPendingSince = 0L
            kbDriftRetried = false
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (kbPendingSince == 0L) {
            kbPendingSince = now
        } else if (now - kbPendingSince > 1200 && !kbDriftRetried) {
            // ★ 先强补弹一次而不是直接退场:showSoftInput 被吞的场景里,
            //   requestFocus + restartInput + FORCED 常常能救回来。
            //   直接退场会造成"点输入框→键盘起不来→被杀→再点"的循环(日志实锤)。
            kbDriftRetried = true
            logToRust("键盘 1.2s 未真正弹出 → 强补弹一次")
            runOnUiThread {
                try {
                    bar.isFocusable = true
                    bar.isFocusableInTouchMode = true
                    bar.requestFocus()
                    val imm =
                        getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.restartInput(bar)
                    imm?.showSoftInput(bar, InputMethodManager.SHOW_FORCED)
                } catch (_: Throwable) {
                }
            }
        } else if (now - kbPendingSince > 2600) {
            kbPendingSince = 0L
            kbDriftRetried = false
            forceExitKeyboardMode("补弹后键盘仍未弹出(脱钩 >2.6s),退出键盘模式")
        }
    }

    /**
     * 浮层自愈(解决"悬浮控件有时候消失、过一会又好"):
     *  1. 保证常驻控件有**各自不同且稳定**的 elevation —— 之前全部 50,而
     *     Z 值相同时按子 View 顺序绘制,排在后面的 perf_hud/输入条就会把
     *     悬浮球盖住(悬浮球在 XML 里反而不算最后);
     *  2. 保证父布局不裁剪子控件(拖到边缘被裁掉 = "消失")。
     *  (键盘状态脱钩的自愈在 checkKeyboardStateDrift(),每 500ms 跑一次。)
     */
    private fun healOverlay() {
        val root = overlayLayout ?: return
        applyBaseElevations(root)
    }

    /** 常驻浮层控件的基础绘制高度(各不相同,谁该在上谁就在上)。
     *  ★ 全部抬到 870+ :主人反馈还是被遮挡,说明还有我们没算到的层。 */
    private val BASE_Z = mapOf(
        R.id.button_settings to 900f,  // 悬浮球永远最上层(常驻)
        R.id.button_ime to 890f,
        R.id.button_toggle to 890f,
        R.id.button_joystick_toggle to 890f,
        R.id.button_hud to 890f,
            R.id.perf_hud to 880f,
            R.id.joystick_panel to 885f,
            R.id.joystick_zone to 860f,
            R.id.gamepad_panel to 870f
    )

    /**
     * 拖完把 Z 放回基础值。
     * ★ 不复位的话 raiseAboveOthers(1000) 会一直留着,那个控件就永久压在悬浮球(900)
     *   上面 —— 主人反馈的"悬浮球还是被挡住"很大概率就是拖过某个控件之后留下的。
     */
    private fun restoreBaseZ(v: View) {
        val z = BASE_Z[v.id]
        if (z != null) {
            if (v.elevation != z) setLayerZ(v, z)
        } else if (v.elevation >= 990f) {
            setLayerZ(v, 900f)
        }
    }

    private fun applyBaseElevations(root: View) {
        val map = BASE_Z
        for ((id, z) in map) {
            root.findViewById<View>(id)?.let { v ->
                // ≥990 说明正被拖动(raiseAboveOthers 抬到 1000),别把它按下来。
                if (v.elevation < 990f && v.elevation != z) setLayerZ(v, z)
            }
        }
    }

    /**
     * 设置绘制高度,并把阴影抹掉。
     * ★ 直接给 900 这种量级的 elevation 会画出一大团阴影(黑乎乎一圈),
     *   所以把 spot/ambient 阴影颜色设成透明 —— 只借用 Z 序,不留阴影。
     */
    private fun setLayerZ(v: View, z: Float) {
        v.elevation = z
        if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
            v.outlineSpotShadowColor = Color.TRANSPARENT
            v.outlineAmbientShadowColor = Color.TRANSPARENT
        }
    }

    // ── 迷你输入框(键盘上方输入条)────────────────────────────
    //
    // 为什么要它:GameActivity 自带的 text_input_state 只有"文本",多数中文输入法
    // (百度/讯飞/Gboard 中文)的退格走 InputConnection.deleteSurroundingText,
    // 不会更新那个状态 → 表现为"能打字、删不掉"。这里用一个真实的 EditText 当输入法
    // 宿主,增删都能拿到;放在键盘上方还顺带解决"看不见自己在打什么"。

    @Suppress("unused")
    // Used by Rust
    private fun showImeProxy() {
        val bar = imeProxy ?: return
        runOnUiThread {
            cancelImeRetries()
            kbPendingSince = 0L
            kbDriftRetried = false
            clearImeProxyText()
            bar.isFocusable = true
            bar.isFocusableInTouchMode = true
            bar.visibility = View.VISIBLE
            // ★ 置顶:输入条必须压在所有浮层控件之上,否则会被手柄键/悬浮球盖住。
            setLayerZ(bar, 960f)
            bar.bringToFront()
            bar.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            // 刚被 hideSoftInput 关掉的键盘,紧跟一次 showSoftInput 常被系统忽略,
            // 所以先 restartInput 复位输入连接,再补两次延迟重试(靠真实 insets 判断成败)。
            imm?.restartInput(bar)
            bar.post {
                imm?.showSoftInput(bar, InputMethodManager.SHOW_IMPLICIT)
                applyImeBarPosition(imeHeightPx)
            }
            scheduleImeRetry(260) {
                if (!imeActuallyVisible()) {
                    imm?.showSoftInput(bar, InputMethodManager.SHOW_FORCED)
                }
            }
            scheduleImeRetry(700) {
                if (!imeActuallyVisible()) {
                    imm?.showSoftInput(bar, InputMethodManager.SHOW_IMPLICIT)
                }
                applyImeBarPosition(imeHeightPx)
            }
        }
    }

    /** 排一个延迟重试(要在 hideImeProxy 里能整体取消)。 */
    private fun scheduleImeRetry(delayMs: Long, body: () -> Unit) {
        val r = Runnable {
            try {
                body()
            } catch (_: Throwable) {
            }
        }
        imeRetryRunnables.add(r)
        window.decorView.postDelayed(r, delayMs)
    }

    private fun cancelImeRetries() {
        for (r in imeRetryRunnables) window.decorView.removeCallbacks(r)
        imeRetryRunnables.clear()
    }

    @Suppress("unused")
    // Used by Rust
    private fun hideImeProxy() {
        val bar = imeProxy ?: return
        runOnUiThread {
            // ★ 先取消所有"补弹键盘"的重试,否则键盘关掉后它们又会把键盘弹起来,
            //   造成"程序以为键盘开着 / 系统里键盘已关"的状态脱钩。
            cancelImeRetries()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(bar.windowToken, 0)
            // ★ 再按 decorView 的 token 收一次:点键盘自带"最小化"键时,IMM 认为的
            //   served view 未必是我们的输入条,只收 bar 的 token 有时收不掉。
            try {
                imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
            } catch (_: Throwable) {
            }
            kbPendingSince = 0L
            kbDriftRetried = false
            lastImeVisible = false
            clearImeProxyText()
            bar.clearFocus()
            bar.visibility = View.GONE
            // ★ 关键:键盘关掉后必须把焦点交还给游戏画面。
            //   输入框还拿着焦点时,游戏里的其它控件(按钮/文本框)点不动 ——
            //   表现就是"关了键盘之后游戏里啥都点不了"。
            bar.isFocusable = false
            bar.isFocusableInTouchMode = false
            // ★★ 千万不要在这里 `mSurfaceView.requestFocus()`!
            //    InputEnabledSurfaceView 自带 InputConnection(GameActivity 的 gametextinput),
            //    一旦把焦点交给它,InputMethodManager 会立刻"为新获得焦点的编辑器"把
            //    软键盘又拉起来 —— 表现就是"点了退出键盘没反应 / 键盘自己又冒出来"。
            //    这里只需要让输入条放下焦点即可。
            imeHeightPx = 0
            applyImeBarPosition(0)
        }
    }

    /** 清空输入条(不触发"删除注入")。 */
    private fun clearImeProxyText() {
        imeInternalEdit = true
        try {
            imeProxy?.setText("")
        } catch (_: Throwable) {
        }
        imeInternalEdit = false
    }

    /**
     * Rust 把游戏里当前聚焦文本框的内容镜像过来(Used by Rust)。
     *
     * 不镜像的话:输入条一开始是空的,而游戏文本框里可能已经有字(上次残留、
     * SWF 预填),此时按退格 —— 输入条没变化可上报 → "删不掉已经输入的内容"。
     */
    @Suppress("unused")
    // Used by Rust
    private fun setImeBarText(text: String) {
        runOnUiThread {
            val bar = imeProxy ?: return@runOnUiThread
            imeInternalEdit = true
            try {
                bar.setText(text)
                bar.setSelection(text.length)
            } catch (_: Throwable) {
            }
            imeInternalEdit = false
        }
    }

    /**
     * 把输入条**贴**在软键盘上沿(王者/微信那种):输入条底边 = 父布局底边 - 键盘高 - 2dp。
     *
     * ★ 不能直接 `translationY = -imeHeight`:布局的 bottom 约束本身已经带 bottomMargin,
     *   而且一旦窗口被软键盘 resize 过,再减一次键盘高就会"飘到键盘上方一大截"。
     *   这里每次都按当前实际尺寸重算(实时刷新),不依赖任何历史位置。
     */
    private fun applyImeBarPosition(imeHeight: Int) {
        val bar = imeProxy ?: return
        val parent = bar.parent as? View ?: return
        if (parent.height <= 0 || bar.height <= 0) {
            bar.post { applyImeBarPosition(imeHeight) }
            return
        }
        val gap = dp(2)
        val targetBottom = parent.height - imeHeight - gap - imeBarUserDy.toInt()
        val naturalBottom = bar.top + bar.height
        bar.translationY = (targetBottom - naturalBottom).toFloat()
    }

    private fun setupImeProxy(bar: EditText) {
        bar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (imeInternalEdit) return
                try {
                    if (before > 0) imeDelete(before)
                    if (count > 0 && s != null) {
                        val end = (start + count).coerceAtMost(s.length)
                        if (end > start) imeText(s.subSequence(start, end).toString())
                    }
                } catch (_: Throwable) {
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // 回车/完成:把 Enter 交给游戏(登录、发聊天),并清空输入条。
        bar.setOnEditorActionListener { _, actionId, event ->
            val isEnter =
                actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (isEnter) {
                try {
                    keydown("ENTER")
                    keyup("ENTER")
                } catch (_: Throwable) {
                }
                clearImeProxyText()
                true
            } else {
                false
            }
        }
        // 物理键盘:退格/回车自己处理(软键盘走 InputConnection,不会进这里)。
        bar.setOnKeyListener { _, keyCode, event ->
            when {
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL -> {
                    try {
                        imeDelete(1)
                    } catch (_: Throwable) {
                    }
                    true
                }
                event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) -> {
                    try {
                        keydown("ENTER")
                    } catch (_: Throwable) {
                    }
                    true
                }
                event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) -> {
                    try {
                        keyup("ENTER")
                    } catch (_: Throwable) {
                    }
                    true
                }
                else -> false
            }
        }
    }

    @Suppress("unused")
    // Used by Rust
    private fun showContextMenu(items: Array<String>) {
        runOnUiThread {
            val popup = PopupMenu(this, findViewById(R.id.button_toggle))
            val menu = popup.menu
            if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
                menu.setGroupDividerEnabled(true)
            }
            var group = 1
            for (i in items.indices) {
                val elements = items[i].split(" ".toRegex(), limit = 4).toTypedArray()
                val enabled = elements[0].toBoolean()
                val separatorBefore = elements[1].toBoolean()
                val checked = elements[2].toBoolean()
                val caption = elements[3]
                if (separatorBefore) group += 1
                val item = menu.add(group, i, Menu.NONE, caption)
                item.setEnabled(enabled)
                if (checked) {
                    item.setCheckable(true)
                    item.setChecked(true)
                }
            }
            val exitItemId: Int = items.size
            menu.add(group, exitItemId, Menu.NONE, "Exit")
            popup.setOnMenuItemClickListener { item: MenuItem ->
                if (item.itemId == exitItemId) {
                    finish()
                } else {
                    runContextMenuCallback(item.itemId)
                }
                true
            }
            popup.setOnDismissListener { clearContextMenu() }
            popup.show()
        }
    }

    @Suppress("unused")
    // Used by Rust
    private fun getAndroidDataStorageDir(): String {
        // TODO It can also be placed in an external storage path in the future to share archived content
        val storageDirPath = "${filesDir.absolutePath}/ruffle/shared_objects"
        val storageDir = File(storageDirPath)
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return storageDirPath
    }

    override fun onCreateSurfaceView() {
        val inflater = layoutInflater

        @SuppressLint("InflateParams")
        val layout = inflater.inflate(R.layout.keyboard, null) as ConstraintLayout
        overlayLayout = layout

        // MoleRuffle:关闭所有层级的裁剪 + 给浮层控件一个稳定的高 elevation。
        //   这两条一起解决"悬浮控件拖到某些位置就消失、过一会又回来":
        //   · clipChildren 默认 true,控件被拖到父布局边界外会被裁掉;
        //   · 只靠 bringToFront() 不够 —— 有 elevation 的控件按 Z 排序绘制,
        //     所以给常驻控件一个明确的基础高度,拖动中的再抬更高,顺序才稳定。
        fun disableClip(v: View) {
            if (v is ViewGroup) {
                v.clipChildren = false
                v.clipToPadding = false
                for (i in 0 until v.childCount) disableClip(v.getChildAt(i))
            }
        }
        disableClip(layout)
        applyBaseElevations(layout)

        contentViewId = View.generateViewId()
        layout.id = contentViewId
        setContentView(layout)
        mSurfaceView = InputEnabledSurfaceView(this)
        // ★ 把 SurfaceView 自带的输入连接"阉掉":它是 GameActivity 的 gametextinput 用的,
        //   一旦它有焦点就会触发系统软键盘(跟我们自己的输入法宿主抢),是键盘反复
        //   自己弹出来、游戏元素点不动的重要来源。设成 TYPE_NULL 后它永远不再是编辑器。
        try {
            val ei = EditorInfo()
            ei.inputType = EditorInfo.TYPE_NULL
            ei.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            mSurfaceView.setEditorInfo(ei)
        } catch (_: Throwable) {
        }

        mSurfaceView.contentDescription = "Ruffle Player"

        val placeholder = findViewById<View>(R.id.placeholder)
        val pars = placeholder.layoutParams as ConstraintLayout.LayoutParams
        val parent = placeholder.parent as ViewGroup
        val index = parent.indexOfChild(placeholder)
        parent.removeView(placeholder)
        parent.addView(mSurfaceView, index)
        mSurfaceView.setLayoutParams(pars)
        // 首启应用渲染分辨率(直接在 UI 线程定缓冲尺寸,此时 surface 未建,不重建 → 不崩)。
        mSurfaceView.post { applyRenderScaleStartup() }
        // MoleRuffle:虚拟手柄按钮(↑↓←→/空格)—— 复用官方按键注入通道 keydown/keyup(tag)。
        //   按下→KeyDown、抬起/取消→KeyUp,天然支持按住持续走;ConstraintLayout 默认 splitMotionEvents
        //   开启,两指按两个按钮各自独立成流 → 多点触控(如 ← + 空格 同时)可用。
        //   支持拖动换位:用 translationX/Y(不走 margin —— ConstraintLayout 每次 layout 都会把
        //   leftMargin 重新解析回 startMargin,margin 方案会"弹回"原位),松手持久化,重启恢复。
        val gamepadKeys = gatherAllDescendantsOfType<Button>(
            layout.getViewById(R.id.gamepad_panel),
            Button::class.java
        )
        for (b in gamepadKeys) {
            val dragKey = "pad_" + ((b.tag as? String) ?: b.id)
            b.setOnTouchListener { view: View, motionEvent: MotionEvent ->
                if (layoutMode) return@setOnTouchListener layoutDrag(view, motionEvent, dragKey)
                val tag = view.tag as? String
                val st = padDragState(view)
                // ★ 平时(打游戏中)不再把"手指滑动"判定为拖动:否则按住方向键走路时
                //   稍微一滑,键就被拖跑、走路也中断了。要挪位置请进「调整布局」。
                val draggable = canDragNow(isDragEnabled(dragKey))
                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        st.dragging = false
                        st.downX = motionEvent.rawX
                        st.downY = motionEvent.rawY
                        st.startTx = view.translationX
                        st.startTy = view.translationY
                        tag?.let { keydown(it) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!draggable) return@setOnTouchListener false
                        val dx = motionEvent.rawX - st.downX
                        val dy = motionEvent.rawY - st.downY
                        if (!st.dragging && dx * dx + dy * dy > padSlopSq) {
                            st.dragging = true
                            // 判定为拖动:立即结束按键,避免角色持续行走
                            tag?.let { keyup(it) }
                        }
                        if (st.dragging) {
                            moveByTranslation(view, st.startTx + dx, st.startTy + dy)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tag?.let { keyup(it) }
                        if (st.dragging && motionEvent.actionMasked == MotionEvent.ACTION_UP) {
                            saveOverlayPos(view, "pad_" + (tag ?: view.id))
                        }
                        st.dragging = false
                    }
                }
                view.performClick()
                // 返回 false:让 Button 自身也处理这次触摸(呈现按下高亮),同时保证后续 UP 仍投递到本按钮。
                false
            }
        }
        // 恢复上次的拖动位置(布局完成后钳制到屏内)。
        for (b in gamepadKeys) {
            restoreOverlayPos(b, "pad_" + ((b.tag as? String) ?: b.id))
        }

        // MoleRuffle:手柄显隐开关(持久化到 show_gamepad_panel,重启后保持)。
        layout.findViewById<View>(R.id.button_toggle).setOnClickListener {
            val panel = layout.findViewById<View>(R.id.gamepad_panel)
            val visible = panel.visibility != View.VISIBLE
            panel.visibility = if (visible) View.VISIBLE else View.GONE
            putUiPref("show_gamepad_panel", visible)
        }

        // MoleRuffle:摇杆显隐开关(常驻「摇杆」钮,不用进设置)。
        layout.findViewById<View>(R.id.button_joystick_toggle).setOnClickListener {
            val panel = layout.findViewById<View>(R.id.joystick_panel)
            panel.visibility =
                if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            putUiPref("show_joystick", panel.visibility == View.VISIBLE)
            refreshJoystickZone()
        }

        // MoleRuffle:系统自带软键盘开关(登录/聊天打字,交给原生调 AndroidApp.show_soft_input)。
        layout.findViewById<View>(R.id.button_ime).setOnClickListener {
            toggleSoftKeyboard()
        }

        // MoleRuffle:性能 HUD 显隐开关(📊)。隐藏后可用 📊 或 ⚙ 设置面板恢复。
        layout.findViewById<View>(R.id.button_hud).setOnClickListener {
            val hud = layout.findViewById<View>(R.id.perf_hud)
            hud.visibility = if (hud.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            putUiPref("show_hud", hud.visibility == View.VISIBLE)
        }

        // MoleRuffle:悬浮设置球(⚙)—— 点按弹设置面板(版本/按键显隐/状态框);可拖动换位。
        val ball = layout.findViewById<ImageButton>(R.id.button_settings)
        ball.setOnTouchListener { view: View, motionEvent: MotionEvent ->
            if (layoutMode) return@setOnTouchListener layoutDrag(view, motionEvent, "settings_ball")
            val st = padDragState(view)
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    st.dragging = false
                    st.downX = motionEvent.rawX
                    st.downY = motionEvent.rawY
                    st.startTx = view.translationX
                    st.startTy = view.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = motionEvent.rawX - st.downX
                    val dy = motionEvent.rawY - st.downY
                    if (!st.dragging && dx * dx + dy * dy > padSlopSq) st.dragging = true
                    if (st.dragging) moveByTranslation(view, st.startTx + dx, st.startTy + dy)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (st.dragging) {
                        saveOverlayPos(view, "settings_ball")
                    } else {
                        view.performClick()
                    }
                    st.dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    st.dragging = false
                    true
                }
                else -> false
            }
        }
        ball.setOnClickListener { showBallMenu() }
        restoreOverlayPos(ball, "settings_ball")

        // MoleRuffle:迷你输入框 —— 键盘上方的输入条(像王者那样看得见在打什么)。
        //   它本身就是输入法宿主(真实 InputConnection),退格删除才收得到。
        val bar = EditText(this).apply {
            id = View.generateViewId()
            val lp = ConstraintLayout.LayoutParams(0, dp(36)).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                leftMargin = dp(24)
                rightMargin = dp(24)
                bottomMargin = dp(8)
            }
            layoutParams = lp
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9E9E9E.toInt())
            hint = "在这里输入…"
            textSize = 14f
            isSingleLine = true
            maxLines = 1
            // ★ 这里绝不能带 password 类变体(VISIBLE_PASSWORD / WEB_PASSWORD …
            //   以及 TYPE_TEXT_FLAG_NO_SUGGESTIONS 在部分 ROM 上同样被判为敏感):
            //   华为/小米/OPPO/vivo 见到"密码输入框"会强制切到系统「安全键盘」,
            //   安全键盘禁用第三方输入法 → 表现就是"只能打英文、打不了中文"。
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            privateImeOptions = ""
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_ACTION_DONE
            isCursorVisible = true
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
        }
        layout.addView(bar)
        imeProxy = bar
        setupImeProxy(bar)
        restoreImeBarOffset()

        // MoleRuffle:虚拟摇杆(按住拖动 → 持续 ↑↓←→,松手回中)。
        setupJoystick(layout)
        // MoleRuffle:这些控件平时只响应点击,补上"布局调整模式"的拖动能力
        //   (未进入该模式时监听器返回 false,点击行为完全不变)。
        attachDraggable(layout.findViewById(R.id.button_toggle), "btn_toggle")
        attachDraggable(layout.findViewById(R.id.button_joystick_toggle), "btn_joystick_toggle")
        attachDraggable(layout.findViewById(R.id.button_ime), "btn_ime")
        attachDraggable(layout.findViewById(R.id.button_hud), "btn_hud")
        attachDraggable(layout.findViewById(R.id.perf_hud), "perf_hud")
        // ★ 迷你输入条不挂拖动:它跟着软键盘一起出现/消失,让它能被拖走只会导致
        //   位置乱掉、甚至挡住游戏。要挪它请进「调整布局」。
        restoreOverlayPos(layout.findViewById(R.id.button_toggle), "btn_toggle")
        restoreOverlayPos(layout.findViewById(R.id.button_joystick_toggle), "btn_joystick_toggle")
        restoreOverlayPos(layout.findViewById(R.id.button_ime), "btn_ime")
        restoreOverlayPos(layout.findViewById(R.id.button_hud), "btn_hud")
        restoreOverlayPos(layout.findViewById(R.id.perf_hud), "perf_hud")

        // MoleRuffle:按持久化配置应用各控件显隐 + HUD 轮询。
        applyVisibilityPrefs(layout)
        // 应用已保存的按键大小(全局 × 单控件缩放)。
        layout.post { refreshAllControlScales() }
        // 应用已保存的帧率上限(默认 0 = 不限)。
        layout.post {
            try {
                nativeSetFpsCap(fpsCap)
                nativeSetQuality(renderQuality)
            } catch (_: Throwable) {
            }
        }
        // 把缓存上限推给 Rust,并先按上限裁一次(上次运行可能超限)。
        layout.post { applyCacheLimit() }
        perfHud = layout.findViewById(R.id.perf_hud)
        // 点 HUD 自身也可关闭。
        perfHud?.setOnClickListener {
            it.visibility = View.GONE
            putUiPref("show_hud", false)
        }
        hudHandler.removeCallbacks(hudRunnable)
        hudHandler.post(hudRunnable)

        layout.requestLayout()
        layout.requestFocus()

        // MoleRuffle:监听 IME insets —— 用户按返回键/键盘收起钮关掉键盘时,
        // Rust 侧的显示状态还是 true(下次按 ⌨ 要按两次才弹、切输入框不自动弹的根因)。
        // 这里监听系统真实的键盘显隐,收起时通知 Rust 同步状态。
        ViewCompat.setOnApplyWindowInsetsListener(layout) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeH = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // 实时刷新:每次 insets 变化都重算输入条位置(键盘高度动画期间会连续回调)。
            imeHeightPx = if (imeH > 0) imeH else 0
            applyImeBarPosition(imeHeightPx)
            if (imeVisible) kbPendingSince = 0L
            if (!imeVisible && lastImeVisible) {
                // 用户用返回键/键盘自带收起钮关了键盘:通知 Rust 同步状态,
                // 同时把输入条彻底收掉并交还焦点(否则游戏里其它控件点不动)。
                try {
                    syncKeyboardHidden()
                } catch (_: Throwable) {
                }
                hideImeProxy()
            }
            lastImeVisible = imeVisible
            insets
        }

        // MoleRuffle:第二套**独立**的键盘检测(可见窗口区域高度法)。
        //   Insets 回调在某些 ROM / 某些场景(尤其点键盘自带的"最小化/收起"键)会漏掉,
        //   光靠它就会出现"程序以为键盘还开着"。这套用经典 getWindowVisibleDisplayFrame 兜底,
        //   两套任一检测到收起都会退出键盘模式。
        lastVisibleHeight = 0
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(kbdLayoutListener)

        mSurfaceView.holder.addCallback(this)
        ViewCompat.setOnApplyWindowInsetsListener(mSurfaceView, this)
    }

    /**
     * 可见窗口区域变高 = 软键盘被收起了。
     * 只在"我们还以为键盘开着(输入条仍在显示)"时才处理 —— 那才是脱钩状态。
     */
    private val kbdLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val r = Rect()
        window.decorView.getWindowVisibleDisplayFrame(r)
        val h = r.height()
        if (lastVisibleHeight == 0) {
            lastVisibleHeight = h
            return@OnGlobalLayoutListener
        }
        val grew = h - lastVisibleHeight
        lastVisibleHeight = h
        if (grew > dp(80) && imeProxy?.visibility == View.VISIBLE) {
            logToRust("可见区域变高 ${grew}px → 键盘已收起,退出键盘模式")
            imeHeightPx = 0
            hideImeProxy()
            try {
                syncKeyboardHidden()
            } catch (_: Throwable) {
            }
        }
    }

    // MoleRuffle:引擎实际收到的按下次数(输入桥对账用)。
    private external fun getTouchCount(): Long

    // MoleRuffle:直连注入触摸(输入桥断了之后的兜底通道)。
    private external fun injectTouch(x: Float, y: Float, down: Boolean)

    /**
     * 渲染分辨率档位(性能)。
     *
     * ★ 为什么:原生版是按屏幕**物理像素**渲染的(主人这台 3198×2000 = 640 万像素),
     *   群里那个 WebView+Ruffle WASM 的包是按 CSS 像素、而且还有 WebView 自己的优化,
     *   所以它更流畅。这里用 SurfaceHolder.setFixedSize 把**渲染缓冲**降采样,
     *   由系统把这块小缓冲放大到全屏 —— 填充率按平方下降,是立竿见影的一档。
     *   ★ 默认 100%(与改之前完全一致),只有主人主动调低才会生效。
     *   输入坐标是"视图尺寸 → 缓冲尺寸"按比例换算的,降采样不影响点击命中。
     */
    private var renderScalePct: Int
        get() = getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("render_scale", 75)
        set(v) {
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putInt("render_scale", v).apply()
        }

    private var lastAppliedScale = -1

    /** 给 Rust 侧读已保存的渲染分辨率档位(首启自动应用用)。 */
    @Suppress("unused")
    private fun getRenderScale(): Int = renderScalePct

    /**
     * 首启:直接在 UI 线程给 SurfaceHolder 设固定尺寸(降采样缓冲)。
     * ★ 关键:此时 wgpu surface 还没建,首帧 surface 直接用这个尺寸创建,**不需要重建**,
     *   所以不会触发 Adreno 在初始化窗口期的 begin_render_pass 崩(这就是 v5.2 初版黑屏的真因)。
     */
    private fun applyRenderScaleStartup() {
        val sv = mSurfaceView ?: return
        val w = sv.width
        val h = sv.height
        if (w <= 0 || h <= 0) {
            sv.post { applyRenderScaleStartup() }
            return
        }
        val pct = renderScalePct
        if (pct == lastAppliedScale) return
        try {
            if (pct >= 100) sv.holder.setSizeFromLayout()
            else sv.holder.setFixedSize(w * pct / 100, h * pct / 100)
            lastAppliedScale = pct
            logToRust("首启渲染分辨率 = $pct% (${w * pct / 100}x${h * pct / 100})")
        } catch (t: Throwable) {
            logToRust("首启设置渲染分辨率失败: $t")
        }
    }

    /**
     * Rust 渲染线程在"帧之间"安全点调用:设/清 SurfaceHolder 固定尺寸(降采样渲染缓冲)。
     *   pct=100 时缓冲等于整屏(无降采样);<100 时按百分比缩小缓冲,填充率按平方下降 → 更流畅。
     */
    @Suppress("unused")
    private fun setSurfaceFixedSize(w: Int, h: Int) {
        val sv = mSurfaceView ?: return
        try {
            if (w >= sv.width && h >= sv.height) sv.holder.setSizeFromLayout()
            else sv.holder.setFixedSize(w, h)
        } catch (_: Throwable) {
        }
    }

    // ── 按键大小(王者/和平精英那种)──────────────────────────
    //
    // 两级:① 设置里的**全局按键大小**滑杆(50%~200%,作用在所有手柄键 + 摇杆);
    //       ② 「调整布局」里用**双指捏合**单独缩放某一个控件(0.5x~2.5x,单独记忆)。
    //   最终缩放 = 全局 × 单控件,用 scaleX/scaleY(不动 layout 尺寸,省得和
    //   ConstraintLayout 的约束打架)。
    private var globalBtnScale: Float
        get() = getSharedPreferences("ui", Context.MODE_PRIVATE).getFloat("btn_scale", 1f)
        set(v) {
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putFloat("btn_scale", v).apply()
        }

    private fun perBtnScale(key: String): Float =
        getSharedPreferences("pad_layout", Context.MODE_PRIVATE).getFloat("${key}_scale", 1f)

    private fun savePerBtnScale(key: String, s: Float) {
        getSharedPreferences("pad_layout", Context.MODE_PRIVATE)
            .edit().putFloat("${key}_scale", s).apply()
    }

    /** 把「全局 × 单控件」的缩放应用到控件上。 */
    private fun refreshControlScale(v: View, key: String) {
        // 输入框不参与缩放(它跟着键盘宽度走)。
        if (v === imeProxy) return
        val s = (globalBtnScale * perBtnScale(key)).coerceIn(0.4f, 3.0f)
        v.scaleX = s
        v.scaleY = s
    }

    private fun refreshAllControlScales() {
        for ((v, key) in layoutTargets()) refreshControlScale(v, key)
        // 手柄面板里的方向键/空格键
        val panel = overlayLayout?.findViewById<View>(R.id.gamepad_panel) ?: return
        for (b in gatherAllDescendantsOfType<Button>(panel, Button::class.java)) {
            refreshControlScale(b, "pad_" + ((b.tag as? String) ?: b.id))
        }
    }

    // ── 虚拟摇杆 ────────────────────────────────────────────
    /** 摇杆「跟随手指」开关:左下区域任意位置按下,摇杆就出现在手指处(王者那种)。 */
    private var joystickFollowFinger: Boolean
        get() = getSharedPreferences("ui", Context.MODE_PRIVATE)
            .getBoolean("joystick_follow", false)
        set(v) {
            getSharedPreferences("ui", Context.MODE_PRIVATE)
                .edit().putBoolean("joystick_follow", v).apply()
        }
    private var joystickPanel: View? = null
    private var joystickKnob: View? = null
    private var joystickActive = false
    //
    // 按住拖动 → 按角度换算成 ↑↓←→(走 keydown/keyup,与十字方向键**同一条**
    // 官方按键注入通道,所以行为完全一致),松手回中并全部松开。
    // 布局调整模式下可整体拖动换位(位置按 "joystick" 键持久化)。
    private var joystickDirs = LinkedHashSet<String>()

    private fun setupJoystick(root: View) {
        val panel = root.findViewById<View>(R.id.joystick_panel) ?: return
        val knob = root.findViewById<View>(R.id.joystick_knob) ?: return
        joystickPanel = panel
        joystickKnob = knob
        val maxKnob = dp(32).toFloat()
        val dead = dp(14).toFloat()

        // 摇杆本体:直接按住摇杆拖
        panel.setOnTouchListener { _, ev ->
            if (layoutMode) return@setOnTouchListener layoutDrag(panel, ev, "joystick")
            handleJoystickMove(panel, knob, ev, maxKnob)
        }
        restoreOverlayPos(panel, "joystick")

        // ★ 跟随手指模式:在左下区域任意位置按下 → 摇杆瞬移到手指处并立即生效。
        val zone = root.findViewById<View>(R.id.joystick_zone)
        if (zone != null) {
            zone.setOnTouchListener { _, ev ->
                if (layoutMode || !joystickFollowFinger) return@setOnTouchListener false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        moveJoystickTo(ev.rawX, ev.rawY)
                        joystickActive = true
                        handleJoystickMove(panel, knob, ev, maxKnob)
                        true
                    }
                    MotionEvent.ACTION_MOVE ->
                        if (joystickActive) {
                            handleJoystickMove(panel, knob, ev, maxKnob)
                            true
                        } else {
                            false
                        }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        if (joystickActive) {
                            joystickActive = false
                            resetJoystick()
                            true
                        } else {
                            false
                        }
                    else -> false
                }
            }
        }
    }

    /** 把摇杆整体挪到指定屏幕坐标(跟随手指用)。 */
    private fun moveJoystickTo(rx: Float, ry: Float) {
        val panel = joystickPanel ?: return
        val loc = IntArray(2)
        panel.getLocationOnScreen(loc)
        val cx = loc[0] + panel.width / 2f
        val cy = loc[1] + panel.height / 2f
        panel.translationX += (rx - cx)
        panel.translationY += (ry - cy)
    }

    private fun resetJoystick() {
        joystickKnob?.translationX = 0f
        joystickKnob?.translationY = 0f
        applyJoystickDirs(emptySet())
    }

    /** 摇杆的位移→方向换算(死区越小越灵敏)。 */
    private fun handleJoystickMove(
        panel: View,
        knob: View,
        ev: MotionEvent,
        maxKnob: Float
    ): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // ★ 跟随模式下手指在摇杆外面,要换算成"相对摇杆中心"的偏移
                val (px, py) = if (joystickActive && joystickFollowFinger &&
                    (ev.x < 0f || ev.x > panel.width || ev.y < 0f || ev.y > panel.height)
                ) {
                    val loc = IntArray(2)
                    panel.getLocationOnScreen(loc)
                    Pair(ev.rawX - loc[0], ev.rawY - loc[1])
                } else {
                    Pair(ev.x, ev.y)
                }
                val cx = panel.width / 2f
                val cy = panel.height / 2f
                val dx = px - cx
                val dy = py - cy
                val dist = kotlin.math.hypot(dx, dy)
                if (dist > 0.01f) {
                    val k = minOf(dist, maxKnob) / dist
                    knob.translationX = dx * k
                    knob.translationY = dy * k
                }
                val dead = joystickDeadDp()          // 灵敏度:触发死区
                val want = LinkedHashSet<String>()
                if (dist > dead) {
                    if (dy < -dead) want.add("UP")
                    if (dy > dead) want.add("DOWN")
                    if (dx < -dead) want.add("LEFT")
                    if (dx > dead) want.add("RIGHT")
                }
                applyJoystickDirs(want)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetJoystick()
                return true
            }
        }
        return true
    }

    /** 摇杆灵敏度 = 触发死区(dp),越小越灵敏。 */
    private fun joystickDeadDp(): Float =
        getSharedPreferences("ui", Context.MODE_PRIVATE).getFloat("joystick_dead_dp", 14f)

    /** 只做**增量**:松掉不再需要的方向,按下新增的方向(避免每帧重复 keydown)。 */
    private fun applyJoystickDirs(want: Set<String>) {
        for (d in joystickDirs) {
            if (d !in want) {
                try {
                    keyup(d)
                } catch (_: Throwable) {
                }
            }
        }
        for (d in want) {
            if (d !in joystickDirs) {
                try {
                    keydown(d)
                } catch (_: Throwable) {
                }
            }
        }
        joystickDirs = LinkedHashSet(want)
    }

    // ── 输入通道探针 + 断链兜底 ────────────────────────────────
    //
    // 主人日志实锤:收起软键盘之后,事件循环活着(心跳正常)、画面照常渲染,
    // 但引擎**一次 TOUCH 都收不到** —— 窗口送进去的触摸没走到引擎。
    // 这里两级处理:
    //   ① 探针:记录"窗口收到 DOWN" 与 "事件走到 Activity.onTouchEvent(无 View 消费)";
    //   ② 兜底:每 500ms 对账,窗口已送 DOWN 但引擎计数不动 → 判定输入桥断裂,
    //      之后所有触摸改走 injectTouch() 直连注入,绕过坏掉的那条通道。
    private var downToWindow = 0L
    private var downToActivity = 0L
    private var lastDownMs = 0L
    private var inputBridgeDead = false
    private var lastRustTouchSeen = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downToWindow++
            lastDownMs = android.os.SystemClock.uptimeMillis()
            logToRust("输入: 窗口收到 DOWN(${ev.rawX.toInt()},${ev.rawY.toInt()}) 第 $downToWindow 次")
        }
        val downToActivityBefore = downToActivity
        val handled = super.dispatchTouchEvent(ev)
        // ★ 关键诊断:DOWN 被哪个 View 吃掉了?
        //   主人之前"收起键盘后什么都点不了"的真相很可能就在这里 —— 事件压根没走到
        //   Activity,而是被某个浮层 View 消费了(那样 native 通道自然也收不到)。
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val byActivity = downToActivity > downToActivityBefore
            if (byActivity) {
                // Activity.onTouchEvent 自己处理了(游戏输入通道),不算"被 View 吃掉"。
                logToRust("输入: DOWN 已交给 Activity(游戏通道)")
            } else {
                logToRust("输入: DOWN 被 View 消费 → ${findTouchEater(ev.rawX, ev.rawY) ?: "未知 View"}")
            }
        }
        return handled
    }

    /** 按屏幕坐标反查是哪个浮层控件吃掉了这次触摸。 */
    private fun findTouchEater(rx: Float, ry: Float): String? {
        val root = overlayLayout ?: return null
        val loc = IntArray(2)
        fun hit(v: View): Boolean {
            if (v.visibility != View.VISIBLE || v.width <= 0) return false
            v.getLocationOnScreen(loc)
            val cx = loc[0] + v.width / 2f
            val cy = loc[1] + v.height / 2f
            val hw = v.width * v.scaleX / 2f
            val hh = v.height * v.scaleY / 2f
            return rx >= cx - hw && rx <= cx + hw && ry >= cy - hh && ry <= cy + hh
        }
        for ((v, key) in layoutTargets()) {
            if (hit(v)) return key
        }
        val panel = root.findViewById<View>(R.id.gamepad_panel) ?: return null
        for (b in gatherAllDescendantsOfType<Button>(panel, Button::class.java)) {
            if (hit(b)) return "pad_" + ((b.tag as? String) ?: b.id)
        }
        return null
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) {
            downToActivity++
            logToRust(
                "输入: 事件走到 Activity(${if (a == MotionEvent.ACTION_DOWN) "DOWN" else "UP"}) " +
                    "桥断=$inputBridgeDead"
            )
            if (inputBridgeDead) {
                // 桥断了:不再交给 super(那边收不到),直接送进引擎。
                injectTouch(ev.rawX, ev.rawY, a == MotionEvent.ACTION_DOWN)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 每 500ms 对账一次:窗口送进去的 DOWN,引擎到底收到了没有。 */
    private fun checkInputBridge() {
        // ★ 必须 downToActivity > 0:只有"确实走到 Activity(没被任何 View 消费)的按下"
        //   引擎还没收到,才能判定输入桥断了。
        //   点悬浮球/方向键这类浮层控件本来就不会走到 Activity,之前没加这个判断,
        //   "点了下悬浮球"就被误判成桥断裂(日志实锤:Activity 收到 0 次)。
        if (inputBridgeDead || downToActivity == 0L) return
        val rust = try {
            getTouchCount()
        } catch (_: Throwable) {
            return
        }
        if (rust != lastRustTouchSeen) {
            lastRustTouchSeen = rust
            return
        }
        val waited = android.os.SystemClock.uptimeMillis() - lastDownMs
        if (waited in 900..60000) {
            inputBridgeDead = true
            logToRust(
                "★输入桥断裂:窗口已送 $downToWindow 次 DOWN、Activity 收到 $downToActivity 次, " +
                    "但引擎只收到 $rust 次(等待 ${waited}ms)→ 启用直连注入兜底"
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MoleRuffle:已强制横屏且移除官方 QWERTY 键盘,虚拟手柄由约束布局自适应,无需在此处理。
    }

    // MoleRuffle:刷新性能 HUD —— Rust 侧给 FPS/帧时间/后端/分辨率,Kotlin 补内存/温度/网络。
    private fun updatePerfHud() {
        val hud = perfHud ?: return
        val native = try {
            getPerfStats()
        } catch (t: Throwable) {
            "FPS -"
        }
        val mb = 1024L * 1024L
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / mb
        var availMb = 0L
        var totalMb = 0L
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            availMb = mi.availMem / mb
            totalMb = mi.totalMem / mb
        } catch (_: Throwable) {
        }
        hud.text = buildString {
            append(native)
            append("\n内存 用").append(usedMb).append(" / 余").append(availMb)
                .append(" / 总").append(totalMb).append(" MB")
            append("\n温度 ").append(thermalText()).append("   网络 ").append(networkText())
        }
    }

    private fun thermalText(): String {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "正常"
                PowerManager.THERMAL_STATUS_LIGHT -> "微热"
                PowerManager.THERMAL_STATUS_MODERATE -> "偏热"
                PowerManager.THERMAL_STATUS_SEVERE -> "过热"
                PowerManager.THERMAL_STATUS_CRITICAL -> "严重"
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "危急"
                else -> "未知"
            }
        } catch (_: Throwable) {
            "?"
        }
    }

    private fun networkText(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return "离线"
            val caps = cm.getNetworkCapabilities(nw) ?: return "离线"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                else -> "在线"
            }
        } catch (_: Throwable) {
            "?"
        }
    }

    override fun onStart() {
        super.onStart()
        // 回到前台:停掉导出时开的保活服务(通知随即消失,不留常驻)。
        try {
            stopService(Intent(this, KeepAliveService::class.java))
        } catch (_: Throwable) {
        }
        // MoleRuffle:回前台恢复 HUD 轮询(perfHud 已就绪时)。
        if (perfHud != null) {
            hudHandler.removeCallbacks(hudRunnable)
            hudHandler.post(hudRunnable)
        }
    }

    override fun onStop() {
        // MoleRuffle:进后台停 HUD 轮询,避免无谓唤醒主线程/耗电(遵循失焦即暂停)。
        hudHandler.removeCallbacks(hudRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        hudHandler.removeCallbacks(hudRunnable)
        super.onDestroy()
    }

    /**
     * 系统内存紧张时主动瘦身(闪退加固)。
     *
     * 进家园/餐厅这类资源密集场景时进程被杀过一次(日志里没有任何 Rust panic,
     * 属于系统层面终止)。这里在内存告警时先裁磁盘缓存 + 提示,尽量别走到被杀那一步。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            val freed = try {
                nativeTrimCache(cacheLimitMb)
            } catch (_: Throwable) {
                0L
            }
            logToRust("内存紧张(level=$level)→ 已裁剪缓存 ${freed / 1024} KB")
        }
    }

    private fun hideSystemUI() {
        // This will put the game behind any cutouts and waterfalls on devices which have
        // them, so the corresponding insets will be non-zero.
        if (Build.VERSION.SDK_INT >= VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // From API 30 onwards, this is the recommended way to hide the system UI, rather than
        // using View.setSystemUiVisibility.
        val decorView = window.decorView
        val controller = WindowInsetsControllerCompat(
            window,
            decorView
        )
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.hide(WindowInsetsCompat.Type.displayCutout())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        nativeInit { message ->
            Log.e("ruffle", "Handling panic: $message")
            startActivity(
                Intent(this, PanicActivity::class.java).apply {
                    putExtra("message", message)
                }
            )
        }
        // When true, the app will fit inside any system UI windows.
        // When false, we render behind any system UI windows.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUI()
        // You can set IME fields here or in native code using GameActivity_setImeEditorInfoFields.
        // We set the fields in native_engine.cpp.
        // super.setImeEditorInfoFields(InputType.TYPE_CLASS_TEXT,
        //     IME_ACTION_NONE, IME_FLAG_NO_FULLSCREEN );
        requestNoStatusBarFeature()
        supportActionBar?.hide()
        // ★ 启动时就把日志开关 + **时区偏移**推给 Rust。
        //   以前只在设置面板里推过 → 启动时偏移还是 0,mole.log 的时间戳一直差 8 小时。
        applyLogSettings()
        // ★ MoleRuffle:崩溃兜底 —— 启动时检测上次是否崩溃,若崩则自动降一档渲染分辨率(见 detectCrashAndDowngrade)。
        detectCrashAndDowngrade()
        super.onCreate(savedInstanceState)
    }

    /**
     * ★ MoleRuffle:崩溃兜底(借鉴 mole-flash-android 的 CrashProbe)——
     *   Adreno GLES 驱动崩(__memset_aarch64 in gl.flush)是驱动层问题,改代码难根治。
     *   这里用 Android 的 ApplicationExitInfo 检测上次进程是否崩溃/ANR,若是则自动把渲染分辨率降一档,
     *   降低 GPU 压力、减少下次崩溃概率。崩了能自愈,用户几乎无感。
     */
    private fun detectCrashAndDowngrade() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return // ApplicationExitInfo 需 API 30+
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val exitInfos = am.getHistoricalProcessExitReasons(null, 0, 5)
            for (info in exitInfos) {
                if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH
                    || info.reason == android.app.ApplicationExitInfo.REASON_ANR
                ) {
                    val old = renderScalePct
                    val newPct = (old - 15).coerceAtLeast(35) // 降一档,最低 35%
                    if (newPct < old) {
                        renderScalePct = newPct
                        logToRust("MoleRuffle: 检测到上次崩溃(reason=${info.reason}),自动降档渲染分辨率 $old% -> $newPct%")
                    } else {
                        logToRust("MoleRuffle: 检测到上次崩溃但分辨率已到最低($old%),不再降档")
                    }
                    break // 只按最近一次崩溃降一档
                }
            }
        } catch (t: Throwable) {
            logToRust("MoleRuffle: 崩溃检测失败: $t")
        }
    }

    // Used by Rust
    @Suppress("unused")
    val isGooglePlayGames: Boolean
        get() {
            val pm = packageManager
            return pm.hasSystemFeature("com.google.android.play.feature.HPE_EXPERIENCE")
        }

    private fun requestNoStatusBarFeature() {
        // Hiding the status bar this way makes it see through when pulled down
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowInsetsControllerCompat(
            window,
            mSurfaceView
        ).hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun <T> gatherAllDescendantsOfType(v: View, t: Class<*>): List<T> {
        val result: MutableList<T> = ArrayList()
        @Suppress("UNCHECKED_CAST")
        if (t.isInstance(v)) result.add(v as T)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                result.addAll(gatherAllDescendantsOfType(v.getChildAt(i), t))
            }
        }
        return result
    }

    fun interface CrashCallback {
        fun onCrash(message: String)
    }

    // ── MoleRuffle:虚拟按键/悬浮球拖动 + 设置面板 ──────────────────

    /** 每个控件独立的拖动状态(多指各拖各的,互不串)。 */
    private class PadDrag {
        var dragging = false
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        // 双指捏合缩放:起始两指间距 / 起始缩放值。
        var pinchBase = 0f
        var pinchStartScale = 1f
    }

    /** 拖动判定阈值(平方,约 12dp)。 */
    private val padSlopSq: Int by lazy {
        val v = (12 * resources.displayMetrics.density)
        (v * v).toInt()
    }

    /** 每控件一份拖动状态(view 双 tag:tag 存键, R.id.pad_drag_state 存状态)。 */
    private fun padDragState(view: View): PadDrag {
        var st = view.getTag(R.id.pad_drag_state) as? PadDrag
        if (st == null) {
            st = PadDrag()
            view.setTag(R.id.pad_drag_state, st)
        }
        return st
    }

    /** 用 translationX/Y 移动控件并钳制在父布局内(margin 方案会被布局回滚,不用)。 */
    private fun moveByTranslation(view: View, tx: Float, ty: Float) {
        // 抬升绘制层级:手柄方向键/空格键自带 elevation,悬浮球拖到它们上方时
        // 会被盖住看不见(表现为"拖到某些位置就消失")。用 elevation(而非
        // translationZ)—— 有 Z 值时绘制顺序完全由 elevation 决定,顺序才稳定。
        if (view.elevation < 900f) setLayerZ(view, 900f)
        val parent = view.parent as? ViewGroup ?: return
        // 父布局尺寸还没测出来时,退回用屏幕尺寸算,避免把控件钳到角落"看起来消失"。
        val pw = if (parent.width > 0) parent.width else resources.displayMetrics.widthPixels
        val ph = if (parent.height > 0) parent.height else resources.displayMetrics.heightPixels
        val margin = dp(4).toFloat()
        val minX = -view.left.toFloat() + margin
        val minY = -view.top.toFloat() + margin
        val maxX = (pw - view.width - view.left).toFloat() - margin
        val maxY = (ph - view.height - view.top).toFloat() - margin
        view.translationX = tx.coerceIn(minX, maxX.coerceAtLeast(minX))
        view.translationY = ty.coerceIn(minY, maxY.coerceAtLeast(minY))
    }

    /** 持久化控件拖动位置(相对父布局的比例,换机也基本成立)。 */
    private fun saveOverlayPos(view: View, key: String) {
        val parent = view.parent as? ViewGroup ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        getSharedPreferences("pad_layout", Context.MODE_PRIVATE)
            .edit()
            .putFloat("${key}_tx", view.translationX / parent.width)
            .putFloat("${key}_ty", view.translationY / parent.height)
            .apply()
    }

    /** 恢复控件拖动位置(布局完成后钳制屏内)。 */
    private fun restoreOverlayPos(view: View, key: String) {
        val sp = getSharedPreferences("pad_layout", Context.MODE_PRIVATE)
        if (!sp.contains("${key}_tx")) return
        view.post {
            val parent = view.parent as? ViewGroup ?: return@post
            val tx = sp.getFloat("${key}_tx", 0f) * parent.width
            val ty = sp.getFloat("${key}_ty", 0f) * parent.height
            moveByTranslation(view, tx, ty)
        }
    }

    // ── 布局调整模式 ────────────────────────────────────────
    //
    // 悬浮球 → 「调整布局」进入:所有浮层控件(方向键/空格/⌨/🎮/📊/状态框/输入条)
    // 都能直接拖动(不必长按),松手即存,退出后长期生效。

    /** 布局模式下的拖动处理(按下即拖,不触发点击/按键)。 */
    private fun layoutDrag(view: View, ev: MotionEvent, key: String): Boolean {
        val st = padDragState(view)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                st.dragging = true
                st.downX = ev.rawX
                st.downY = ev.rawY
                st.startTx = view.translationX
                st.startTy = view.translationY
                st.pinchBase = 0f
                st.pinchStartScale = perBtnScale(key)
                raiseAboveOthers(view)
                // ★ 按下即"点选":底部大小滑块跟着切到这个键(王者自定义布局的同款交互)。
                selectLayoutTarget(view, key)
                return true
            }
            // ★ 双指捏合 = 单独缩放这个控件(王者/和平精英那种手感)。
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    st.pinchBase = kotlin.math.hypot(
                        ev.getX(0) - ev.getX(1),
                        ev.getY(0) - ev.getY(1)
                    )
                    st.pinchStartScale = perBtnScale(key)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2 && st.pinchBase > 0f) {
                    val dist = kotlin.math.hypot(
                        ev.getX(0) - ev.getX(1),
                        ev.getY(0) - ev.getY(1)
                    )
                    val s = (st.pinchStartScale * (dist / st.pinchBase)).coerceIn(0.5f, 2.5f)
                    savePerBtnScale(key, s)
                    refreshControlScale(view, key)
                    return true
                }
                moveByTranslation(
                    view,
                    st.startTx + (ev.rawX - st.downX),
                    st.startTy + (ev.rawY - st.downY)
                )
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (st.dragging && ev.actionMasked == MotionEvent.ACTION_UP) {
                    val dx = ev.rawX - st.downX
                    val dy = ev.rawY - st.downY
                    // 布局模式下"按下即拖",但没挪动(轻点)仍要还原点击行为:
                    //   否则悬浮球点一下没反应 —— 就是主人说的"拖拽优先级大于点击"。
                    if (dx * dx + dy * dy <= padSlopSq) {
                        view.performClick()
                    } else {
                        saveOverlayPos(view, key)
                        if (view === imeProxy) {
                            imeBarUserDy = view.translationY + imeHeightPx
                            saveImeBarOffset()
                        }
                    }
                }
                st.dragging = false
                // ★ 松手把 Z 放回基础值(布局模式里也一样),否则它会永久压在悬浮球上。
                restoreBaseZ(view)
                return true
            }
        }
        return true
    }

    /**
     * 统一拖动能力(给"平时只响应点击"的控件用):
     *   · 布局调整模式:按下即拖,不触发点击(所有控件都能拖);
     *   · 平时:只有白名单控件(悬浮球/软键盘钮)能长按拖动 —— 或者设置里
     *     打开了「默认可直接拖动全部浮层控件」。其余控件平时只响应点击,
     *     避免打游戏时误拖。
     */
    private fun attachDraggable(view: View?, key: String) {
        view ?: return
        // ★ 拖动权限在触摸发生时**实时**判定(isDragEnabled 每次读设置),
        //   这样「各控件平时拖动开关」改完立刻生效,不用重启。
        // 非 clickable 的 View 收不到后续 MOVE(DOWN 被判为不感兴趣),补齐可点击性。
        if (!view.isClickable) view.isClickable = true
        view.setOnTouchListener { v, ev ->
            val st = padDragState(v)
            val allowed = canDragNow(isAlwaysDraggable(v.id) || isDragEnabled(key))
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    st.dragging = layoutMode
                    st.downX = ev.rawX
                    st.downY = ev.rawY
                    st.startTx = v.translationX
                    st.startTy = v.translationY
                    if (layoutMode) {
                        raiseAboveOthers(v)
                        selectLayoutTarget(v, key)
                    }
                    layoutMode
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!allowed) return@setOnTouchListener false
                    val dx = ev.rawX - st.downX
                    val dy = ev.rawY - st.downY
                    if (!st.dragging && dx * dx + dy * dy > padSlopSq) {
                        st.dragging = true
                        raiseAboveOthers(v)
                    }
                    if (st.dragging) {
                        moveByTranslation(v, st.startTx + dx, st.startTy + dy)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (st.dragging && ev.actionMasked == MotionEvent.ACTION_UP) {
                        saveOverlayPos(v, key)
                    }
                    val dragged = st.dragging
                    st.dragging = false
                    // ★ 松手必须把 Z 放回基础值,否则这个控件会永久压在悬浮球上面。
                    restoreBaseZ(v)
                    dragged // 拖动过就吃掉这次点击,避免"拖完还触发功能"
                }
                else -> false
            }
        }
    }

    /** 抬到最上层,避免被手柄按键等自带 elevation 的控件盖住(拖到中间"消失"的根因)。 */
    private fun raiseAboveOthers(view: View) {
        if (view.elevation < 1000f) setLayerZ(view, 1000f)
        view.bringToFront()
    }

    private fun saveImeBarOffset() {
        val parent = imeProxy?.parent as? ViewGroup ?: return
        if (parent.height <= 0) return
        getSharedPreferences("pad_layout", Context.MODE_PRIVATE)
            .edit()
            .putFloat("imebar_dy", imeBarUserDy / parent.height)
            .apply()
    }

    private fun restoreImeBarOffset() {
        val bar = imeProxy ?: return
        val parent = bar.parent as? ViewGroup ?: return
        val sp = getSharedPreferences("pad_layout", Context.MODE_PRIVATE)
        bar.post {
            val p = bar.parent as? ViewGroup ?: return@post
            imeBarUserDy = sp.getFloat("imebar_dy", 0f) * p.height
            applyImeBarPosition(0)
        }
        // 让父布局参与一次测量,避免 parent 尚未布局导致比例为 0
        parent.requestLayout()
    }

    /**
     * 悬浮球菜单。
     *
     * ★ 按主人的要求把"即时生效"和"重启生效"分成两个入口:
     *   ① 版本选择 —— 换游戏版本/服务器,**必须重启**才生效(放第一行);
     *   ② 控件设置 —— 显示开关/按键大小/摇杆/渲染分辨率/布局,**点完立刻生效**;
     *   ③ 诊断日志 —— 日志开关 + 实时日志 + 导出日志(系统分享);
     *   ④⑤ 缓存相关。
     */
    private fun showBallMenu() {
        // ★ 主人要求:主菜单只写干净的名字(不带「(即时生效)」这类后缀),
        //   进到子菜单里再标注生效方式。
        val items = arrayOf(
            "卡死重启",
            "版本选择",
            "控件与按键",
            "图像设置",
            "日志与诊断",
            "缓存管理",
            "关于与帮助"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmRestart()
                    1 -> showVersionDialog()
                    2 -> showControlsPanel()
                    3 -> showGraphicsPanel()
                    4 -> showLogMenu()
                    5 -> showCacheSettings()      // 清除 + 上限设置 合并成一个菜单
                    6 -> showAboutDialog()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 布局调整模式能拖的所有控件 → 各自的持久化键。 */
    private fun layoutTargets(): List<Pair<View, String>> {
        val root = overlayLayout ?: return emptyList()
        val list = ArrayList<Pair<View, String>>()
        root.findViewById<View>(R.id.gamepad_panel)?.let { panel ->
            for (b in gatherAllDescendantsOfType<Button>(panel, Button::class.java)) {
                list.add(b to ("pad_" + ((b.tag as? String) ?: b.id)))
            }
        }
        fun add(id: Int, key: String) {
            root.findViewById<View>(id)?.let { list.add(it to key) }
        }
        add(R.id.button_toggle, "btn_toggle")
        add(R.id.button_ime, "btn_ime")
        add(R.id.button_hud, "btn_hud")
        add(R.id.perf_hud, "perf_hud")
        add(R.id.button_settings, "settings_ball")
        add(R.id.joystick_panel, "joystick")
        imeProxy?.let { list.add(it to "ime_bar") }
        return list
    }

    private fun saveAllLayoutPositions() {
        for ((v, key) in layoutTargets()) {
            saveOverlayPos(v, key)
            if (v === imeProxy) {
                imeBarUserDy = v.translationY + imeHeightPx
                saveImeBarOffset()
            }
        }
    }

    /** 布局编辑里给控件取的中文名(给底部面板显示"当前在调哪个键")。 */
    private fun btnName(key: String): String {
        if (key.startsWith("pad_")) {
            return when (key.removePrefix("pad_")) {
                "up" -> "↑ 上"
                "down" -> "↓ 下"
                "left" -> "← 左"
                "right" -> "→ 右"
                "space" -> "空格"
                else -> "手柄键"
            }
        }
        return when (key) {
            "btn_hud" -> "状态框开关钮"
            "perf_hud" -> "状态框"
            "settings_ball" -> "悬浮球"
            "joystick" -> "摇杆"
            "ime_bar" -> "输入条"
            "btn_toggle" -> "手柄显隐钮"
            "btn_ime" -> "软键盘钮"
            "btn_joystick_toggle" -> "摇杆显隐钮"
            else -> key
        }
    }

    /** 点选一个控件(高亮),之后底部滑块调的就是它的大小。 */
    private fun selectLayoutTarget(v: View, key: String) {
        if (layoutSelectedView === v) return
        clearLayoutSelection()
        layoutOrigBg = v.background
        v.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x33FFEB3B)                   // 半透明黄底,仍能看见控件(字面量本身即 Int)
            setStroke(dp(2), 0xFFFFC107.toInt())  // 黄色边框(王者那种选中态)
            setCornerRadius(dp(6).toFloat())
        }
        layoutSelectedView = v
        layoutSelectedKey = key
        updateLayoutSizeUi()
    }

    /** 取消点选(恢复原背景)。 */
    private fun clearLayoutSelection() {
        // ★ 必须无条件还原(原背景可能本来就是 null),否则黄框会永久留在控件上。
        layoutSelectedView?.background = layoutOrigBg
        layoutSelectedView = null
        layoutSelectedKey = null
        layoutOrigBg = null
    }

    /** 同步底部面板:显示当前调节对象 + 百分比,并把滑块拨到对应位置。 */
    private fun updateLayoutSizeUi() {
        val label = layoutSizeLabel ?: return
        val seek = layoutSizeSeek ?: return
        val key = layoutSelectedKey
        val pct = if (key != null) (perBtnScale(key) * 100).toInt()
        else (globalBtnScale * 100).toInt()
        label.text = if (key != null)
            "按键大小(单个): ${btnName(key)}  $pct%"
        else
            "按键大小(整体): $pct%  — 点任意按键可单独调"
        val np = (pct - 50).coerceIn(0, 200)
        if (seek.progress != np) seek.progress = np
    }

    /** 进入布局调整:先快照当前坐标,顶部挂一条带「保存」「退出」的操作条。 */
    private fun enterLayoutMode() {
        if (layoutMode) return
        layoutMode = true
        val snap = LinkedHashMap<View, FloatArray>()
        for ((v, _) in layoutTargets()) snap[v] = floatArrayOf(v.translationX, v.translationY)
        layoutSnapshot = snap
        layoutSnapshotDy = imeBarUserDy
        // ★ 手柄面板若处于隐藏状态,方向键/空格根本看不见也拖不动,进编辑界面先显示。
        overlayLayout?.findViewById<View>(R.id.gamepad_panel)?.visibility = View.VISIBLE
        putUiPref("show_gamepad_panel", true)

        val root = overlayLayout ?: return

        // ── 顶部操作条:恢复默认 / 保存 / 退出(王者「自定义布局」的同款三件套)──
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xEE1565C0.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            // 布局编辑的操作条要在最上层(比悬浮球还高),同样不留阴影。
            elevation = 1100f
            if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
                outlineSpotShadowColor = Color.TRANSPARENT
                outlineAmbientShadowColor = Color.TRANSPARENT
            }
            val lp = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            lp.topMargin = dp(4)
            layoutParams = lp
        }
        bar.addView(TextView(this).apply {
            text = "布局调整:拖动摆放 · 点选后调大小"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(this).apply {
            text = "恢复默认"
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                resetLayout()
                clearLayoutSelection()
                updateLayoutSizeUi()
            }
        })
        bar.addView(Button(this).apply {
            text = "保存"
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { exitLayoutMode(true) }
        })
        bar.addView(Button(this).apply {
            text = "退出"
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener { exitLayoutMode(false) }
        })
        root.addView(bar)
        layoutHint = bar

        // ── 底部「按键大小」面板(手游自定义布局的尺寸滑块)──
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE1565C0.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            elevation = 1100f
            if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
                outlineSpotShadowColor = Color.TRANSPARENT
                outlineAmbientShadowColor = Color.TRANSPARENT
            }
            val lp = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }
        val sizeLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(2))
        }
        val sizeSeek = SeekBar(this).apply {
            max = 200 // 50 + progress = 实际百分比(50%~250%)
            progress = ((globalBtnScale * 100).toInt() - 50).coerceIn(0, 200)
        }
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyLayoutSize(p + 50)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layoutSizeLabel = sizeLabel
        layoutSizeSeek = sizeSeek
        panel.addView(sizeLabel)
        panel.addView(sizeSeek)
        // 一键档位(王者那种):75/100/125/150
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presetRow.addView(TextView(this).apply {
            text = "一键:"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, dp(6), 0)
        })
        for (pct in listOf(75, 100, 125, 150)) {
            presetRow.addView(Button(this).apply {
                text = "$pct%"
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener { applyLayoutSize(pct) }
            })
        }
        presetRow.addView(Button(this).apply {
            text = "取消选择"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener {
                clearLayoutSelection()
                updateLayoutSizeUi()
            }
        })
        panel.addView(presetRow)
        // ★ 说清"保存/退出"的语义,免得辛苦摆好的位置被误丢。
        panel.addView(TextView(this).apply {
            text = "保存 = 应用并退出;退出 = 放弃本次未保存的改动。点任意按键可选中后单独调大小。"
            textSize = 11f
            setTextColor(0xFFE3F2FD.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(panel)
        layoutPanel = panel
        updateLayoutSizeUi()

        android.widget.Toast.makeText(this, "布局调整已开启", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 底部面板统一入口:有选中就调那个键,没选中就调整体。pct = 50~250。 */
    private fun applyLayoutSize(pct: Int) {
        val f = pct / 100f
        val key = layoutSelectedKey
        if (key != null) {
            savePerBtnScale(key, f.coerceIn(0.5f, 2.5f))
            layoutSelectedView?.let { refreshControlScale(it, key) }
        } else {
            globalBtnScale = f.coerceIn(0.5f, 2.0f)
            refreshAllControlScales()
        }
        updateLayoutSizeUi()
    }

    /**
     * 退出布局调整。
     *  · keep = true :保存当前摆放并退出;
     *  · keep = false:还原到进入前的摆放(放弃本次调整)再退出。
     */
    private fun exitLayoutMode(keep: Boolean) {
        if (!layoutMode) return
        layoutMode = false
        val root = overlayLayout
        layoutHint?.let { root?.removeView(it) }
        layoutHint = null
        layoutPanel?.let { root?.removeView(it) }
        layoutPanel = null
        // 退出前必须还原选中控件的高亮背景,否则它会一直顶着黄框。
        clearLayoutSelection()
        layoutSizeLabel = null
        layoutSizeSeek = null
        if (!keep) {
            layoutSnapshot?.forEach { (v, p) -> moveByTranslation(v, p[0], p[1]) }
            imeBarUserDy = layoutSnapshotDy
            saveImeBarOffset()
            imeProxy?.let { applyImeBarPosition(imeHeightPx) }
        }
        layoutSnapshot = null
        saveAllLayoutPositions()
        // 退出时把可能拖出可视区的控件拉回来(自愈,避免"拖完某个键不见了")。
        root?.let { clampAllIntoView(it) }
        android.widget.Toast.makeText(
            this,
            if (keep) "布局已保存" else "已放弃本次调整",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleLayoutMode() {
        if (layoutMode) exitLayoutMode(true) else enterLayoutMode()
    }

    /** 所有可拖动控件:确保仍在可视范围内。 */
    private fun clampAllIntoView(root: View) {
        val keys = listOf(
            R.id.button_up, R.id.button_down, R.id.button_left, R.id.button_right,
            R.id.button_space, R.id.button_toggle, R.id.button_joystick_toggle, R.id.button_ime, R.id.button_hud,
            R.id.perf_hud, R.id.button_settings
        )
        for (id in keys) {
            root.findViewById<View>(id)?.let { v ->
                if (v.visibility == View.VISIBLE) moveByTranslation(v, v.translationX, v.translationY)
            }
        }
        imeProxy?.let { v -> if (v.visibility == View.VISIBLE) applyImeBarPosition(imeHeightPx) }
    }

    // ── 资源缓存 ────────────────────────────────────────────
    //
    // Rust 侧(ruffle 的 fetch 通道)把 swf/xml/mp3/图片落到这个目录,
    //   第二进同一场景直接命中磁盘,不再回服务器。这里提供手动清除。
    //   ★ 路径必须和 Rust 里的一致:android_storage_dir / "mole_cache",
    //     android_storage_dir = filesDir/ruffle/shared_objects。
    private fun moleCacheDir(): java.io.File =
        java.io.File("${filesDir.absolutePath}/ruffle/shared_objects", "mole_cache")

    private fun dirSize(f: java.io.File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        var s = 0L
        f.listFiles()?.forEach { s += dirSize(it) }
        return s
    }

    // MoleRuffle:缓存上限(MB),0 = 不限制。默认 2GB(现在手机存储/运存都很宽裕),按最久未用自动淘汰。
    private var cacheLimitMb: Long
        // 默认 1GB:现在手机存储普遍 128GB 起、运存 8GB 起,缓存放宽点能让第二次进同一
        //   场景基本全命中(实测 346 次命中 vs 312 次请求)。可在「资源缓存」里改或一键清除。
        get() = getSharedPreferences("ui", Context.MODE_PRIVATE).getLong("cache_limit_mb", 2048)
        set(v) {
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putLong("cache_limit_mb", v).apply()
        }

    /** 启动时把上限推给 Rust,并先裁一次。 */
    private fun applyCacheLimit() {
        try {
            nativeSetCacheLimit(cacheLimitMb)
        } catch (_: Throwable) {
        }
        try {
            nativeTrimCache(cacheLimitMb)
        } catch (_: Throwable) {
        }
    }

    /** 「控件与按键」→「悬浮窗」子菜单:全局按键大小(手柄键/摇杆/悬浮控件)。 */
    private fun showFloatingPanel() {
        val pad = dp(16)
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        box.addView(TextView(this).apply {
            text = "按键大小"
            textSize = 15f
            setTextColor(0xFF2E7D32.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
            compoundDrawablePadding = dp(8)
            setCompoundDrawables(icon(R.drawable.ic_gamepad, 0xFF2E7D32.toInt(), 18), null, null, null)
        })
        val scaleLabel = TextView(this).apply {
            text = "全局缩放: ${(globalBtnScale * 100).toInt()}%"
            textSize = 14f
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        val seek = SeekBar(this).apply {
            max = 150               // 50% ~ 200%
            progress = ((globalBtnScale - 0.5f) * 100f).toInt().coerceIn(0, 150)
            setPadding(dp(8), 0, dp(8), 0)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val s = (p + 50) / 100f
                scaleLabel.text = "全局缩放: ${(s * 100).toInt()}%"
                if (fromUser) {
                    globalBtnScale = s
                    refreshAllControlScales()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // 一键档位
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        for (pct in listOf(150, 125, 100, 75)) {
            presetRow.addView(android.widget.Button(this).apply {
                text = "$pct%"
                textSize = 13f
                setOnClickListener {
                    globalBtnScale = pct / 100f
                    refreshAllControlScales()
                    seek.progress = ((globalBtnScale - 0.5f) * 100f).toInt().coerceIn(0, 150)
                    scaleLabel.text = "全局缩放: $pct%"
                }
            })
        }
        box.addView(scaleLabel)
        box.addView(seek)
        box.addView(presetRow)
        box.addView(TextView(this).apply {
            text = "作用于:悬浮球、手柄键(方向键/空格)、摇杆、状态框开关等所有浮层控件。\n" +
                "单独改某一个键:进「调整布局」,在那个键上**双指捏合**(0.5x~2.5x,单独记忆)。"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(8), dp(6), dp(8), 0)
        })
        scroll.addView(box)
        android.app.AlertDialog.Builder(this)
            .setTitle("悬浮窗(即时生效)")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 「日志与诊断」→「日志操作」子菜单:实时日志 / 导出日志 / 清除日志。 */
    private fun showLogActionsDialog() {
        val items = arrayOf("实时日志(自动刷新,可复制)", "导出日志(系统分享)", "清除日志")
        android.app.AlertDialog.Builder(this)
            .setTitle("日志操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLogDialog()
                    1 -> exportLog()
                    2 -> android.app.AlertDialog.Builder(this)
                        .setTitle("清除日志")
                        .setMessage("确定清空 mole.log?")
                        .setPositiveButton("清除") { _, _ ->
                            try {
                                logFile().delete()
                                android.widget.Toast.makeText(this, "日志已清除", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (_: Throwable) {
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 「关于与帮助」→「使用说明」。 */
    private fun showHelpDialog() {
        val text = listOf(
            "【悬浮球菜单】",
            "· 版本选择:平行服各版本 / 淘米官方服 / 检查更新(均**重启生效**)",
            "· 控件与按键:布局调整、各控件显隐、摇杆、**悬浮窗**(按键大小)——即时生效",
            "· 图像设置:渲染分辨率(滑条/25~100 一键)、渲染画质(高/中/低)、帧率上限——即时生效;渲染后端——重启生效",
            "· 日志与诊断:日志开关与保留天数 + 日志操作(实时/导出/清除)",
            "· 缓存管理:查看占用、设置上限(MB)、一键清除",
            "· 关于与帮助:版本信息、使用说明、常见问题",
            "",
            "【操作】",
            "· 走路:点地图即可;也可用手柄方向键或摇杆",
            "· 摇杆:左下角圆钮,拖动即可;可在「控件与按键 → 摇杆」里开「跟随手指」和调灵敏度",
            "· 拖动控件:默认只有悬浮球和键盘钮可直接拖;其余进「调整布局」后可拖,双击捏合可单独改大小",
            "· 打字:点游戏输入框会弹出系统输入法;收起键盘会自动退出输入态",
            "",
            "【建议设置】",
            "· 觉得卡:图像设置 → 分辨率 75% + 画质「低」",
            "· 想看清:分辨率 100% + 画质「高」"
        ).joinToString("\n")
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("使用说明")
            .setView(ScrollView(this).apply { addView(tv) })
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 「关于与帮助」→「常见问题」。 */
    private fun showFaqDialog() {
        val text = listOf(
            "Q1 打开黑屏 / 进场景闪退?",
            "A  v5.6 起渲染后端**默认 OpenGL ES**(绕开小米 Adreno 的 Vulkan 驱动崩溃,这是闪退主因)。",
            "   想要更好画质可在「图像设置 → 渲染后端」切回 Vulkan;若又闪退就切回 OpenGL ES。",
            "   卡顿再配合:图像设置 → 分辨率 75% 或 50% 降低填充率。",
            "",
            "Q2 动画一顿一顿 / 不流畅?",
            "A  摩尔庄园的动画是 Flash 时间轴驱动,**原生就是 24FPS**,调高帧率不会更顺。",
            "   想更顺:分辨率 75% + 画质「低」(关掉位图平滑等开销)。另外开着日志会掉帧,",
            "   平时记得在「日志与诊断」里关掉日志记录。",
            "",
            "Q3 打字后点不动游戏里其它元素?",
            "A  收起键盘会自动清理游戏的输入态。若仍异常,再点一次输入框或重启即可。",
            "",
            "Q4 为什么地图/任务每次都要重新加载?",
            "A  美术资源(地图 swf/xml、任务 swf)已走本地缓存(基本秒开)。",
            "   每次走网络的是**服务器实时数据**(进入地图、场景用户信息、任务进度),那些",
            "   本来就不能缓存 —— 缓存了反而会看到过期状态。",
            "",
            "Q5 缓存占空间吗?能清吗?",
            "A  「缓存管理」里可看占用、设上限(默认 2GB,超出按最久未用自动淘汰),也可一键清除。",
            "",
            "Q6 日志时间不对?",
            "A  已修:日志时间戳按手机本地时区(会自动跟随系统时区/夏令时)。",
            "",
            "Q7 淘米官方服连不上?",
            "A  官方服(mole.61.com)是否仍在运营以实际连接为准;连不上就切回平行服主版本。",
            "",
            "Q8 为什么日志/崩溃信息里出现过 C 盘路径?",
            "A  那是**编译机**的源码路径(Rust 会把编译时的路径写进 panic 信息),不是手机上的。",
            "   已处理:写日志和弹崩溃框前会把这类绝对路径替换成「<路径>」。"
        ).joinToString("\n")
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(dp(18), dp(14), dp(18), dp(10))
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("常见问题")
            .setView(ScrollView(this).apply { addView(tv) })
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 悬浮球 →「图像设置」:渲染分辨率 + 帧率上限 —— **全部即时生效**。 */
    private fun showGraphicsPanel() {
        val pad = dp(16)
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(text: String, iconRes: Int? = null) {
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(0xFF2E7D32.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, if (box.childCount == 0) 0 else pad, 0, dp(6))
                if (iconRes != null) {
                    compoundDrawablePadding = dp(8)
                    setCompoundDrawables(icon(iconRes, 0xFF2E7D32.toInt(), 18), null, null, null)
                }
            })
        }
        fun note(text: String) {
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(dp(8), 0, dp(8), dp(6))
            })
        }

        // ★ 打开面板时要给各单选组设初始选中态(.check()),但监听器已挂上会被触发 →
        //   误弹 Toast/重启框。加 initializing 标志抑制,设完初始态再放开。
        var initializing = true

        // ── 渲染分辨率 ──
        // ★ v5.5 方案②:改用 ruffle 官方 set_viewport_dimensions 安全重建交换链(不销毁 OS
        //   surface、不泄漏驱动资源),可实时切换,不再需要重启生效。
        header("渲染分辨率", R.drawable.ic_chart)
        val scaleGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val scaleIds = LinkedHashMap<Int, Int>()
        for (pct in listOf(100, 75, 50, 25)) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "$pct%"
                textSize = 15f
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            scaleIds[pct] = rb.id
            scaleGroup.addView(rb)
        }
        // 当前值标签(滑动条和单选共用)
        val scaleLabel = TextView(this).apply {
            text = "当前: ${renderScalePct}%"
            textSize = 14f
            setPadding(dp(8), dp(2), dp(8), dp(4))
        }
        // 滑动条:25% ~ 100% 连续可调
        val scaleSeek = SeekBar(this).apply {
            max = 75                       // 25 + progress = 实际百分比
            progress = (renderScalePct - 25).coerceIn(0, 75)
            setPadding(dp(8), 0, dp(8), 0)
        }

        fun applyScale(pct: Int, fromUser: Boolean) {
            val p = pct.coerceIn(25, 100)
            scaleLabel.text = "当前: $p%"
            if (scaleSeek.progress != p - 25) scaleSeek.progress = p - 25
            scaleIds[p]?.let { if (scaleGroup.checkedRadioButtonId != it) scaleGroup.check(it) }
            if (fromUser) {
                renderScalePct = p
                try { nativeSetRenderScale(p) } catch (_: Throwable) {}
                android.widget.Toast.makeText(
                    this, "渲染分辨率 $p%", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        scaleGroup.setOnCheckedChangeListener { _, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            val pct = scaleIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            applyScale(pct, true)
        }
        scaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = (p + 25).coerceIn(25, 100)
                scaleLabel.text = "当前: $v%"
                if (fromUser) {
                    renderScalePct = v
                    try { nativeSetRenderScale(v) } catch (_: Throwable) {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        scaleGroup.check(scaleIds[renderScalePct] ?: scaleIds[100]!!)
        box.addView(scaleLabel)
        box.addView(scaleGroup)
        box.addView(scaleSeek)
        // 一键档位:25/50/75/100,一点就能对比
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        for (pct in listOf(100, 75, 50, 25)) {
            presetRow.addView(android.widget.Button(this).apply {
                text = "$pct%"
                textSize = 13f
                setOnClickListener {
                    applyScale(pct, true)
                }
            })
        }
        box.addView(presetRow)
        note(
            "原生版按屏幕物理像素渲染(本机 3198×2000),降采样后填充率按平方下降、明显更流畅," +
                "代价是画面变糊。默认 100%;觉得卡先试 75%,还卡再试 50%。"
        )

        // ── 渲染画质 ──
        // ★ v5.5:画质原本就是实时 set_quality,恢复即时生效(运行时改画质安全,不碰 surface)。
        header("渲染画质", R.drawable.ic_chart)
        val qGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val qIds = LinkedHashMap<Int, Int>()
        for ((lv, name) in listOf(2 to "高", 1 to "中", 0 to "低")) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = name
                textSize = 15f
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            qIds[lv] = rb.id
            qGroup.addView(rb)
        }
        qGroup.setOnCheckedChangeListener { _, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            val lv = qIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            renderQuality = lv
            try { nativeSetQuality(lv) } catch (_: Throwable) {}
            android.widget.Toast.makeText(
                this,
                "渲染画质:${if (lv == 2) "高" else if (lv == 1) "中" else "低"}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        qGroup.check(qIds[renderQuality] ?: qIds[2]!!)
        box.addView(qGroup)
        note(
            "低画质会关掉位图平滑等开销 —— 十几年前的 Flash 内容用**低画质 + 75% 分辨率**" +
                "通常最顺(画面会糙一点);想看清楚再调回高。"
        )

        // ── 帧率上限 ──
        header("帧率上限(即时生效)", R.drawable.ic_chart)
        val fpsGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val fpsIds = LinkedHashMap<Int, Int>()
        for (f in listOf(0, 60, 30, 24)) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = if (f == 0) "不限" else "$f"
                textSize = 15f
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            fpsIds[f] = rb.id
            fpsGroup.addView(rb)
        }
        fpsGroup.setOnCheckedChangeListener { _, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            val f = fpsIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            fpsCap = f
            try {
                nativeSetFpsCap(f)
            } catch (_: Throwable) {
            }
            android.widget.Toast.makeText(
                this,
                if (f == 0) "帧率上限:不限" else "帧率上限:$f FPS",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        fpsGroup.check(fpsIds[fpsCap] ?: fpsIds[0]!!)
        box.addView(fpsGroup)
        note(
            "摩尔庄园的动画是 Flash 时间轴驱动的(原生 24FPS),**调高上限不会让它更流畅**; " +
                "这个开关是用来**限帧省电/控温**的(比如设 30 或 24 会更凉、更省电)。想要更顺,请调上面的渲染分辨率。"
        )

        // ── 渲染后端(崩溃绕行)—— ★重启生效,与上面的即时项不同 ──
        header("渲染后端(重启生效)", R.drawable.ic_chart)
        val backendGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val bIds = LinkedHashMap<String, Int>()
        for ((key, label) in listOf(
            "gl" to "OpenGL ES(默认·稳定)",
            "vulkan" to "Vulkan(画质更好·部分机型驱动易崩)"
        )) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            bIds[key] = rb.id
            backendGroup.addView(rb)
        }
        backendGroup.setOnCheckedChangeListener { _, checkedId ->
            if (initializing) return@setOnCheckedChangeListener
            val key = bIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString("render_backend", key).apply()
            val label = if (key == "gl") "OpenGL ES" else "Vulkan"
            // 后端需重启才生效 → 弹确认直接重启(避免用户忘记)。
            android.app.AlertDialog.Builder(this)
                .setTitle("渲染后端已选: $label")
                .setMessage("渲染后端需重启游戏后生效(用于绕开部分机型的 Vulkan 驱动崩溃)。立即重启?")
                .setPositiveButton("立即重启") { _, _ -> restartApp() }
                .setNegativeButton("稍后重启", null)
                .show()
        }
        bIds[renderBackendPref()]?.let { backendGroup.check(it) }
        box.addView(backendGroup)
        note(
            "渲染后端 = 用哪个 GPU API 画画面。**OpenGL ES 更稳**(绕开小米 Adreno 的 Vulkan 驱动崩溃、闪退); " +
                "Vulkan 理论上画质/性能更好,但这台机型的 Vulkan 驱动有 bug。改后端需**重启游戏**才生效。"
        )
        initializing = false // 各组初始选中态已设完,之后用户改动才触发监听器
        scroll.addView(box)
        android.app.AlertDialog.Builder(this)
            .setTitle("图像设置")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    // MoleRuffle:帧率上限(0 = 不限),图像设置里选,即时生效。
    private var fpsCap: Int
        get() = getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("fps_cap", 0)
        set(v) {
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putInt("fps_cap", v).apply()
        }
    // ★ 名字不能叫 setFpsCap —— 会和上面 fpsCap 属性的 setter 撞 JVM 签名。
    // ★ v5.5:分辨率恢复运行时实时改(走 ruffle 官方 set_viewport_dimensions,安全),恢复声明。
    private external fun nativeSetRenderScale(pct: Int)
    private external fun nativeSetFpsCap(fps: Int)
    private external fun nativeSetQuality(level: Int)

    /** 渲染画质:2=高 1=中 0=低(即时生效)。 */
    private var renderQuality: Int
        get() = getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("render_quality", 2)
        set(v) {
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putInt("render_quality", v).apply()
        }

    /** 悬浮球 →「关于」:版本/内核/当前设置/缓存占用 + 检查更新。 */
    private fun showAboutDialog() {
        val pm = packageManager
        val pi = try {
            pm.getPackageInfo(packageName, 0)
        } catch (_: Throwable) {
            null
        }
        val verName = pi?.versionName ?: "未知"
        val verCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pi?.longVersionCode ?: 0
        } else {
            @Suppress("DEPRECATION")
            (pi?.versionCode ?: 0).toLong()
        }
        val installTime = pi?.lastUpdateTime?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
        } ?: "未知"

        val usedMb = dirSize(moleCacheDir()) / 1024.0 / 1024.0
        val qName = if (renderQuality == 2) "高" else if (renderQuality == 1) "中" else "低"
        val fpsName = if (fpsCap == 0) "不限" else "$fpsCap FPS"

        val info = listOf(
            "客户端版本: $verName (build $verCode)",
            "安装/更新: $installTime",
            "Ruffle 内核: ${RUFFLE_REV}",
            "内核来源: $CORE_SOURCE",
            "当前渲染: ${renderScalePct}% 分辨率 / 画质 $qName / 帧率 $fpsName",
            "资源缓存: 已用 %.1f MB / 上限 %d MB".format(usedMb, cacheLimitMb),
            "游戏服务器: $PARALLEL_BASE",
            "自动更新: 未配置(无托管服务器);「检查更新」目前只比对游戏资源版本"
        ).joinToString("\n")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(6))
        }
        box.addView(TextView(this).apply {
            text = info
            textSize = 14f
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
        })
        // 帮助子菜单
        val helpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        helpRow.addView(android.widget.Button(this).apply {
            text = "使用说明"
            textSize = 14f
            setOnClickListener { showHelpDialog() }
        })
        helpRow.addView(android.widget.Button(this).apply {
            text = "常见问题"
            textSize = 14f
            setOnClickListener { showFaqDialog() }
        })
        box.addView(helpRow)

        android.app.AlertDialog.Builder(this)
            .setTitle("关于与帮助")
            .setView(box)
            .setNeutralButton("检查更新") { _, _ -> runUpdateCheck() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 版本选择 / 关于 里的「检查更新」。 */
    private fun runUpdateCheck() {
        val pending = android.app.ProgressDialog.show(this, "检查更新", "正在连接版本服务器…", true, false)
        checkGameVersion { result ->
            try {
                pending.dismiss()
            } catch (_: Throwable) {
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("检查更新")
                .setMessage(result)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /**
     * 比对平行服的版本标识(`/version/zzz_config.txt`),判断游戏资源是否有更新。
     * 目前没有独立的"客户端更新服务器",所以检查的是**游戏资源版本**;
     * 客户端本身的版本号在「关于」里查看。
     */
    private fun checkGameVersion(onResult: (String) -> Unit) {
        Thread {
            var msg: String
            try {
                val url = java.net.URL("$PARALLEL_BASE/version/zzz_config.txt?${System.currentTimeMillis()}")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                val body = if (code == 200) {
                    conn.inputStream.bufferedReader().readText().trim()
                } else {
                    ""
                }
                conn.disconnect()
                val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val prev = sp.getString("game_version_seen", null)
                msg = when {
                    code != 200 -> "无法连接版本服务器(HTTP $code)。请检查网络后重试。"
                    body.isEmpty() -> "服务器返回空内容,无法判断。"
                    prev == null -> "当前游戏版本标识:\n$body\n\n(已记录,下次检查时用于比对)"
                    prev == body -> "已是最新。\n游戏版本标识 $body 未变化。"
                    else -> "游戏资源有更新:\n$prev  →  $body\n\n建议:悬浮球 → 资源缓存 → 清除全部,再重启游戏。"
                }
                if (code == 200 && body.isNotEmpty()) {
                    sp.edit().putString("game_version_seen", body).apply()
                }
            } catch (e: Throwable) {
                msg = "检查失败:${e.message ?: e}"
            }
            runOnUiThread { onResult(msg) }
        }.start()
    }

    /** 悬浮球 →「资源缓存」:当前占用 + 上限(MB)+ 立即清除 + 保存并裁剪,**合并成一个菜单**。 */
    private fun showCacheSettings() {
        val dir = moleCacheDir()
        val usedMb = dirSize(dir) / 1024.0 / 1024.0
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cacheLimitMb.toString())
            textSize = 15f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "当前缓存占用: %.1f MB".format(usedMb)
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        })
        box.addView(TextView(this).apply {
            text = "缓存上限(MB,0 = 不限制):超出后按「最久未用」自动淘汰"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        box.addView(input)
        android.app.AlertDialog.Builder(this)
            .setTitle("缓存管理")
            .setView(box)
            // 清除全部
            .setNeutralButton("清除全部") { _, _ -> clearResourceCache() }
            // 保存上限并裁剪
            .setPositiveButton("保存并裁剪") { _, _ ->
                val v = input.text.toString().toLongOrNull() ?: 2048
                cacheLimitMb = v.coerceAtLeast(0)
                applyCacheLimit()
                android.widget.Toast.makeText(
                    this,
                    "上限已设为 %d MB,已裁剪到 %.1f MB".format(
                        cacheLimitMb,
                        dirSize(moleCacheDir()) / 1024.0 / 1024.0
                    ),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // MoleRuffle:设置缓存上限(MB)/ 立即裁剪到上限(返回释放字节数)。
    //   ★ 名字不能叫 setCacheLimitMb —— 那会和上面 cacheLimitMb 属性的 setter 撞 JVM 签名。
    private external fun nativeSetCacheLimit(mb: Long)
    private external fun nativeTrimCache(maxMb: Long): Long

    private fun clearResourceCache() {
        val dir = moleCacheDir()
        val before = dirSize(dir)
        val mb = before / 1024.0 / 1024.0
        val ok = try {
            dir.deleteRecursively()
            true
        } catch (_: Throwable) {
            false
        }
        dir.mkdirs()
        android.widget.Toast.makeText(
            this,
            if (ok) "已清除资源缓存 %.1f MB(下次进场景会重新下载)".format(mb)
            else "清除缓存失败",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 恢复默认布局。
     *
     * ★ 以前的 bug:只清了 SharedPreferences,界面上控件的 translationX/Y 没复位,
     *   于是点了毫无变化(还提示"重启后生效")。现在**当场**把所有浮层控件的位置
     *   归零并重置 Z 序,立刻能看到效果,不再需要重启。
     */
    private fun resetLayout() {
        getSharedPreferences("pad_layout", Context.MODE_PRIVATE).edit().clear().apply()
        for ((v, key) in layoutTargets()) {
            v.translationX = 0f
            v.translationY = 0f
            v.scaleX = globalBtnScale
            v.scaleY = globalBtnScale
            restoreBaseZ(v)
        }
        // 手柄里的方向键/空格键也一起复位
        overlayLayout?.findViewById<View>(R.id.gamepad_panel)?.let { panel ->
            for (b in gatherAllDescendantsOfType<Button>(panel, Button::class.java)) {
                b.translationX = 0f
                b.translationY = 0f
                b.scaleX = globalBtnScale
                b.scaleY = globalBtnScale
            }
        }
        imeBarUserDy = 0f
        saveImeBarOffset()
        imeProxy?.let { applyImeBarPosition(imeHeightPx) }
        overlayLayout?.let { applyBaseElevations(it) }
        android.widget.Toast.makeText(this, "已恢复默认布局", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun putUiPref(key: String, value: Boolean) {
        getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    private fun getUiPref(key: String): Boolean {
        return getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(key, true)
    }

    /**
     * 是否允许"平时(非布局调整模式)直接拖动"某个浮层控件。
     *
     * 主人的取舍:默认只有悬浮球和软键盘钮能随手拖 —— 方向键/空格/状态框在
     * 打游戏时手指一滑就被拖跑、还打断走路,太容易误触。要整体重排就进
     * 「调整布局」(那里所有控件都能拖)。真想平时也随便拖,设置里打开
     * 「默认可直接拖动全部浮层控件」即可。
     */
    private fun canDragNow(alwaysAllowed: Boolean): Boolean =
        layoutMode || alwaysAllowed ||
            getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("drag_all_default", false)

    /** 该控件在"平时"是否属于允许随手拖的白名单(悬浮球 / 软键盘钮)。 */
    private fun isAlwaysDraggable(id: Int): Boolean =
        id == R.id.button_settings || id == R.id.button_ime

    /**
     * 某个控件是否允许"平时直接拖动"(不用进调整布局)。
     *   由「控件与按键 → 各控件平时拖动开关」里的勾选决定;悬浮球/键盘钮默认开。
     *   手柄键共用 drag_pad 一档;摇杆不在这里(它是操作件,平时拖动会破坏走位)。
     */
    private fun isDragEnabled(key: String): Boolean {
        val sp = getSharedPreferences("ui", Context.MODE_PRIVATE)
        return if (key.startsWith("pad_")) {
            sp.getBoolean("drag_pad", false)
        } else {
            val defaultOn = key == "settings_ball" || key == "btn_ime"
            sp.getBoolean("drag_$key", defaultOn)
        }
    }

    /** 按持久化配置应用各控件显隐(默认全显示)。 */
    private fun applyVisibilityPrefs(root: View) {
        val map = mapOf(
            "show_hud" to R.id.perf_hud,
            "show_btn_hud" to R.id.button_hud,
            "show_btn_ime" to R.id.button_ime,
            "show_btn_toggle" to R.id.button_toggle,
            "show_btn_joystick_toggle" to R.id.button_joystick_toggle,
            "show_btn_up" to R.id.button_up,
            "show_btn_down" to R.id.button_down,
            "show_btn_left" to R.id.button_left,
            "show_btn_right" to R.id.button_right,
            "show_btn_space" to R.id.button_space,
            "show_joystick" to R.id.joystick_panel,
            // ★ 手柄面板(方向键/空格)整体显隐:以前漏了这条,导致按钮关掉后重启又显示。
            "show_gamepad_panel" to R.id.gamepad_panel
        )
        for ((key, id) in map) {
            root.findViewById<View>(id)?.visibility =
                if (getUiPref(key)) View.VISIBLE else View.GONE
        }
        refreshJoystickZone()
    }

    /**
     * 摇杆触发区只在「摇杆已开启 + 跟随手指打开」时才显示。
     * ★ 平时必须 GONE:它整片吃触摸,否则会把左下角的游戏点击全挡掉。
     */
    private fun refreshJoystickZone() {
        val zone = overlayLayout?.findViewById<View>(R.id.joystick_zone) ?: return
        zone.visibility =
            if (getUiPref("show_joystick") && joystickFollowFinger) View.VISIBLE else View.GONE
    }

    /** ⚙ 控件与按键:布局 / 显示开关 / 摇杆 + 「悬浮窗」子菜单(按键大小)—— **全部即时生效**。 */
    private fun showControlsPanel() {
        val pad = dp(16)
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // ★ 分组标题也用矢量图标,不再用 Emoji(Emoji 各 ROM 字形/颜色不一致)。
        fun header(text: String, iconRes: Int? = null) {
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(0xFF2E7D32.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, pad, 0, dp(6))
                if (iconRes != null) {
                    compoundDrawablePadding = dp(8)
                    setCompoundDrawables(icon(iconRes, 0xFF2E7D32.toInt(), 18), null, null, null)
                }
            })
        }

        // ── 布局(即时生效,放最上面方便随手点)──
        header("布局", R.drawable.ic_layout)
        val layoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        layoutRow.addView(android.widget.Button(this).apply {
            text = if (layoutMode) "退出布局调整" else "调整布局"
            textSize = 14f
            setOnClickListener {
                if (layoutMode) {
                    exitLayoutMode(true)
                } else {
                    // ★ 必须先关掉本设置对话框 —— 否则它盖在布局编辑界面上面,控件根本拖不动。
                    //   (手游的做法也是:进「自定义布局」就切到独立编辑态,设置页退场。)
                    controlsDialog?.dismiss()
                    enterLayoutMode()
                }
            }
        })
        layoutRow.addView(android.widget.Button(this).apply {
            text = "恢复默认布局"
            textSize = 14f
            setOnClickListener { resetLayout() }
        })
        box.addView(layoutRow)

        // ── 显示开关 ──
        header("显示开关(即时生效)", R.drawable.ic_eye)
        data class Tgl(val key: String, val label: String, val id: Int, val iconRes: Int)
        val toggles = listOf(
            Tgl("show_gamepad_panel", "手柄面板(方向键/空格)整体", R.id.gamepad_panel, R.drawable.ic_gamepad),
            Tgl("show_hud", "状态框(帧率/内存/温度)", R.id.perf_hud, R.drawable.ic_chart),
            Tgl("show_btn_hud", "状态框开关按钮", R.id.button_hud, R.drawable.ic_chart),
            Tgl("show_btn_ime", "软键盘按钮", R.id.button_ime, R.drawable.ic_keyboard),
            Tgl("show_btn_toggle", "手柄显隐按钮", R.id.button_toggle, R.drawable.ic_gamepad),
            Tgl("show_btn_joystick_toggle", "摇杆显隐按钮(常驻「摇杆」钮)", R.id.button_joystick_toggle, R.drawable.ic_gamepad),
            Tgl("show_btn_up", "上方向键", R.id.button_up, R.drawable.ic_arrow_up),
            Tgl("show_btn_down", "下方向键", R.id.button_down, R.drawable.ic_arrow_down),
            Tgl("show_btn_left", "左方向键", R.id.button_left, R.drawable.ic_arrow_left),
            Tgl("show_btn_right", "右方向键", R.id.button_right, R.drawable.ic_arrow_right),
            Tgl("show_btn_space", "空格键", R.id.button_space, R.drawable.ic_space),
            Tgl("show_joystick", "虚拟摇杆(拖动走位)", R.id.joystick_panel, R.drawable.ic_gamepad)
        )
        for (t in toggles) {
            val cb = CheckBox(this).apply {
                textSize = 15f
                isChecked = getUiPref(t.key)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnCheckedChangeListener { _, checked ->
                    putUiPref(t.key, checked)
                    overlayLayout?.findViewById<View>(t.id)?.visibility =
                        if (checked) View.VISIBLE else View.GONE
                }
            }
            withIcon(cb, t.iconRes, t.label)
            box.addView(cb)
        }
        // 平时(非布局调整模式)是否允许随手拖动所有浮层控件。
        val cbDragAll = CheckBox(this).apply {
            textSize = 15f
            isChecked = getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("drag_all_default", false)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .edit().putBoolean("drag_all_default", checked).apply()
            }
        }
        withIcon(cbDragAll, R.drawable.ic_layout, "默认可直接拖动全部浮层控件")
        box.addView(cbDragAll)
        box.addView(TextView(this).apply {
            text = "默认只有「悬浮球」和「软键盘钮」能随手拖,其余控件请进「调整布局」;" +
                "打开上面这项目则平时也能直接拖(打游戏时容易误拖)。"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(8), 0, dp(8), dp(6))
        })

        // ── 摇杆 ──
        header("摇杆", R.drawable.ic_gamepad)
        val cbFollow = CheckBox(this).apply {
            textSize = 15f
            isChecked = joystickFollowFinger
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnCheckedChangeListener { _, checked ->
                joystickFollowFinger = checked
                refreshJoystickZone()
            }
        }
        withIcon(cbFollow, R.drawable.ic_layout, "摇杆跟随手指(左下区域按哪就出现在哪)")
        box.addView(cbFollow)

        val deadLabel = TextView(this).apply {
            text = "灵敏度:触发距离 ${joystickDeadDp().toInt()} dp(越小越灵敏)"
            textSize = 14f
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val deadSeek = SeekBar(this).apply {
            max = 36                      // 4 ~ 40 dp
            progress = (joystickDeadDp() - 4f).toInt().coerceIn(0, 36)
            setPadding(dp(8), 0, dp(8), 0)
        }
        deadSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = (p + 4).toFloat()
                deadLabel.text = "灵敏度:触发距离 ${v.toInt()} dp(越小越灵敏)"
                if (fromUser) {
                    getSharedPreferences("ui", Context.MODE_PRIVATE)
                        .edit().putFloat("joystick_dead_dp", v).apply()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        box.addView(deadLabel)
        box.addView(deadSeek)

        // ── 悬浮窗子菜单(按键大小放在里面)──
        header("悬浮窗", R.drawable.ic_settings)
        box.addView(android.widget.Button(this).apply {
            text = "按键大小 / 悬浮窗缩放"
            textSize = 14f
            compoundDrawablePadding = dp(8)
            setCompoundDrawables(icon(R.drawable.ic_layout, 0xFFFFFFFF.toInt()), null, null, null)
            setOnClickListener { showFloatingPanel() }
        })
        box.addView(TextView(this).apply {
            text = "全局按键大小在这里;单个按键大小在「调整布局」界面底部滑块(也可双指捏合)。"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(8), 0, dp(8), dp(6))
        })

        // ── 各控件平时拖动开关(即时生效)──
        header("各控件平时拖动开关", R.drawable.ic_layout)
        val dragToggles = listOf(
            "settings_ball" to "悬浮球",
            "btn_ime" to "软键盘钮",
            "btn_toggle" to "手柄显隐钮",
            "btn_joystick_toggle" to "摇杆显隐钮",
            "btn_hud" to "状态框开关钮",
            "perf_hud" to "状态框",
            "pad" to "手柄键(↑↓←→ 和 空格)"
        )
        for ((key, label) in dragToggles) {
            val prefKey = if (key == "pad") "drag_pad" else "drag_$key"
            val defaultOn = key == "settings_ball" || key == "btn_ime"
            box.addView(CheckBox(this).apply {
                textSize = 15f
                isChecked = getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .getBoolean(prefKey, defaultOn)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnCheckedChangeListener { _, checked ->
                    getSharedPreferences("ui", Context.MODE_PRIVATE)
                        .edit().putBoolean(prefKey, checked).apply()
                }
            }.also { cb ->
                cb.text = label
            })
        }
        box.addView(TextView(this).apply {
            text = "勾上 = 平时就能直接拖动换位(不用进调整布局);不勾 = 只在「调整布局」里能拖。" +
                "摇杆不在此列 —— 它是操作件,平时拖动会破坏走位(只能在布局模式里拖)。"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(8), 0, dp(8), dp(6))
        })

        box.addView(TextView(this).apply {
            text = "提示:所有按钮/悬浮球都能长按拖动换位(位置自动保存);" +
                "想整体重排就点上面的「调整布局」。日志相关请去悬浮球 →「诊断日志」。"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(6), 0, 0)
        })

        scroll.addView(box)

        controlsDialog = android.app.AlertDialog.Builder(this)
            .setTitle("控件与按键(即时生效)")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        controlsDialog?.show()
    }

    /**
     * 「版本选择」菜单(悬浮球第一行)—— **重启生效**:
     *   ① 游戏版本(平行服各版本 + 淘米官方服)。
     *   (渲染后端已移到「图像设置」里,见 showGraphicsPanel。)
     */
    private fun showVersionDialog() {
        val pad = dp(16)
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(text: String, iconRes: Int? = null) {
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(0xFF2E7D32.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, if (box.childCount == 0) 0 else pad, 0, dp(6))
                if (iconRes != null) {
                    compoundDrawablePadding = dp(8)
                    setCompoundDrawables(icon(iconRes, 0xFF2E7D32.toInt(), 18), null, null, null)
                }
            })
        }

        header("游戏版本(重启生效)", R.drawable.ic_refresh)
        val spVersion = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentVersion = spVersion.getString("version", "") ?: ""
        val versionGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        // ★ 必须先 addView、后 check():否则 RadioGroup 不知道当前选中项,会"两个都选中"。
        val rbIds = LinkedHashMap<String, Int>()
        for ((key, label) in VERSIONS) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            rbIds[key] = rb.id
            versionGroup.addView(rb)
        }
        versionGroup.setOnCheckedChangeListener { _, checkedId ->
            val key = rbIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            spVersion.edit().putString("version", key).apply()
            val label = VERSIONS.firstOrNull { it.first == key }?.second ?: key
            logToRust("版本已选: ${if (key.isEmpty()) "主版本" else label}(重启后生效)")
        }
        rbIds[currentVersion]?.let { versionGroup.check(it) }
        box.addView(versionGroup)
        box.addView(TextView(this).apply {
            text = "「淘米官方服」走 mole.61.com(与平行服 mole.61player.com 不同域名/不同存档);" +
                "官方服是否仍可连接以实际结果为准。"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(8), 0, dp(8), dp(6))
        })

        scroll.addView(box)
        android.app.AlertDialog.Builder(this)
            .setTitle("版本选择(重启生效)")
            .setView(scroll)
            .setPositiveButton("重启生效") { _, _ -> restartApp() }
            // ★ 检查更新:比对平行服的版本标识,判断游戏资源是否更新。
            .setNeutralButton("检查更新") { _, _ -> runUpdateCheck() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 「日志与诊断」菜单:日志开关 + 保留天数 + **日志操作**子菜单(实时/导出/清除)。
     */
    private fun showLogMenu() {
        val pad = dp(16)
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)

        box.addView(CheckBox(this).apply {
            text = "记录运行日志(关闭则完全不写盘)"
            textSize = 15f
            isChecked = sp.getBoolean("log_enabled", false)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            compoundDrawablePadding = dp(8)
            setCompoundDrawables(icon(R.drawable.ic_log, 0xFF455A64.toInt()), null, null, null)
            setOnCheckedChangeListener { _, checked ->
                sp.edit().putBoolean("log_enabled", checked).apply()
                applyLogSettings()
            }
        })
        val daysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val daysInput = EditText(this).apply {
            hint = "7"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(sp.getInt("log_retention_days", 7).toString())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        daysRow.addView(daysInput)
        daysRow.addView(TextView(this).apply {
            text = " 天(日志保留天数,1~90)"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        })
        box.addView(daysRow)
        box.addView(Button(this).apply {
            // ★ 改"保存并重启":主人反馈改了设置后不重启不生效,干脆保存完自动重启,
            //   一步到位(重启后日志设置才完整应用,包括时区偏移等)。
            text = "保存并重启"
            textSize = 14f
            compoundDrawablePadding = dp(8)
            setCompoundDrawables(icon(R.drawable.ic_save, 0xFFFFFFFF.toInt()), null, null, null)
            setOnClickListener {
                // 二次确认(跟「卡死重启」一样),避免误触直接重启。
                android.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("保存并重启")
                    .setMessage("保存日志设置并重启应用吗?\n(重启后设置完全生效)")
                    .setPositiveButton("保存并重启") { _, _ ->
                        val days = daysInput.text.toString().toIntOrNull()?.coerceIn(1, 90) ?: 7
                        sp.edit().putInt("log_retention_days", days).apply()
                        daysInput.setText(days.toString())
                        applyLogSettings()
                        logToRust("日志设置已保存,自动重启以完全生效")
                        restartApp()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        // ★ 日志操作收进子菜单
        box.addView(android.widget.Button(this).apply {
            text = "日志操作(实时 / 导出 / 清除)"
            textSize = 14f
            compoundDrawablePadding = dp(8)
            setCompoundDrawables(icon(R.drawable.ic_log, 0xFFFFFFFF.toInt()), null, null, null)
            setOnClickListener { showLogActionsDialog() }
        })

        scroll.addView(box)
        android.app.AlertDialog.Builder(this)
            .setTitle("日志与诊断")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 导出日志 → 系统分享(ACTION_SEND + FileProvider content URI)。
     *   这样主人可以直接分享到微信/QQ/邮箱/网盘等任意应用。
     */
    private fun exportLog() {
        try {
            val f = logFile()
            if (!f.exists() || f.length() == 0L) {
                android.widget.Toast.makeText(this, "日志文件为空,请先开启日志记录", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // ★ 先起保活服务:分享面板一弹出我们的窗口就被销毁、进程进后台,
            //   没有前台服务很容易被系统回收(主人遇到的"导出就闪退")。
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(Intent(this, KeepAliveService::class.java))
                } else {
                    startService(Intent(this, KeepAliveService::class.java))
                }
            } catch (t: Throwable) {
                logToRust("保活服务启动失败: $t")
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "摩尔客户端日志 ${f.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "导出日志到…"))
        } catch (t: Throwable) {
            logToRust("导出日志失败: $t")
            android.widget.Toast.makeText(this, "导出失败: $t", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 干净地重启整个进程(版本切换/渲染后端需要 Rust 侧重新读配置)。 */
    /**
     * 「卡死重启」:游戏卡住(画面冻结/点不动)时一键重启应用。
     * 比系统杀应用再点图标更快,且重启前留一条日志方便排查。
     */
    private fun confirmRestart() {
        android.app.AlertDialog.Builder(this)
            .setTitle("卡死重启")
            .setMessage("确定重启应用吗?\n(游戏画面冻结、点不动时用;正常想退出请用返回键)")
            .setPositiveButton("重启") { _, _ ->
                try {
                    logToRust("主人手动重启(卡死重启)")
                } catch (_: Throwable) {
                }
                restartApp()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    /** 简单日志:写 logcat(ruffle tag),供真机排查。 */
    // MoleRuffle:Kotlin 侧诊断消息写进 Rust 的 mole.log(诊断面板可见;logcat 同步保留)。
    private external fun klog(msg: String)

    private fun logToRust(msg: String) {
        Log.i("ruffle", "MoleRuffle: $msg")
        try {
            klog(msg)
        } catch (_: Throwable) {
        }
    }

    /** 把当前的日志开关/保留天数推给 Rust,立即生效。 */
    // MoleRuffle:日志时区偏移(秒),让 mole.log 的时间戳 = 手机本地时间(之前是 UTC)。
    private external fun setLogTzOffset(offsetSecs: Long)

    private fun applyLogSettings() {
        try {
            setLogging(isLogEnabled(), getLogRetentionDays())
            setLogTzOffset(
                java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() / 1000
            )
        } catch (_: Throwable) {
        }
    }

    /** Rust 侧组合日志器写入的日志文件(与 getAndroidDataStorageDir() 同目录)。 */
    private fun logFile(): java.io.File =
        java.io.File("${filesDir.absolutePath}/ruffle/shared_objects", "mole.log")

    /**
     * 诊断日志面板:直接读 Rust 写的 mole.log,1.5 秒自动刷新一次。
     * 真机没 adb 也能看到 socket/HTTP/AVM2 的真实情况。
     */
    private fun showLogDialog() {
        val tv = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFE0E0E0.toInt())
            setBackgroundColor(0xFF0E0E0E.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        fun refresh() {
            val f = logFile()
            val text = try {
                if (f.exists()) {
                    val lines = f.readLines()
                    val tail = if (lines.size > 400) lines.takeLast(400) else lines
                    "文件: ${f.absolutePath}\n共 ${lines.size} 行,显示末 ${tail.size} 行\n\n" +
                        tail.joinToString("\n")
                } else {
                    "暂无日志(预期路径: ${f.absolutePath})\n请先复现问题再回来查看。"
                }
            } catch (e: Exception) {
                "读取失败: $e"
            }
            tv.text = text
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        refresh()

        val dlg = android.app.AlertDialog.Builder(this)
            // 这是"实时日志"查看器本身(菜单里那个入口已改名为「日志与诊断」)。
            .setTitle("实时日志")
            .setView(scroll)
            .setPositiveButton("复制全部") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("molelog", tv.text))
                android.widget.Toast.makeText(this, "已复制,可粘贴发给我", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("刷新") { _, _ -> }
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()

        val handler = android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                refresh()
                handler.postDelayed(this, 1500)
            }
        }
        handler.postDelayed(tick, 1500)
        dlg.setOnDismissListener { handler.removeCallbacks(tick) }
    }
}
