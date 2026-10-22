mod audio;
mod custom_event;
mod external_interface;
mod java;
mod keycodes;
mod local_proxy;
mod molelog;
mod navigator;
mod trace;
mod ui_backend;

use custom_event::RuffleEvent;

use jni::{
    objects::{JObject, JString},
    sys::{self, jfloat, jint, jlong, jobject},
    JNIEnv, JavaVM,
};
use keycodes::{android_key_event_to_ruffle_key_descriptor, key_tag_to_key_descriptor};
use std::any::Any;
use std::rc::Rc;
use std::sync::mpsc::Sender;
use std::sync::{mpsc, MutexGuard};
use std::time::Duration;
use std::{
    panic,
    sync::{Arc, Mutex},
    thread,
    time::Instant,
};
use ruffle_render_wgpu::wgpu;
use wgpu::rwh::{AndroidDisplayHandle, HasWindowHandle, RawDisplayHandle};

use android_activity::input::{InputEvent, KeyAction, MotionAction};
use android_activity::{AndroidApp, AndroidAppWaker, InputStatus, MainEvent, PollEvent};
use backtrace::Backtrace;
use jni::objects::JClass;

use audio::AAudioAudioBackend;
use url::Url;

use ruffle_common::duration::FloatDuration;
use ruffle_core::{
    backend::navigator::OwnedFuture,
    events::{LogicalKey, MouseButton, PlayerEvent},
    tag_utils::SwfMovie,
    Player, PlayerBuilder, StageScaleMode, ViewportDimensions,
};

// MoleRuffle:游戏加载地址 —— 运行时从 Kotlin 侧读取(支持平行服的版本切换:
// 主版本 /moleverse/骑士版20090626 /圣诞版20111225 /万圣版20190815 /新春版20120128 /
// 火神杯2025hsb /桃源版taoyuan)。Kotlin 兜底默认平行服主版本。
// 用 http 而非 https:与已验证能跑通的官方配置一致,排除 rustls/TLS 变量。
// 域名 spoof 同步指向该地址(SWF 内的 navigateToURL 仅是"打开官网"按钮,无域名白名单)。
use ruffle_frontend_utils::backends::storage::DiskStorageBackend;
use ruffle_frontend_utils::content::PlayingContent;
use ruffle_frontend_utils::{
    backends::navigator::{ExternalNavigatorBackend, FutureSpawner},
    content::ContentDescriptor,
};

use crate::navigator::AndroidNavigatorInterface;
use crate::trace::FileLogBackend;
use java::JavaInterface;
use ruffle_render_wgpu::{backend::WgpuRenderBackend, target::SwapChainTarget};

// MoleRuffle:性能监视 HUD 的全局状态。渲染循环(android_main 线程)写入,
//   JNI getPerfStats(UI 线程)读取。用原子/Mutex 免加锁竞争。
mod perf {
    use std::sync::atomic::{AtomicU32, AtomicU64};
    use std::sync::Mutex;

    pub static FPS: AtomicU32 = AtomicU32::new(0);
    /// 最近一帧 tick 间隔,微秒(帧时间)。
    pub static FRAME_US: AtomicU32 = AtomicU32::new(0);
    pub static SURFACE_W: AtomicU32 = AtomicU32::new(0);
    pub static SURFACE_H: AtomicU32 = AtomicU32::new(0);
    /// MoleRuffle:引擎实际收到的"按下"次数。
    ///   Kotlin 侧拿它跟"窗口送进去的 DOWN 数"对账 —— 窗口送了但这里不动
    ///   就说明输入桥断了(收起键盘后的整机点不动)。
    pub static TOUCH_COUNT: AtomicU64 = AtomicU64::new(0);
    /// MoleRuffle:帧率上限(每秒最多 tick/render 多少次)。**0 = 不限**(默认)。
    ///   注意:摩尔庄园的 SWF 是 24FPS,调高上限不会让动画更流畅(Flash 时间轴
    ///   按自己的帧率推进),这个开关是用来**限帧省电/控温**的。
    pub static FPS_CAP: AtomicU32 = AtomicU32::new(0);
    /// 渲染后端名(Vulkan / Gl 等),渲染器创建时写一次。
    pub static BACKEND: Mutex<String> = Mutex::new(String::new());
}

/// A unique identifier for a given `Player` instance.
/// Used to track which player any currently executing future is bound to.
#[derive(Copy, Clone, Eq, PartialEq)]
struct PlayerId(i64);

impl PlayerId {
    fn new() -> Self {
        use std::sync::atomic::{AtomicI64, Ordering};

        static NEXT: AtomicI64 = AtomicI64::new(0);
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        assert!(id >= 0, "PlayerId overflowed!");
        Self(id)
    }
}

/// A `Player`-bound future that is currently running.
pub struct PlayerRunnable(async_task::Runnable<PlayerId>);

/// Represents a current Player and any associated state with that player,
/// which may be lost when this Player is closed (dropped)
struct ActivePlayer {
    id: PlayerId,
    player: Arc<Mutex<Player>>,
}

#[derive(Clone)]
pub struct EventSender {
    sender: Sender<RuffleEvent>,
    waker: AndroidAppWaker,
}

impl EventSender {
    pub fn send(&self, event: RuffleEvent) {
        if self.sender.send(event).is_ok() {
            self.waker.wake();
        }
    }
}

/// A bare-bones executor that schedules tasks on the winit event loop.
struct AndroidExecutor {
    event_loop: EventSender,
    player_id: PlayerId,
}

impl<E: std::error::Error + 'static> FutureSpawner<E> for AndroidExecutor {
    fn spawn(&self, future: OwnedFuture<(), E>) {
        // Discard any errors.
        let future = async {
            if let Err(e) = future.await {
                tracing::error!("Async error: {}", e);
            }
        };

        let event_loop = self.event_loop.clone();
        let scheduler = move |task| {
            let event = RuffleEvent::TaskPoll(PlayerRunnable(task));
            event_loop.send(event)
        };

        // ★ MoleRuffle:用 spawn_unchecked 而非 spawn_local —— spawn_local 内部用 Checked<F>
        //   在 drop 时断言"必须在 spawn 线程 drop"(async-task runnable.rs:439)。重负载/回家时
        //   future 在非 spawn 线程(tokio 工作线程)被 drop → assert panic → abort_on_panic →
        //   整机闪退(日志17 实锤:"local task dropped by a thread that didn't spawn it")。
        //   spawn_unchecked 跳过该断言,避免 abort。future 为 !Send,但其 drop 只是丢 Gc 引用、
        //   不触线程本地状态,跨线程 drop 实际无害 —— 换掉"必现闪退"。
        let (runnable, task) = unsafe {
            async_task::Builder::new()
                .metadata(self.player_id)
                .spawn_unchecked(|_| future, scheduler)
        };

        // The future should run in the background.
        task.detach();
        // Immediately schedule the future to be polled for the first time.
        runnable.schedule();
    }
}

/// MoleRuffle:看门狗用全局计数 —— 事件循环每转一圈 +1。
static WD_LOOP: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
/// MoleRuffle:看门狗用的"当前阶段"。0=轮询/输入分发 1=事件+键盘状态机 2=tick 3=render。
static WD_PHASE: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(0);

