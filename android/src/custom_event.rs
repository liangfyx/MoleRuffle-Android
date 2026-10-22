//! Custom event type for Ruffle on Android

use ruffle_core::events::KeyDescriptor;

use crate::PlayerRunnable;

/// User-defined events.
pub enum RuffleEvent {
    /// Indicates that a task is ready to be polled.
    TaskPoll(PlayerRunnable),
    VirtualKeyEvent {
        down: bool,
        key_descriptor: KeyDescriptor,
    },
    RunContextMenuCallback(usize),
    ClearContextMenu,
    RequestContextMenu,
    /// MoleRuffle:显/隐系统自带软键盘(⌨ 按钮触发)。
    ToggleSoftKeyboard,
    /// MoleRuffle:IME 退格键事件(物理按键通道)→ 注入 TextControl::Backspace。
    /// 输入法按退格时会发 KEYCODE_DEL 键事件;若 IME 文本缓冲为空(如删除游戏
    /// 预填的记住密码),文本 diff 通道看不到删除,必须靠这个键事件通道。
    InjectBackspace,
    /// MoleRuffle:系统侧收起了软键盘(返回键/键盘自带收起钮)。
    /// Kotlin 监听 IME insets 变化后通知;同步 Rust 侧显示状态,避免状态过期
    /// (表现为"要点两次 ⌨ 才弹键盘"、"切输入框不自动弹")。
    KbdSyncHidden,
    /// MoleRuffle:隐藏输入框(EditText)报告"删掉了 n 个字符"。
    /// 走真实 InputConnection,中文输入法的 composition 删除也能看到
    /// (旧的 text_input_state 轮询在多数输入法下收不到删除)。
    ImeDelete(u32),
    /// MoleRuffle:隐藏输入框报告"新增了这段文本"。
    ImeText(String),
    /// MoleRuffle:★ 直连注入的触摸事件。
    ///   主人日志实锤:收起软键盘之后,窗口照样在收到触摸(Activity.onTouchEvent 有),
    ///   但引擎侧一次 TOUCH 都收不到 —— 输入桥断了(事件循环活着、渲染正常,
    ///   就是收不到输入)。断掉后由 Kotlin 直接把坐标送进来,绕过那条通道。
    ///   坐标是 **屏幕原始坐标**(MotionEvent 的 rawX/rawY),由 Rust 做同样的窗口换算。
    InjectedTouch {
        x: f64,
        y: f64,
        down: bool,
    },
    /// MoleRuffle:切换渲染画质(0=低 1=中 2=高)。低画质关掉位图平滑等开销,
    ///   对十几年前的 Flash 内容是立竿见影的加速(配合渲染分辨率一起调)。
    SetQuality(u8),
    /// MoleRuffle:切换渲染分辨率(25~100)。改 surface 缓冲尺寸必须在"帧之间"安全点执行,
    ///   否则 UI 线程 setFixedSize 与渲染线程并发 → Adreno 驱动 begin_render_pass 崩
    ///   (任务界面开着切分辨率就崩的真因)。本事件在事件循环里、下一帧 render 之前处理,
    ///   先让 Kotlin setFixedSize 再 recreate_surface_unsafe,最后留 grace frames 缓冲。
    SetRenderScale(u8),
}