#[tokio::main]
async fn run(app: AndroidApp) {
    // MoleRuffle:看门狗 —— 主人日志实锤"收起键盘后整机冻结"(4 分钟零日志零心跳),
    //   但冻在哪一步看不见。这个独立线程监测事件循环计数,卡死时把当前阶段写进 mole.log。
    //   阶段:0=轮询/输入分发 1=事件+键盘状态机 2=tick(游戏逻辑) 3=render(渲染)
    std::thread::Builder::new()
        .name("mole-watchdog".into())
        .spawn(|| {
            let mut last_loop = 0u64;
            let mut last_change = std::time::Instant::now();
            let mut reported = false;
            loop {
                std::thread::sleep(Duration::from_millis(1000));
                let cur = WD_LOOP.load(std::sync::atomic::Ordering::Relaxed);
                if cur != last_loop {
                    last_loop = cur;
                    last_change = std::time::Instant::now();
                    reported = false;
                } else {
                    let stuck = last_change.elapsed().as_secs();
                    // 5s 首报,之后每 10s 报一次(避免刷屏)。
                    let should_report = stuck >= 5
                        && (!reported || stuck % 10 == 0 || stuck == 5);
                    if should_report {
                        reported = true;
                        let phase = WD_PHASE.load(std::sync::atomic::Ordering::Relaxed);
                        let phase_name = match phase {
                            0 => "0=轮询事件/输入分发",
                            1 => "1=事件处理+键盘状态机(含清聚焦)",
                            2 => "2=tick 游戏逻辑帧",
                            3 => "3=render 渲染",
                            _ => "未知",
                        };
                        log::error!(
                            "MoleRuffle: ★看门狗: 事件循环已冻结 {} 秒,卡在阶段 [{}]",
                            stuck,
                            phase_name
                        );
                    }
                }
            }
        })
        .ok();
    let mut last_frame_time = Instant::now();
    let mut next_frame_time = Some(Instant::now());
    let mut quit = false;
    let (sender, receiver) = mpsc::channel::<RuffleEvent>();
    let mut native_window: Option<ndk::native_window::NativeWindow> = None;
    let mut playerbox: Option<ActivePlayer> = None;
    // MoleRuffle:性能 HUD 采样(每 500ms 算一次 FPS)。
    let mut frame_count: u64 = 0;
    let mut last_hud_time = Instant::now();
    let mut last_hud_frames: u64 = 0;
    // MoleRuffle:心跳计时(每 10s 记一行,证明事件循环还活着;冻结时它是最后一行)。
    let mut last_beat = Instant::now();
    // MoleRuffle:系统软键盘状态 + 上一次 IME 文本(用于增删 diff)。
    // MoleRuffle:★ 窗口是否还活着。窗口没了(切后台/系统分享/锁屏)时必须停止
    //   tick + render —— 否则会往已经销毁的 surface 上画,Vulkan 直接把进程带崩
    //   (主人点「导出日志」就闪退,正是分享面板弹出后窗口被销毁)。
    let mut surface_alive = false;
    // MoleRuffle:恢复后先跳 N 帧再渲染(给重建的 surface 稳定时间,避免驱动崩)。
    let mut resume_grace_frames = 0u8;
    let mut soft_kb_visible = false;
    let mut last_ime_text = String::new();
    // MoleRuffle:失焦收起键盘的去抖计时(失焦持续 400ms 才真收)。
    let mut kbd_close_pending: Option<Instant> = None;
    // MoleRuffle:聚焦事件脉冲(上轮见到的值),变化 = 新聚焦。
    let mut last_focus_pulse: u32 = 0;
    // MoleRuffle:最近一次真实触摸(MouseDown/Up)的时刻。
    //   ★ 用来区分"用户真的点了输入框"和"游戏自己又把焦点抢回去":后者会再产生一次
    //     聚焦脉冲,若不加以区分,键盘就会在用户刚收起它之后自己又弹起来。
    let mut last_touch: Option<Instant> = None;
    // MoleRuffle:★ 键盘模式已关闭标志 —— 用户/系统已经把键盘收掉了,不允许游戏
    //   继续停在"输入态"(文本框还聚焦)。
    //   只要它为真且游戏仍在输入态,下面的自愈守卫就会每 300ms 重复清一次聚焦,
    //   直到游戏真的退出输入态。摩尔的聊天框会每帧 Selection.setFocus 抢回焦点,
    //   一次性清除会被立刻撤销 —— 这正是"键盘关了却点不动其它元素"的根因。
    let mut kbd_closed = false;
    // MoleRuffle:上一次清除聚焦的时刻(自愈守卫的节流基准)。
    let mut last_blur_at: Option<Instant> = None;
    // MoleRuffle:★ 待执行的"清聚焦"。不再在事件派发途中同步跑游戏的失焦代码,
    //   而是挪到每帧开头(tick 之前)执行 —— 主人在登录界面"收起键盘后整机冻结",
    //   最大嫌疑就是游戏的失焦 AS 代码被塞在 IME/JNI 的混乱时刻同步执行。
    let mut pending_blur = false;
    // MoleRuffle:最近一次经键事件通道注入退格的时间(与 diff 通道去重)。
    let mut last_bs_inject: Option<Instant> = None;
    let sender = EventSender {
        sender,
        waker: app.create_waker(),
    };

    log::info!("Starting event loop...");
    let trace_output;
    let android_storage_dir;
    // MoleRuffle:启动时从 Kotlin 读游戏地址(版本选择),事件循环里用。
    let mole_swf_url: String;
    // MoleRuffle:渲染后端偏好("vulkan" / "gl"),由设置面板选择、重启后生效。
    let backend_pref: String;
    // MoleRuffle:启动 3200 本地代理。平行服 Server.xml 的 GameSer 是 127.0.0.1:3200,
    //   网页版靠本地 ws 桥转发,原生端没桥 → 游戏直连必失败。这里自己起一个代理,
    //   并**自动探测**真实游戏服(逐个候选试,谁先回数据用谁),不再硬猜目标地址。
    //   若游戏其实走 DirSer 下发的真实地址,这个代理就只是安静地无人连接,无害。
    local_proxy::start();

    unsafe {
        let vm = JavaVM::from_raw(app.vm_as_ptr() as *mut sys::JavaVM).expect("JVM must exist");
        let activity = JObject::from_raw(app.activity_as_ptr() as jobject);
        let mut jni_env = vm.get_env().unwrap();
        trace_output = JavaInterface::get_trace_output(&mut jni_env, &activity);
        android_storage_dir = JavaInterface::get_android_data_storage_dir(&mut jni_env, &activity);
        // MoleRuffle:日志落盘到 filesDir/ruffle/shared_objects/mole.log,供 App 内
        //   「诊断日志」面板读取。开关默认关(设置面板控制),开了才写,并按保留天数清理旧行。
        molelog::set_enabled(JavaInterface::is_log_enabled(&mut jni_env, &activity));
        molelog::set_path(android_storage_dir.join("mole.log"));
        // MoleRuffle:★ 本地资源缓存目录(供 ruffle 的 fetch 通道落盘/命中)。
        //   场景 swf/服装/音效第二进同一张图不再回服务器(主人反馈的"每次都要重下")。
        let mole_cache = android_storage_dir.join("mole_cache");
        match std::fs::create_dir_all(&mole_cache) {
            Ok(_) => {
                // frontend-utils 的 fetch 读这个环境变量决定缓存位置。
                #[allow(deprecated, unused_unsafe)]
                unsafe {
                    std::env::set_var("MOLE_CACHE_DIR", &mole_cache);
                }
                log::warn!(
                    "MoleRuffle: 资源缓存目录 = {}",
                    mole_cache.display()
                );
            }
            Err(e) => log::error!("MoleRuffle: 资源缓存目录创建失败 {}", e),
        }
        molelog::prune_retention(JavaInterface::get_log_retention_days(
            &mut jni_env,
            &activity,
        ));
        mole_swf_url = JavaInterface::get_game_swf_url(&mut jni_env, &activity);
        // MoleRuffle:渲染后端偏好("vulkan" 默认 / "gl" 用于绕开餐厅场景的原生崩溃)。
        backend_pref = JavaInterface::get_render_backend(&mut jni_env, &activity);
        let _ = jni_env.set_rust_field(activity, "eventLoopHandle", sender.clone());
    }
    log::info!("MoleRuffle: 游戏加载地址 = {}", mole_swf_url);

    while !quit {
        // MoleRuffle:看门狗计数 + 阶段标记(阶段含义见 watchdog 线程)。
        WD_LOOP.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        WD_PHASE.store(0, std::sync::atomic::Ordering::Relaxed);
        let mut needs_redraw = false;
        app.poll_events(
            Some(
                next_frame_time
                    .and_then(|next| next.checked_duration_since(last_frame_time))
                    .unwrap_or_else(|| Duration::from_millis(100)),
            ),
            |event| {
                match event {
                    PollEvent::Main(event) => match event {
                        MainEvent::Destroy => {
                            if let Some(player) = playerbox.as_ref() {
                                let mut player_lock = player.player.lock().unwrap();
                                player_lock.flush_shared_objects();
                            }
                            quit = true;
                        }
                        MainEvent::WindowResized { .. } => {
                            // ★ 窗口尺寸变化 = 窗口确实存在 → 兜底把渲染开关打开。
                            surface_alive = true;
                            if let Some(player) = playerbox.as_ref() {
                                let mut player_lock = player.player.lock().unwrap();
                                let window = native_window
                                    .as_ref()
                                    .expect("native_window should be Some for a WindowResized");
                                log::info!(
                                    "WindowResized: {} x {}",
                                    window.width(),
                                    window.height()
                                );
                                let viewport_scale_factor = app
                                    .config()
                                    .density()
                                    .map(|dpi| dpi as f64 / 160.0)
                                    .unwrap_or(1.0);
                                let dimensions = ViewportDimensions {
                                    width: window.width() as u32,
                                    height: window.height() as u32,
                                    scale_factor: viewport_scale_factor,
                                };
                                player_lock.set_viewport_dimensions(dimensions);
                                // MoleRuffle:旋转/尺寸变化后刷新 HUD 分辨率(否则停留在首帧旧值)。
                                perf::SURFACE_W
                                    .store(dimensions.width, std::sync::atomic::Ordering::Relaxed);
                                perf::SURFACE_H
                                    .store(dimensions.height, std::sync::atomic::Ordering::Relaxed);
                                needs_redraw = true;
                            }
                        }
                        MainEvent::Resume { .. } => {
                            if let Some(player) = playerbox.as_ref() {
                                if let Some(window) = native_window.as_ref() {
                                    // [NA] For some reason we can get negative sizes during a resume...
                                    if window.width() > 0 && window.height() > 0 {
                                        unsafe {
                                            let mut player = player
                                                .player
                                                .lock()
                                                .unwrap();

                                            let renderer = <dyn Any>::downcast_mut::<WgpuRenderBackend<SwapChainTarget>>(
                                                player.renderer_mut(),
                                            )
                                            .unwrap();

                                            renderer.recreate_surface_unsafe(
                                                wgpu::SurfaceTargetUnsafe::RawHandle {
                                                    raw_display_handle:
                                                        Some(RawDisplayHandle::Android(
                                                            AndroidDisplayHandle::new(),
                                                        )),
                                                    raw_window_handle: window
                                                        .window_handle()
                                                        .unwrap()
                                                        .into(),
                                                },
                                                (window.width() as u32, window.height() as u32),
                                            )
                                            .unwrap();
                                        }
                                    }
                                }
                            }
                        }
                        MainEvent::InitWindow { .. } => {
                            native_window = app.native_window();
                            let window = native_window
                                .as_ref()
                                .expect("native_window should be Some after InitWindow");
                            let viewport_scale_factor = app
                                .config()
                                .density()
                                .map(|dpi| dpi as f64 / 160.0)
                                .unwrap_or(1.0);
                            let dimensions = ViewportDimensions {
                                width: window.width() as u32,
                                height: window.height() as u32,
                                scale_factor: viewport_scale_factor,
                            };
                            log::info!(
                                "Init window: {} x {} (is existing: {})",
                                window.width(),
                                window.height(),
                                playerbox.is_some()
                            );
                            // MoleRuffle:窗口(重)建后刷新 HUD 分辨率,覆盖后台回前台/重建路径。
                            perf::SURFACE_W
                                .store(dimensions.width, std::sync::atomic::Ordering::Relaxed);
                            perf::SURFACE_H
                                .store(dimensions.height, std::sync::atomic::Ordering::Relaxed);

                            if let Some(activeplayer) = &playerbox {
                                let mut player_lock = activeplayer.player.lock().unwrap();
                                unsafe {
                                    let renderer = <dyn Any>::downcast_mut::<WgpuRenderBackend<SwapChainTarget>>(
                                        player_lock.renderer_mut(),
                                    )
                                    .unwrap();

                                    renderer.recreate_surface_unsafe(
                                        wgpu::SurfaceTargetUnsafe::RawHandle {
                                            raw_display_handle: Some(RawDisplayHandle::Android(
                                                AndroidDisplayHandle::new(),
                                            )),
                                            raw_window_handle: window
                                                .window_handle()
                                                .unwrap()
                                                .into(),
                                        },
                                        (window.width() as u32, window.height() as u32),
                                    )
                                    .unwrap();
                                }
                                player_lock.set_is_playing(true);
                                // ★ 窗口回来了(后台回前台 / 分享面板关闭)→ 恢复 tick+render。
                                surface_alive = true;
                                // ★★ 给重建后的 surface 留 3 帧缓冲:Adreno 驱动对刚重建的 surface
                                //   第一帧就 begin_render_pass 会崩(导出日志返回后闪退的真因)。
                                resume_grace_frames = 3;
                                needs_redraw = true;
                                last_frame_time = Instant::now();
                                log::info!("MoleRuffle: 窗口已恢复,继续渲染(先跳 3 帧让 surface 稳定)");
                            } else {
                                // 首次建渲染器:失败也不要 panic(以前是 .unwrap(),
                                // 窗口还没准备好时直接把进程带崩)。
                                let renderer = unsafe {
                                    // TODO: make this take an Arc<Window> instead?
                                    WgpuRenderBackend::for_window_unsafe(
                                        wgpu::SurfaceTargetUnsafe::RawHandle {
                                            raw_display_handle: Some(RawDisplayHandle::Android(
                                                AndroidDisplayHandle::new(),
                                            )),
                                            raw_window_handle: window
                                                .window_handle()
                                                .unwrap()
                                                .into(),
                                        },
                                        (dimensions.width, dimensions.height),
                                        // MoleRuffle:默认"Vulkan 优先,失败回退 GL";
                                        //   但**餐厅场景会原生崩溃**(日志:1340011.swf 反复请求后
                                        //   进程直接没了,且没有 Rust panic → 属渲染/原生层)。
                                        //   设置里可强制改用 OpenGL ES 绕开(重启生效)。
                                        if backend_pref == "gl" {
                                            wgpu::Backends::GL
                                        } else {
                                            wgpu::Backends::VULKAN | wgpu::Backends::GL
                                        },
                                        wgpu::PowerPreference::HighPerformance,
                                        // ★ 新版 ruffle 的 for_window_unsafe 多了 display 参数,
                                        //   我们没有独立 display handle → None。
                                        None,
                                    )
                                    .unwrap()
                                };
                                // MoleRuffle:记录真实后端名 + 渲染分辨率给性能 HUD 显示。
                                {
                                    let info = renderer.descriptors().adapter.get_info();
                                    if let Ok(mut b) = perf::BACKEND.lock() {
                                        *b = format!("{:?}", info.backend);
                                    }
                                    perf::SURFACE_W.store(
                                        dimensions.width,
                                        std::sync::atomic::Ordering::Relaxed,
                                    );
                                    perf::SURFACE_H.store(
                                        dimensions.height,
                                        std::sync::atomic::Ordering::Relaxed,
                                    );
                                    log::info!(
                                        "MoleRuffle: 渲染后端 = {:?} ({})",
                                        info.backend,
                                        info.name
                                    );
                                }
                                // MoleRuffle:base URL 指向 mole.61.com,好让 Client.swf 里的相对资源正确解析。
                                let movie_url = Url::parse(&mole_swf_url).unwrap();
                                let player_id = PlayerId::new();

                                let future_spawner = AndroidExecutor {
                                    event_loop: sender.clone(),
                                    player_id,
                                };

                                let navigator = ExternalNavigatorBackend::new(
                                    movie_url.clone(),
                                    None,
                                    None,
                                    future_spawner,
                                    None,
                                    // MoleRuffle:upgrade_to_https=false —— 摩尔庄园服务器只有 http,
                                    //   升 https 会连到 443 假 IP 代理不处理 → "Domain resolution failure"。
                                    false,
                                    Default::default(),
                                    ruffle_core::backend::navigator::SocketMode::Allow,
                                    Rc::new(PlayingContent::DirectFile(ContentDescriptor::new_remote(movie_url))),
                                    AndroidNavigatorInterface,
                                );

                                playerbox = Some(ActivePlayer {
                                    id: player_id,
                                    player: PlayerBuilder::new()
                                            .with_renderer(renderer)
                                            .with_audio(AAudioAudioBackend::new().unwrap())
                                            .with_storage(Box::new(DiskStorageBackend::new(android_storage_dir.clone())))
                                            .with_navigator(navigator)
                                            .with_log(FileLogBackend::new(trace_output.as_deref()))
                                            .with_video(
                                                ruffle_video_software::backend::SoftwareVideoBackend::new(),
                                            )
                                            // MoleRuffle:自定义 UI 后端 —— 缺失设备字体(SimSun/Arial 等)回退到
                                            //   系统 CJK 字体,游戏内中文才渲染得出来。
                                            // MoleRuffle:提供 ExternalInterface —— 没有它,平行服
                                            //   ClientSocketDLL 构造时 addCallback 会抛 #2067,
                                            //   连游戏服的 socket 模块起不来(选服后卡 0% 的真因)。
                                            .with_external_interface(Box::new(
                                                external_interface::AndroidExternalInterfaceProvider {
                                                    game_url: Url::parse(&mole_swf_url).ok(),
                                                },
                                            ))
                                            .with_ui(ui_backend::AndroidUiBackend::new())
                                            // MoleRuffle:域名 spoof(让 Client.swf 以为在官网,不 navigateToURL 弹走)+
                                            //   ShowAll 等比缩放铺满屏(摩尔庄园舞台固定 960x560)。
                                            .with_spoofed_url(Some(mole_swf_url.clone()))
                                            .with_scale_mode(StageScaleMode::ShowAll, true)
                                            .with_autoplay(true)
                                        .build(),
                                    }
                                );

                                let player = &playerbox.as_ref().unwrap().player;
                                let mut player_lock = player.lock().unwrap();
                                let (jvm, activity) = get_jvm().unwrap();
                                let mut env = jvm.attach_current_thread().unwrap();
                                // MoleRuffle:不走 Java 文件选择器,固定从网络加载摩尔庄园 Client.swf。
                                let _ = (&mut env, &activity); // 保留 jvm/activity 绑定,后续 JNI 会用
                                let url = mole_swf_url.clone();
                                log::warn!("MoleRuffle: fetch_root_movie 开始: {url}");
                                player_lock.fetch_root_movie(
                                    url,
                                    Vec::new(),
                                    // 该回调仅在 SWF 头解析成功(=fetch 成功)时触发;失败由 ruffle loader 内部记日志。
                                    Box::new(|_header| {
                                        log::warn!("MoleRuffle: 根影片 SWF 头到达 —— fetch 成功、开始加载内容");
                                    }),
                                );
                                player_lock.set_is_playing(true); // Desktop player will auto-play.

                                player_lock.set_letterbox(ruffle_core::config::Letterbox::On);

                                player_lock.set_viewport_dimensions(dimensions);

                                // MoleRuffle:内置默认字体 _sans/_serif/_typewriter 也路由到 CJK
                                //   (catch-all UI 后端会把任意名字解析成系统 CJK 字体)。
                                player_lock.set_default_font(
                                    ruffle_core::font::DefaultFont::Sans,
                                    vec!["_MoleCJK".to_string()],
                                );
                                player_lock.set_default_font(
                                    ruffle_core::font::DefaultFont::Serif,
                                    vec!["_MoleCJK".to_string()],
                                );
                                player_lock.set_default_font(
                                    ruffle_core::font::DefaultFont::Typewriter,
                                    vec!["_MoleCJK".to_string()],
                                );

                                last_frame_time = Instant::now();
                                next_frame_time = Some(Instant::now());

                                // ★★ 首次创建渲染器这一支也必须置 surface_alive = true!
                                //   漏了它 → 刚启动就永远不 tick 不 render = **开机黑屏**
                                //   (v4.1/v4.2 的回归,主人实测"只能看到控件、看不到画面")。
                                surface_alive = true;
                                log::info!("MOVIE STARTED");
                            }
                        }
                        MainEvent::TerminateWindow { .. }  => {
                            // ★ 窗口销毁(切后台、弹系统分享、锁屏等)时必须立刻停画:
                            //   以前这里只 set_is_playing(false),但事件循环仍会 tick +
                            //   往已销毁的 surface 渲染 → Vulkan 直接崩(**点「导出日志」闪退的真因**,
                            //   因为分享面板一弹出我们的窗口就没了)。
                            surface_alive = false;
                            let player = &playerbox.as_ref().unwrap().player;
                            let mut player_lock = player.lock().unwrap();
                            player_lock.set_is_playing(false);
                        }
                        MainEvent::InputAvailable => {
                            if let Ok(mut inputs) = app.input_events_iter() {
                                while inputs.next(|input| match input {
                                    InputEvent::MotionEvent(event) => {
                                        let window = native_window.as_ref().unwrap();
                                        let pointer = event.pointer_index();
                                        let pointer = event.pointer_at_index(pointer);
                                        let coords: (i32, i32) = get_loc_in_window();
                                        let mut x = pointer.x() as f64 - coords.0 as f64;
                                        let mut y = pointer.y() as f64 - coords.1 as f64;
                                        let view_size = get_view_size().unwrap();
                                        x = x * window.width() as f64 / view_size.0 as f64;
                                        y = y * window.height() as f64 / view_size.1 as f64;
                                        let ruffle_event = match event.action() {
                                            MotionAction::Down | MotionAction::PointerDown | MotionAction::ButtonPress => {
                                                last_touch = Some(Instant::now());
                                                perf::TOUCH_COUNT
                                                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                                                // MoleRuffle:诊断用 —— 记录每次按下的最终坐标与键盘状态。
                                                //   "点不动游戏元素"时看这行还在不在:不在 = 事件没进来,
                                                //   在 = 事件进了但游戏侧没响应(输入态/坐标问题)。
                                                log::info!(
                                                    "MoleRuffle: TOUCH down raw=({:.0},{:.0}) loc=({},{}) view=({:?}) -> ({:.0},{:.0}) kb={} focus={}",
                                                    pointer.x(),
                                                    pointer.y(),
                                                    coords.0,
                                                    coords.1,
                                                    view_size,
                                                    x,
                                                    y,
                                                    soft_kb_visible,
                                                    crate::ui_backend::VIRTUAL_KBD
                                                        .load(std::sync::atomic::Ordering::Relaxed)
                                                );
                                                PlayerEvent::MouseDown {
                                                    x,
                                                    y,
                                                    button: MouseButton::Left, // TODO
                                                    index: None, // TODO
                                                }
                                            }
                                            MotionAction::Up | MotionAction::PointerUp | MotionAction::ButtonRelease => {
                                                last_touch = Some(Instant::now());
                                                PlayerEvent::MouseUp {
                                                    x,
                                                    y,
                                                    button: MouseButton::Left, // TODO
                                                }
                                            }
                                            MotionAction::Move => PlayerEvent::MouseMove { x, y },
                                            _ => return InputStatus::Unhandled,
                                        };

                                        if let Some(player) = playerbox.as_ref() {
                                            player
                                                .player
                                                .lock()
                                                .unwrap()
                                                .handle_event(ruffle_event);
                                        }

                                        InputStatus::Handled
                                    }
                                    InputEvent::KeyEvent(event) => {
                                        if let Some(player) = playerbox.as_ref() {
                                            let Some(key_descriptor) =
                                                android_key_event_to_ruffle_key_descriptor(event)
                                            else {
                                                return InputStatus::Unhandled;
                                            };
                                            let down;
                                            let ruffle_event = match event.action() {
                                                KeyAction::Down => {
                                                    down = true;
                                                    PlayerEvent::KeyDown {
                                                        key: key_descriptor.clone(),
                                                    }
                                                }
                                                KeyAction::Up => {
                                                    down = false;
                                                    PlayerEvent::KeyUp { key: key_descriptor }
                                                }
                                                _ => return InputStatus::Unhandled,
                                            };
                                            player
                                                .player
                                                .lock()
                                                .unwrap()
                                                .handle_event(ruffle_event);

                                            // MoleRuffle:IME 退格键走物理键事件通道 →
                                            //   注入 TextControl::Backspace(经事件循环)。
                                            //   必要性:删除"游戏预填的记住密码"等场景,
                                            //   IME 文本缓冲是空的,diff 通道看不到删除。
                                            if down
                                                && matches!(
                                                    key_descriptor.logical_key,
                                                    LogicalKey::Named(ruffle_core::events::NamedKey::Backspace)
                                                )
                                            {
                                                sender.send(RuffleEvent::InjectBackspace);
                                            }

                                            // TODO: Use `KeyEvent.unicode_char` when it's available:
                                            // https://github.com/rust-mobile/android-activity/issues/183
                                            if down {
                                                if let LogicalKey::Character(c) = key_descriptor.logical_key {
                                                    let event = PlayerEvent::TextInput { codepoint: c };
                                                    player.player.lock().unwrap().handle_event(event);
                                                }
                                            };

                                            needs_redraw = true;
                                        }

                                        InputStatus::Handled
                                    }
                                    _ => InputStatus::Unhandled,
                                }) {}
                            }
                        }
                        _ => {} // Something else happened but it's probably not important for now.
                    },
                    PollEvent::Wake => {} // A task tried to wake us, we'll recv it below
                    PollEvent::Timeout => {} // No events happened, we'll tick as normal below
                    _ => {}               // Unknown future event
                }
            },
        );

        // MoleRuffle:看门狗阶段 1 = 事件处理+键盘状态机(含清聚焦)。
        WD_PHASE.store(1, std::sync::atomic::Ordering::Relaxed);
        match receiver.try_recv() {
            Err(_) => {}
            Ok(RuffleEvent::TaskPoll(task)) => {
                // Only run the task if it matches our current player;
                // otherwise it is stale, and should be cancelled (which
                // happens implicitly on drop).
                if let Some(player) = playerbox.as_ref() {
                    if *task.0.metadata() == player.id {
                        task.0.run();
                    }
                }
            }
            Ok(RuffleEvent::VirtualKeyEvent {
                down,
                key_descriptor,
            }) => {
                if let Some(player) = playerbox.as_ref() {
                    let event = if down {
                        PlayerEvent::KeyDown {
                            key: key_descriptor,
                        }
                    } else {
                        PlayerEvent::KeyUp {
                            key: key_descriptor,
                        }
                    };
                    player.player.lock().unwrap().handle_event(event);

                    if down {
                        // TODO: Add shift/capslock and pass in uppercase characters accordingly
                        if let LogicalKey::Character(c) = key_descriptor.logical_key {
                            let event = PlayerEvent::TextInput { codepoint: c };
                            player.player.lock().unwrap().handle_event(event);
                        }
                    }
                }
            }
            Ok(RuffleEvent::RunContextMenuCallback(index)) => {
                if let Some(player) = playerbox.as_ref() {
                    player
                        .player
                        .lock()
                        .unwrap()
                        .run_context_menu_callback(index);
                }
            }
            Ok(RuffleEvent::ClearContextMenu) => {
                if let Some(player) = playerbox.as_ref() {
                    player.player.lock().unwrap().clear_custom_menu_items();
                }
            }
            Ok(RuffleEvent::RequestContextMenu) => {
                if let Some(player) = playerbox.as_ref() {
                    log::warn!("preparing context menu!");
                    let items = player.player.lock().unwrap().prepare_context_menu();
                    let (jvm, activity) = get_jvm().unwrap();
                    let mut env = jvm.attach_current_thread().unwrap();
                    JavaInterface::show_context_menu(&mut env, &activity, &items);
                }
            }
            Ok(RuffleEvent::InjectedTouch { x, y, down }) => {
                // MoleRuffle:★ 直连注入(Kotlin 侧发现输入桥断了之后改走这条路)。
                //   坐标换算与正常通道完全一致:减去窗口偏移,再按 Surface 尺寸归一化。
                let coords: (i32, i32) = get_loc_in_window();
                let sw = perf::SURFACE_W.load(std::sync::atomic::Ordering::Relaxed) as f64;
                let sh = perf::SURFACE_H.load(std::sync::atomic::Ordering::Relaxed) as f64;
                let vs = get_view_size().unwrap_or((sw as i32, sh as i32));
                let px = (x - coords.0 as f64) * sw / vs.0.max(1) as f64;
                let py = (y - coords.1 as f64) * sh / vs.1.max(1) as f64;
                if let Some(player) = playerbox.as_ref() {
                    if let Ok(mut p) = player.player.lock() {
                        if down {
                            perf::TOUCH_COUNT
                                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                            p.handle_event(PlayerEvent::MouseDown {
                                x: px,
                                y: py,
                                button: MouseButton::Left,
                                index: None,
                            });
                        } else {
                            p.handle_event(PlayerEvent::MouseUp {
                                x: px,
                                y: py,
                                button: MouseButton::Left,
                            });
                        }
                        needs_redraw = true;
                    }
                }
                if down {
                    last_touch = Some(Instant::now());
                    log::info!(
                        "MoleRuffle: TOUCH(直连注入) down raw=({:.0},{:.0}) -> ({:.0},{:.0})",
                        x,
                        y,
                        px,
                        py
                    );
                }
            }
            Ok(RuffleEvent::SetQuality(level)) => {
                // 画质档位:低画质会去掉位图平滑/部分特效开销,老 Flash 内容提速明显。
                let q = match level {
                    0 => ruffle_render::quality::StageQuality::Low,
                    1 => ruffle_render::quality::StageQuality::Medium,
                    _ => ruffle_render::quality::StageQuality::High,
                };
                if let Some(player) = playerbox.as_ref() {
                    if let Ok(mut p) = player.player.lock() {
                        p.set_quality(q);
                        needs_redraw = true;
                    }
                }
                // ★ 画质变了渲染通道参数也变,给 2 帧缓冲,避免 Adreno 在 begin_render_pass 崩。
                resume_grace_frames = resume_grace_frames.max(2);
                log::warn!("MoleRuffle: 渲染画质 = {:?}", q);
            }
            Ok(RuffleEvent::SetRenderScale(pct)) => {
                // v5.5 方案②:运行时实时改渲染分辨率(降采样)。改用 ruffle 官方
                //   player.set_viewport_dimensions() 安全路径 —— 内部只重新 configure 交换链
                //   (target.resize → surface.configure,**不销毁 VkSurfaceKHR**),并重建 ruffle
                //   自身内部 Surface(渲染目标纹理);这正是桌面端每次窗口 resize 走的安全姿势。
                //   相较 v5.3 的 recreate_surface_unsafe(销毁重建 OS surface,反复切换导致 Adreno
                //   驱动 swapchain/帧缓冲资源泄漏、最终 OOM 崩在 vkCmdCopyBuffer,见日志10),
                //   本方案不泄漏、可任意次数实时切换、无需重启。
                let pct = pct.clamp(25, 100) as i32;
                if let Some(player) = playerbox.as_ref() {
                    if let Some(_window) = native_window.as_ref() {
                        if let Ok((jvm, activity)) = get_jvm() {
                            if let Ok(mut env) = jvm.attach_current_thread() {
                                let vw = JavaInterface::get_surface_width(&mut env, &activity);
                                let vh = JavaInterface::get_surface_height(&mut env, &activity);
                                if vw > 0 && vh > 0 {
                                    let nw = (vw as i64 * pct as i64 / 100).max(1) as i32;
                                    let nh = (vh as i64 * pct as i64 / 100).max(1) as i32;
                                    // 让 Kotlin 改 SurfaceHolder 缓冲尺寸(任意线程安全),保证
                                    //   输入坐标映射依旧按"缓冲区/视图"比例换算,不会偏移。
                                    JavaInterface::set_surface_fixed_size(
                                        &mut env,
                                        &activity,
                                        nw,
                                        nh,
                                    );
                                    // ★ 保持与 100% 基线一致的逻辑视口:scale_factor 随 pct 等比例
                                    //   缩小,使画面缩放/位置与 100% 完全一致,只是渲染缓冲降采样。
                                    let viewport_scale_factor = app
                                        .config()
                                        .density()
                                        .map(|dpi| dpi as f64 / 160.0 * pct as f64 / 100.0)
                                        .unwrap_or(1.0);
                                    if let Ok(mut player_lock) = player.player.lock() {
                                        player_lock.set_viewport_dimensions(ViewportDimensions {
                                            width: nw as u32,
                                            height: nh as u32,
                                            scale_factor: viewport_scale_factor,
                                        });
                                        perf::SURFACE_W
                                            .store(nw as u32, std::sync::atomic::Ordering::Relaxed);
                                        perf::SURFACE_H
                                            .store(nh as u32, std::sync::atomic::Ordering::Relaxed);
                                    }
                                    // 重建后留 2 帧缓冲让驱动稳定(同画质切换路径)。
                                    resume_grace_frames = resume_grace_frames.max(2);
                                    needs_redraw = true;
                                    last_frame_time = Instant::now();
                                    log::info!(
                                        "MoleRuffle: 渲染分辨率 = {}% ({}x{}, scale={:.3})",
                                        pct, nw, nh, viewport_scale_factor
                                    );
                                }
                            }
                        }
                    }
                }
            }
            Ok(RuffleEvent::ToggleSoftKeyboard) => {
                // MoleRuffle:摩尔庄园登录/聊天需要打字 —— 调系统自带软键盘(而非官方那套占屏 View 键盘)。
                //   手动打开时清"抑制"标记并复位 IME 基准;关闭时清 VIRTUAL_KBD,
                //   避免"仍聚焦"的状态下自动逻辑马上又弹回来。
                soft_kb_visible = !soft_kb_visible;
                if soft_kb_visible {
                    kbd_closed = false;
                    last_blur_at = None;
                    last_ime_text.clear();
                    crate::ui_backend::VIRTUAL_KBD
                        .store(true, std::sync::atomic::Ordering::Relaxed);
                    // ★ 不再调 app.set_text_input_state:那走 GameActivity 的 GameTextInput
                    //   通道,会在 SurfaceView 上 restartInput,和迷你输入条(真正的 IME 宿主)
                    //   抢输入法服务 → showSoftInput 被吞、键盘起不来(日志:每次"弹出软键盘"
                    //   1~2 秒后必跟一条"已由系统侧收起")。IME 完全交给 Kotlin 输入条。
                    show_soft_keyboard_via_java();
                    // 打开键盘时把游戏里已有的字镜像进输入条,否则退格删不掉预填内容。
                    sync_ime_bar_from_game(&playerbox);
                    log::info!("MoleRuffle: 显示系统软键盘");
                } else {
                    crate::ui_backend::VIRTUAL_KBD
                        .store(false, std::sync::atomic::Ordering::Relaxed);
                    // 手动关键盘:连游戏里的文本框聚焦一起清掉,彻底退出键盘模式。
                    kbd_closed = true;
                    last_blur_at = Some(Instant::now());
                    pending_blur = true;
                    hide_soft_keyboard_via_java();
                    log::info!("MoleRuffle: 隐藏系统软键盘(已退出输入框聚焦)");
                }
            }
            Ok(RuffleEvent::InjectBackspace) => {
                // MoleRuffle:IME 退格键事件 → TextControl::Backspace(删除预填内容也有效)。
                if let Some(player) = playerbox.as_ref() {
                    if let Ok(mut p) = player.player.lock() {
                        p.handle_event(PlayerEvent::TextControl {
                            code: ruffle_core::events::TextControlCode::Backspace,
                        });
                        needs_redraw = true;
                    }
                }
                last_bs_inject = Some(Instant::now());
            }
            Ok(RuffleEvent::ImeDelete(n)) => {
                // MoleRuffle:隐藏输入框(真实 InputConnection)报告删除 → 注入退格。
                //   必须走 TextControl::Backspace:KeyDown 只派发 AS3 键盘事件,
                //   不进 edit_text 的文本控制路径,表现为"删除无效"。
                crate::ui_backend::IME_PROXY_ACTIVE
                    .store(true, std::sync::atomic::Ordering::Relaxed);
                if let Some(player) = playerbox.as_ref() {
                    if let Ok(mut p) = player.player.lock() {
                        for _ in 0..n.min(64) {
                            p.handle_event(PlayerEvent::TextControl {
                                code: ruffle_core::events::TextControlCode::Backspace,
                            });
                        }
                        needs_redraw = true;
                    }
                }
                // 删完回读一次真实文本校正输入条(游戏侧可能限制了删除范围)。
                sync_ime_bar_from_game(&playerbox);
            }
            Ok(RuffleEvent::ImeText(text)) => {
                crate::ui_backend::IME_PROXY_ACTIVE
                    .store(true, std::sync::atomic::Ordering::Relaxed);
                if let Some(player) = playerbox.as_ref() {
                    if let Ok(mut p) = player.player.lock() {
                        for c in text.chars() {
                            p.handle_event(PlayerEvent::TextInput { codepoint: c });
                        }
                        needs_redraw = true;
                    }
                }
            }
            Ok(RuffleEvent::KbdSyncHidden) => {
                // MoleRuffle:系统侧收起了键盘(返回键/键盘收起钮/自愈检测到)。
                //
                // ★★ 必须无条件退出"键盘模式",不能只在 soft_kb_visible==true 时才处理:
                //   游戏里那个 TextField 若仍处于聚焦态,游戏自己的"输入态"逻辑
                //   (常见的是一块全屏不可见 MovieClip 用来挡误触)就不会撤掉,
                //   表现就是主人遇到的"键盘明明关了,游戏里其它元素点不了"。
                //   发生脱钩的原因是Insets 回调偶发丢失,程序以为键盘还开着。
                soft_kb_visible = false;
                kbd_closed = true;
                kbd_close_pending = None;
                last_ime_text.clear();
                last_blur_at = Some(Instant::now());
                crate::ui_backend::VIRTUAL_KBD
                    .store(false, std::sync::atomic::Ordering::Relaxed);
                pending_blur = true;
                hide_soft_keyboard_via_java();
                log::warn!("MoleRuffle: 键盘已由系统侧收起 → 无条件退出键盘模式(清除游戏文本框聚焦)");
            }
        }

        // MoleRuffle:Flash 文本框聚焦/失焦 → 自动弹/收软键盘(完整状态机)。
        //   状态源:
        //     · VIRTUAL_KBD = 文本框当前是否聚焦(引擎 focus/blur 维护)
        //     · FOCUS_PULSE = 聚焦事件计数(每次获得焦点 +1,含 A框→B框 直接切换)
        //     · kbd_suppress = 用户用返回键/键盘收起钮主动关了键盘,先不要自动重弹
        //   行为:
        //     · 新聚焦(pulse 变化):弹键盘 + 清抑制 + 重置 IME 缓冲 —— 即使之前被
        //       用户收起(切输入框、点回输入框都能唤出)
        //     · 失焦持续 400ms:收键盘(去抖,避免 A→B 切换瞬间闪断)
        //     · 失焦本身清抑制(下次聚焦恢复自动弹)
        let flash_focus = crate::ui_backend::VIRTUAL_KBD.load(std::sync::atomic::Ordering::Relaxed);
        let pulse = crate::ui_backend::FOCUS_PULSE.load(std::sync::atomic::Ordering::Relaxed);

        // ★★ 自愈守卫(最重要的一条):键盘已经关了,但游戏仍停在"输入态"
        //    (Flash 文本框还聚焦)—— 这就是"键盘明明关了,游戏里其它元素点不动"的直接原因。
        //    摩尔的聊天框会用 Selection.setFocus 每帧把焦点抢回去,一次性清除会被立刻撤销,
        //    所以这里改成**每 300ms 重复清一次**,直到游戏真的退出输入态为止。
        if kbd_closed {
            if flash_focus {
                let due = last_blur_at
                    .map_or(true, |t| Instant::now().duration_since(t).as_millis() >= 300);
                if due {
                    last_blur_at = Some(Instant::now());
                    log::warn!(
                        "MoleRuffle: 键盘已关但游戏仍处输入态 → 再次清除文本框聚焦(自愈守卫)"
                    );
                    pending_blur = true;
                    hide_soft_keyboard_via_java();
                }
            } else {
                // 游戏自己退出输入态了,守卫下班。
                kbd_closed = false;
                last_blur_at = None;
            }
        }

        if pulse != last_focus_pulse {
            last_focus_pulse = pulse;
            // ★ 只有"刚刚真的有触摸"才认为是用户点的输入框 → 解除关闭态并弹键盘。
            //   游戏自己把焦点抢回去(收起键盘后聊天框常会这样)产生的脉冲不会被认成
            //   用户意图,键盘就不会自己弹回来(主人看到的那次"键盘又自动弹起")。
            //   窗口 1200ms:加载繁忙时事件循环一轮可能超过 800ms,脉冲被延迟处理,
            //   窗口太窄会把用户真实的点击误判成"游戏抢焦点"→ 键盘弹不出来。
            let user_gesture = last_touch
                .map(|t| Instant::now().duration_since(t).as_millis() < 1200)
                .unwrap_or(false);
            if user_gesture {
                kbd_closed = false;
                last_blur_at = None;
            }
            kbd_close_pending = None;
            // 新聚焦:重置 IME 缓冲,开始干净的输入会话(退格删旧内容走键事件通道)。
            last_ime_text.clear();
            // ★ 必须是用户刚点过才弹。游戏自己抢焦点(收起键盘后聊天框常这样)
            //   一律不弹 —— 否则就会出现"刚收起键盘它自己又冒出来"。
            if !user_gesture {
                log::warn!("MoleRuffle: 聚焦脉冲没有伴随用户触摸 → 判定为游戏自动抢焦点,不弹键盘");
            } else {
                // ★ 弹之前必须确认游戏**此刻**仍聚焦:加载繁忙时事件循环一轮可能
                //   几百毫秒,等轮到处理脉冲,用户可能已经点到别处把输入框 blur 掉了。
                //   不检查就会出现"键盘在 blur 之后才弹出来,又自动关掉"(
                //   日志 22:21:01 的实锤:TOUCH 后隔了好几条才出现"弹出软键盘")。
                let still_focused =
                    crate::ui_backend::VIRTUAL_KBD.load(std::sync::atomic::Ordering::Relaxed);
                if !still_focused {
                    log::warn!(
                        "MoleRuffle: 聚焦脉冲处理时游戏已失焦(事件循环延迟) → 放弃弹键盘"
                    );
                } else {
                    // MoleRuffle:交给 Kotlin 的迷你输入框(真实 InputConnection)弹键盘。
                    //   ★ 每次聚焦都重新调一次,不能只在 soft_kb_visible==false 时调:
                    //     上一次弹出若被系统吞掉(刚关掉键盘立刻再弹最常见),状态是 true
                    //     但键盘其实没起来,只有再调一次才有机会补弹。
                    soft_kb_visible = true;
                    show_soft_keyboard_via_java();
                    // 把游戏文本框里已有的内容镜像进输入条,这样退格能删掉预填/残留的字。
                    sync_ime_bar_from_game(&playerbox);
                    log::info!("MoleRuffle: 用户点击输入框,弹出软键盘");
                }
            }
        } else if !flash_focus && soft_kb_visible {
            // 失焦后持续 400ms 才真正收起(去抖)。
            kbd_closed = false;
            let since = *kbd_close_pending.get_or_insert(Instant::now());
            if Instant::now().duration_since(since).as_millis() >= 400 {
                kbd_close_pending = None;
                soft_kb_visible = false;
                hide_soft_keyboard_via_java();
                log::info!("MoleRuffle: 文本框失焦,自动收起软键盘");
            }
        } else {
            kbd_close_pending = None;
            if !flash_focus {
                kbd_closed = false;
            }
        }
        // 抑制期间不因"仍聚焦"自动重弹(等下一个聚焦脉冲或用户手动 ⌨)。

        // MoleRuffle:软键盘开着时轮询 IME 文本,做增删 diff 注入到聚焦的 Flash 文本框。
        //   android-activity(game-activity 后端)没有专用文本事件,靠轮询 text_input_state。
        //   ★ 删除的"双通道去重":同一物理退格可能既发 KEYCODE_DEL(键事件通道,
        //   InjectBackspace 已注入)又更新 IME 缓冲(diff 通道再看到 deletes)——
        //   若 150ms 内刚由键事件注入过退格,diff 的删除量跳过,避免双删。
        if soft_kb_visible
            && !crate::ui_backend::IME_PROXY_ACTIVE.load(std::sync::atomic::Ordering::Relaxed)
        {
            let new_text = app.text_input_state().text;
            if new_text != last_ime_text {
                let common = last_ime_text
                    .chars()
                    .zip(new_text.chars())
                    .take_while(|(a, b)| a == b)
                    .count();
                let mut deletes = last_ime_text.chars().count().saturating_sub(common);
                let added: String = new_text.chars().skip(common).collect();
                if deletes > 0 && last_bs_inject.map_or(false, |t| t.elapsed().as_millis() < 150) {
                    log::info!("MoleRuffle: 退格已由键事件通道注入,diff 删除量跳过");
                    deletes = 0;
                }
                if let Some(player) = playerbox.as_ref() {
                    if let Ok(mut p) = player.player.lock() {
                        if deletes > 0 {
                            // ★ 删除必须走 TextControl::Backspace:KeyDown 只派发 AS3 键盘事件,
                            //   不进 edit_text 的文本控制路径,表现为"删除无效"。
                            for _ in 0..deletes {
                                p.handle_event(PlayerEvent::TextControl {
                                    code: ruffle_core::events::TextControlCode::Backspace,
                                });
                            }
                        }
                        for c in added.chars() {
                            p.handle_event(PlayerEvent::TextInput { codepoint: c });
                        }
                    }
                }
                last_ime_text = new_text;
                needs_redraw = true;
            }
        }

        // MoleRuffle:看门狗阶段 2 = tick(游戏逻辑帧)。
        //   ★ 收起键盘后整机冻结的三个嫌疑里,"游戏的失焦处理代码"和"tick 里的某段
        //     AS 逻辑"都落在这里,阶段标记能让日志直接指认。
        WD_PHASE.store(2, std::sync::atomic::Ordering::Relaxed);
        // MoleRuffle:每帧开头统一执行"清聚焦"(挪到这里,避免在游戏/IME 的混乱时刻
        //   同步跑游戏的失焦 AS 代码 —— 登录界面收键盘后整机冻结的最大嫌疑)。
        if pending_blur {
            pending_blur = false;
            blur_game_text_field(&playerbox);
        }
        let new_time = Instant::now();
        let dt = new_time.duration_since(last_frame_time).as_micros();

        // MoleRuffle:★ 帧率上限(0 = 不限)。设了就限制每秒最多 tick/render 多少次,
        //   用来省电/控温;游戏原生 24FPS,调高上限不会更流畅。
        let fps_cap = perf::FPS_CAP.load(std::sync::atomic::Ordering::Relaxed);
        let cap_us: u128 = if fps_cap > 0 {
            1_000_000u128 / fps_cap as u128
        } else {
            0
        };
        let throttled = surface_alive && cap_us > 0 && (dt as u128) < cap_us;
        if throttled {
            // 还没到下一帧的间隔:不 tick 也不 render,把唤醒时间推到间隔结束。
            next_frame_time = Some(last_frame_time + Duration::from_micros(cap_us as u64));
        }

        if !surface_alive {
            // 窗口没了(切后台/系统分享/锁屏):不 tick、不 render、不碰音频。
            //   事件照常轮询,这样 Resume / 输入 都能正常收到。
            last_frame_time = new_time;
        } else if !throttled && dt > 0 {
            let tick_started = Instant::now();
            last_frame_time = new_time;
            // MoleRuffle:帧时间(µs)给性能 HUD。
            perf::FRAME_US.store(dt.min(u32::MAX as u128) as u32, std::sync::atomic::Ordering::Relaxed);
            if let Some(player) = playerbox.as_ref() {
                if let Ok(mut player) = player.player.lock() {
                    player.tick(FloatDuration::from_millis(dt as f64 / 1000.0));
                    next_frame_time = Some(new_time + player.time_til_next_frame());
                    needs_redraw = player.needs_render();
                    let audio =
                        <dyn Any>::downcast_mut::<AAudioAudioBackend>(player.audio_mut()).unwrap();
                    audio.recreate_stream_if_needed();
                }
            } else {
                next_frame_time = None;
            }
            // 单帧超过 400ms 记一笔(冻结前的最后线索)。
            let took = tick_started.elapsed().as_millis();
            if took >= 400 {
                log::warn!("MoleRuffle: tick 单帧耗时 {} ms(异常,可能即将卡死)", took);
            }
        }

        // MoleRuffle:看门狗阶段 3 = render(渲染)。
        WD_PHASE.store(3, std::sync::atomic::Ordering::Relaxed);
        // ★ 恢复后的前 N 帧跳过渲染(给重建的 surface 稳定时间,
        //   否则 Adreno 驱动在 begin_render_pass 里崩 = "导出日志返回后闪退")。
        if resume_grace_frames > 0 {
            resume_grace_frames -= 1;
        } else if needs_redraw && surface_alive && !throttled {
            if let Some(player) = playerbox.as_ref() {
                if let Ok(mut player) = player.player.lock() {
                    player.render();
                    // MoleRuffle:数真正渲染出的帧(HUD 的 FPS 用)。
                    frame_count += 1;
                }
            }
        }

        // MoleRuffle:每 500ms 采样一次 FPS 写入全局,供 JNI getPerfStats 读取。
        let hud_elapsed = last_hud_time.elapsed();
        if hud_elapsed.as_millis() >= 500 {
            let dframes = frame_count.saturating_sub(last_hud_frames);
            let secs = hud_elapsed.as_secs_f64();
            let fps = if secs > 0.0 {
                (dframes as f64 / secs).round() as u32
            } else {
                0
            };
            perf::FPS.store(fps, std::sync::atomic::Ordering::Relaxed);
            last_hud_frames = frame_count;
            last_hud_time = Instant::now();
        }

        // MoleRuffle:每 10s 一行心跳 —— 日志里看它还在不在,就知道循环是否活着。
        if last_beat.elapsed().as_secs() >= 10 {
            last_beat = Instant::now();
            log::info!(
                "MoleRuffle: 心跳 —— 事件循环存活,已渲染 {} 帧,键盘={} 游戏聚焦={}",
                frame_count,
                soft_kb_visible,
                crate::ui_backend::VIRTUAL_KBD.load(std::sync::atomic::Ordering::Relaxed)
            );
        }
    }

    unsafe {
        let vm = JavaVM::from_raw(app.vm_as_ptr() as *mut sys::JavaVM).expect("JVM must exist");
        let activity = JObject::from_raw(app.activity_as_ptr() as jobject);
        // Ensure that we take the EventSender back, or we'll leak it
        let _: Result<EventSender, _> = vm
            .get_env()
            .unwrap()
            .take_rust_field(activity, "eventLoopHandle");
    }
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_keydown(
    mut env: JNIEnv,
    this: JObject,
    key_tag: JString,
) {
    let tag: String = env
        .get_string(&key_tag)
        .expect("Couldn't get java string!")
        .into();

    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    if let Some(desc) = key_tag_to_key_descriptor(&tag) {
        let _ = event_loop.send(RuffleEvent::VirtualKeyEvent {
            down: true,
            key_descriptor: desc,
        });
    }
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_keyup(
    mut env: JNIEnv,
    this: JObject,
    key_tag: JString,
) {
    let tag: String = env
        .get_string(&key_tag)
        .expect("Couldn't get java string!")
        .into();

    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    if let Some(desc) = key_tag_to_key_descriptor(&tag) {
        let _ = event_loop.send(RuffleEvent::VirtualKeyEvent {
            down: false,
            key_descriptor: desc,
        });
    }
}

pub fn get_jvm<'a>() -> Result<(jni::JavaVM, JObject<'a>), Box<dyn std::error::Error>> {
    // Create a VM for executing Java calls
    let context = ndk_context::android_context();
    let activity = unsafe { JObject::from_raw(context.context().cast()) };
    let vm = unsafe { jni::JavaVM::from_raw(context.vm().cast()) }?;

    Ok((vm, activity))
}

/// MoleRuffle:弹/收系统软键盘 —— 走 Kotlin 的迷你输入框(EditText)。
///
/// 为什么不再用 `AndroidApp::show_soft_input`:那条路只有 GameActivity 自己的
/// text_input_state,多数中文输入法(百度/讯飞/Gboard 中文)的退格走
/// InputConnection.deleteSurroundingText,不会更新那个状态,于是"能打字、删不掉"。
/// 迷你输入框有真实 InputConnection,增删都能拿到,而且用户能看见自己打了什么。
fn show_soft_keyboard_via_java() {
    if let Ok((jvm, activity)) = get_jvm() {
        if let Ok(mut env) = jvm.attach_current_thread() {
            JavaInterface::show_soft_keyboard(&mut env, &activity);
        }
    }
}

fn hide_soft_keyboard_via_java() {
    if let Ok((jvm, activity)) = get_jvm() {
        if let Ok(mut env) = jvm.attach_current_thread() {
            JavaInterface::hide_soft_keyboard(&mut env, &activity);
        }
    }
}

/// MoleRuffle:让游戏真正"退出键盘模式" —— 清掉 Flash 文本框的聚焦。
///
/// 只把软键盘收起来是不够的:游戏里那个 TextField 还处于聚焦态,主人反馈
/// "键盘关掉后游戏内其它控件点不动"。这里直接把 focus_tracker 置空,
/// 焦点事件(FOCUS_OUT)会正常派发,游戏回到普通可点击状态。
fn blur_game_text_field(playerbox: &Option<ActivePlayer>) {
    let playerbox = match playerbox {
        Some(p) => p,
        None => return,
    };
    if let Ok(mut p) = playerbox.player.lock() {
        // ★ 打点:这一行若出现而"完成"那行不出现 = 冻结就发生在 reset_focus 内部
        //   (游戏的失焦处理代码)。主人日志里"收起键盘后整机冻结"的最大嫌疑。
        log::warn!("MoleRuffle: 清聚焦 —— 开始 reset_focus");
        p.mutate_with_update_context(|context| {
            // FocusTracker 是 Copy:先拷一份,避免"借字段 + 借整个 context"冲突。
            let tracker = context.focus_tracker;
            // ★ 必须用 reset_focus,不能用 set(None, context):
            //   set() 内部走 set_internal(.., run_actions = false),不跑排队中的 AVM1
            //   onKillFocus / onSetFocus 处理器;reset_focus 走 run_actions = true。
            //   摩尔是 AS2 游戏,它的"输入态"遮罩(关掉后其它元素才能点)正是靠
            //   onKillFocus 收起的 —— 用 set(None) 就等于没通知游戏,于是卡住。
            tracker.reset_focus(context);
        });
        log::warn!("MoleRuffle: 清聚焦 —— reset_focus 完成");
    }
    // 兜底:万一引擎的 close_virtual_keyboard 没被触发,这里直接清标志。
    crate::ui_backend::VIRTUAL_KBD.store(false, std::sync::atomic::Ordering::Relaxed);
}

/// MoleRuffle:把游戏里当前聚焦文本框的已有内容"镜像"到迷你输入条。
///
/// 不这么做的话:输入条一开始是空的,而游戏文本框里可能已经有字(默认账号、
/// 上次输入残留、Flash 里代码预填),此时按退格 —— 输入条没变化可上报,
/// 于是"删不掉已经输入的内容"。镜像之后输入条 == 游戏文本,退格才对得上。
fn sync_ime_bar_from_game(playerbox: &Option<ActivePlayer>) {
    let playerbox = match playerbox {
        Some(p) => p,
        None => return,
    };
    // ★ 这里用 ruffle_core 现有的公开 API 直接读,不依赖对 ruffle 源码打补丁
    //   (cargo 对 git 依赖按 revision 做指纹,改了源码未必会重编那个 crate)。
    let game_text: Option<String> = match playerbox.player.lock() {
        Ok(mut p) => p.mutate_with_update_context(|context| {
            context
                .focus_tracker
                .get_as_edit_text()
                .map(|edit_text| edit_text.text().to_string())
        }),
        Err(_) => None,
    };
    let game_text = match game_text {
        Some(t) => t,
        None => return,
    };
    if let Ok((jvm, activity)) = get_jvm() {
        if let Ok(mut env) = jvm.attach_current_thread() {
            JavaInterface::set_ime_bar_text(&mut env, &activity, &game_text);
        }
    }
}

/// MoleRuffle:迷你输入框报告"删掉了 n 个字符"(Kotlin → Rust)。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_imeDelete(
    mut env: JNIEnv,
    this: JObject,
    count: jint,
) {
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    if count > 0 {
        let _ = event_loop.send(RuffleEvent::ImeDelete(count as u32));
    }
}

/// MoleRuffle:设置面板改了日志开关/保留天数 → 立即生效(Kotlin → Rust)。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_setLogging(
    _env: JNIEnv,
    _this: JObject,
    enabled: u8,
    days: jint,
) {
    molelog::set_enabled(enabled != 0);
    molelog::prune_retention(days.max(1) as u32);
    log::warn!(
        "MoleRuffle: 日志记录 = {}, 保留 {} 天",
        enabled != 0,
        days.max(1)
    );
}

/// MoleRuffle:日志时区偏移(秒)—— 让 mole.log 的时间戳和手机本地时间一致。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_setLogTzOffset(
    _env: JNIEnv,
    _this: JObject,
    offset_secs: jlong,
) {
    molelog::set_tz_offset(offset_secs);
}

/// MoleRuffle:Kotlin 侧诊断消息 → 写进 mole.log(诊断面板可见)。
///   之前 logToRust 只写 logcat,真机没 adb 看不到 —— 排查键盘问题时,
///   看门狗/第二套检测到底干了什么在 mole.log 里是盲区。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_klog(
    mut env: JNIEnv,
    _this: JObject,
    msg: JString,
) {
    let Ok(java_str) = env.get_string(&msg) else {
        return;
    };
    let s: String = java_str.into();
    log::warn!("MoleRuffle[Kotlin]: {}", s);
}

/// MoleRuffle:设置资源缓存上限(MB)。悬浮球菜单里可改(0 = 不限制)。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_nativeSetCacheLimit(
    _env: JNIEnv,
    _this: JObject,
    mb: jlong,
) {
    let mb = if mb <= 0 { u64::MAX / (1024 * 1024) } else { mb as u64 };
    ruffle_frontend_utils::backends::navigator::mole_cache_set_limit_mb(mb);
    log::warn!("MoleRuffle: 资源缓存上限 = {} MB", mb);
}

/// MoleRuffle:立即按"最久未用"裁剪缓存到上限,返回释放的字节数。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_nativeTrimCache(
    _env: JNIEnv,
    _this: JObject,
    max_mb: jlong,
) -> jlong {
    let max = if max_mb <= 0 {
        None
    } else {
        Some((max_mb as u64).saturating_mul(1024 * 1024))
    };
    let freed = ruffle_frontend_utils::backends::navigator::mole_cache_trim(max);
    if freed > 0 {
        log::warn!("MoleRuffle: 裁剪缓存释放 {} KB", freed / 1024);
    }
    freed as jlong
}

/// MoleRuffle:设置渲染画质(0=低 1=中 2=高),图像设置里选,即时生效。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_nativeSetQuality(
    mut env: JNIEnv,
    this: JObject,
    level: jint,
) {
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    let _ = event_loop.send(RuffleEvent::SetQuality(level.clamp(0, 2) as u8));
}

/// MoleRuffle:设置渲染分辨率(25~100),图像设置里选,经事件在渲染线程安全点执行
///   (setFixedSize + recreate_surface),避免与渲染并发 → Adreno begin_render_pass 崩。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_nativeSetRenderScale(
    mut env: JNIEnv,
    this: JObject,
    pct: jint,
) {
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        match env.get_rust_field(this, "eventLoopHandle") {
            Ok(g) => g,
            Err(_) => return, // 渲染器未就绪(首启早期)时静默忽略,玩家创建后由 Rust 自动应用。
        };
    let _ = event_loop.send(RuffleEvent::SetRenderScale(pct.clamp(25, 100) as u8));
}

/// MoleRuffle:设置帧率上限(0 = 不限),图像设置里可选,即时生效。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_nativeSetFpsCap(
    _env: JNIEnv,
    _this: JObject,
    fps: jint,
) {
    let v = if fps <= 0 { 0 } else { fps as u32 };
    perf::FPS_CAP.store(v, std::sync::atomic::Ordering::Relaxed);
    log::warn!(
        "MoleRuffle: 帧率上限 = {}",
        if v == 0 { "不限".to_string() } else { format!("{v} FPS") }
    );
}

/// MoleRuffle:引擎实际收到的"按下"次数(Kotlin 用它跟窗口送进去的 DOWN 数对账)。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_getTouchCount(
    _env: JNIEnv,
    _this: JObject,
) -> jlong {
    perf::TOUCH_COUNT.load(std::sync::atomic::Ordering::Relaxed) as jlong
}

/// MoleRuffle:★ 直连注入触摸(Kotlin → Rust)—— 输入桥断了之后的兜底通道。
///   x/y 是屏幕原始坐标(MotionEvent 的 rawX/rawY),换算在事件循环里做。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_injectTouch(
    mut env: JNIEnv,
    this: JObject,
    x: jfloat,
    y: jfloat,
    down: u8,
) {
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    let _ = event_loop.send(RuffleEvent::InjectedTouch {
        x: x as f64,
        y: y as f64,
        down: down != 0,
    });
}

/// MoleRuffle:迷你输入框报告"新增了这段文本"(Kotlin → Rust)。
#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_imeText(
    mut env: JNIEnv,
    this: JObject,
    text: JString,
) {
    let Ok(java_str) = env.get_string(&text) else {
        return;
    };
    let s: String = java_str.into();
    if s.is_empty() {
        return;
    }
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    let _ = event_loop.send(RuffleEvent::ImeText(s));
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_toggleSoftKeyboard(
    mut env: JNIEnv,
    this: JObject,
) {
    // MoleRuffle:⌨ 按钮 → 显/隐系统自带软键盘(交给主循环处理,那里能拿到 AndroidApp)。
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    let _ = event_loop.send(RuffleEvent::ToggleSoftKeyboard);
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_syncKeyboardHidden(
    mut env: JNIEnv,
    this: JObject,
) {
    // MoleRuffle:Kotlin 监听到 IME insets 隐藏(返回键/键盘收起钮)→ 通知主循环同步状态。
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    let _ = event_loop.send(RuffleEvent::KbdSyncHidden);
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_getPerfStats(
    env: JNIEnv,
    _this: JObject,
) -> jni::sys::jstring {
    // MoleRuffle:性能 HUD 的 Rust 侧数据(FPS/帧时间/后端/分辨率),Kotlin 再补内存/温度/网络。
    use std::sync::atomic::Ordering::Relaxed;
    let fps = perf::FPS.load(Relaxed);
    let frame_us = perf::FRAME_US.load(Relaxed);
    let w = perf::SURFACE_W.load(Relaxed);
    let h = perf::SURFACE_H.load(Relaxed);
    let backend = perf::BACKEND
        .lock()
        .map(|b| b.clone())
        .unwrap_or_default();
    let ms = frame_us as f64 / 1000.0;
    let text = format!("FPS {fps}   帧 {ms:.1}ms\n渲染 {backend}   {w}x{h}");
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_requestContextMenu(
    mut env: JNIEnv,
    this: JObject,
) {
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    let _ = event_loop.send(RuffleEvent::RequestContextMenu);
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_runContextMenuCallback(
    mut env: JNIEnv,
    this: JObject,
    index: jint,
) {
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    let _ = event_loop.send(RuffleEvent::RunContextMenuCallback(index as usize));
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_clearContextMenu(
    mut env: JNIEnv,
    this: JObject,
) {
    let event_loop: MutexGuard<Sender<RuffleEvent>> =
        env.get_rust_field(this, "eventLoopHandle").unwrap();
    let _ = event_loop.send(RuffleEvent::ClearContextMenu);
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_nativeInit(
    mut env: JNIEnv,
    class: JClass,
    crash_callback: JObject,
) {
    let crash_callback = env.new_global_ref(crash_callback).unwrap();
    let jvm = env.get_java_vm().unwrap();

    /// 把字符串里形如 `C:\xxx\yyy`、`/home/xxx/yyy` 的**编译机绝对路径**替换成 `<路径>`。
///
/// Rust 的 `panic!` / `file!()` 会把编译时的源码路径硬编码进产物,真机崩溃框和日志里
/// 会显示编译机的目录结构(含用户名)。这个函数在**写日志和弹崩溃框之前**统一抹掉,
/// 既不泄露编译机信息,也不影响排查(行号和函数名都还在)。
fn sanitize_paths(s: &str) -> String {
    // 手机上自己的路径要保留(排查时有用),不能一起抹掉。
    const KEEP: [&str; 6] = ["/data/", "/storage/", "/sdcard/", "/mnt/", "/system/", "/vendor/"];
    s.split_whitespace()
        .map(|tok| {
            let core = tok.trim_matches(|c| matches!(c, '"' | '\'' | '(' | ')' | ','));
            if KEEP.iter().any(|p| core.starts_with(p)) {
                return tok;
            }
            let mut it = core.chars();
            let is_abs = match (it.next(), it.next(), it.next()) {
                // Windows: C:\ 或 C:/
                (Some(d), Some(':'), Some(b)) if d.is_ascii_alphabetic() && (b == '\\' || b == '/') => true,
                // Unix: /xxx
                (Some('/'), Some(_), _) => true,
                _ => false,
            };
            if core.len() > 3 && is_abs {
                "<路径>"
            } else {
                tok
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// MoleRuffle:组合日志 —— logcat 照旧,额外把每条日志写进 filesDir/mole.log,
    //   供 App 内「诊断日志」面板直接查看(真机排查无需 adb)。
    let android_logger_instance = android_logger::AndroidLogger::new(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("ruffle")
            .with_filter(
                android_logger::FilterBuilder::new()
                    .parse("warn,ruffle=info")
                    .build(),
            ),
    );
    if log::set_boxed_logger(Box::new(molelog::MoleLogger::new(android_logger_instance))).is_ok() {
        log::set_max_level(log::LevelFilter::Info);
    }

    panic::set_hook(Box::new(move |info| {
        let backtrace = Backtrace::new();
        let thread = thread::current();
        let thread = thread.name().unwrap_or("<unnamed>");
        let message = match info.payload().downcast_ref::<&'static str>() {
            Some(s) => *s,
            None => match info.payload().downcast_ref::<String>() {
                Some(s) => &**s,
                None => "Box<Any>",
            },
        };

        let full = match info.location() {
            Some(location) => format!(
                "thread '{}' panicked at '{}': {}:{}\n{:?}",
                thread,
                message,
                location.file(),
                location.line(),
                backtrace
            ),
            None => format!(
                "thread '{}' panicked at '{}'\n{:?}",
                thread, message, backtrace
            ),
        };
        // ★ 把"编译机上的绝对路径"抹掉再落日志/弹崩溃框。
        //   Rust 的 panic! 会把**编译时**的源码路径嵌进可执行文件,所以真机上会看到
        //   类似 C:\Users\XXX\.cargo\registry\... 的路径 —— 那是我(编译机)的目录,
        //   不是主人手机上的,但看着很怪也像泄露隐私,统一替换掉。
        let full = sanitize_paths(&full);
        log::error!(target: "panic","{}", full);

        let mut env = jvm.attach_current_thread().unwrap();
        if env.exception_check().unwrap() {
            // There's a pending exception, java will discover this on their own
        } else {
            let java_message = env.new_string(full).unwrap();
            let crash_callback = env.new_global_ref(&crash_callback).unwrap();
            env.call_method(
                crash_callback,
                "onCrash",
                "(Ljava/lang/String;)V",
                &[(&java_message).into()],
            )
            .unwrap();
        }
    }));

    JavaInterface::init(&mut env, &class)
}

fn get_loc_in_window() -> (i32, i32) {
    let (jvm, activity) = get_jvm().unwrap();
    let mut env = jvm.attach_current_thread().unwrap();

    // no worky :(
    //ndk_glue::native_activity().show_soft_input(true);

    JavaInterface::get_loc_in_window(&mut env, &activity)
}

fn get_view_size() -> Result<(i32, i32), Box<dyn std::error::Error>> {
    let (jvm, activity) = get_jvm()?;
    let mut env = jvm.attach_current_thread()?;

    let width = JavaInterface::get_surface_width(&mut env, &activity);
    let height = JavaInterface::get_surface_height(&mut env, &activity);

    Ok((width, height))
}

#[no_mangle]
fn android_main(app: AndroidApp) {
    log::info!("Starting android_main...");
    // ★★ 必须**在原来的线程**上跑 run(app),不能搬到新线程:
    //    android-activity 的 poll_events 调的是 ALooper_pollAll(用的是**调用线程**的 looper),
    //    而 app 的 looper / 唤醒管道属于 android_main 这个线程。搬到新线程后 looper 里
    //    根本没有注册 fd → ALooper_pollAll 返回 POLL_ERROR → 直接 panic「白屏」。
    //    (v4.6 就是这么炸的,已回退。)
    run(app);
}
