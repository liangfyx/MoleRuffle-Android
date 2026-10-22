//! MoleRuffle:安卓 UI 后端。
//!
//! 官方 ruffle-android 用 `NullUiBackend`,其 `load_device_font` 是空实现 —— 摩尔庄园 SWF
//! 请求的设备字体(SimSun/Arial/Noto Sans/Tahoma/Times New Roman)全部落空,中文渲染不出来。
//!
//! 这里实现一个 catch-all 后端:对**任何**字体请求都返回设备自带的 CJK 字体
//! (`/system/fonts/SysSans-Hans-Regular.ttf` 等),不打包进 APK。其余方法照抄 NullUiBackend 的 no-op。

use std::sync::Arc;
use std::sync::atomic::AtomicBool;

/// MoleRuffle:Flash 文本框是否处于聚焦状态(引擎 focus/blur 时维护)。
pub static VIRTUAL_KBD: AtomicBool = AtomicBool::new(false);

/// MoleRuffle:聚焦事件脉冲计数 —— 引擎每次文本框获得焦点(含从 A 框切到 B 框)
/// 都会调 open_virtual_keyboard,此计数 +1。主循环对比计数变化识别"新聚焦",
/// 借此在键盘已被用户收起后、点击另一个输入框时也能重新弹出。
pub static FOCUS_PULSE: std::sync::atomic::AtomicU32 =
    std::sync::atomic::AtomicU32::new(0);

/// MoleRuffle:隐藏输入框(EditText)通道是否已经在工作。
/// 一旦 Kotlin 侧送来过文本/删除事件,就停用旧的 text_input_state 轮询兜底,
/// 避免两条通道各注入一次(表现为"按一下删两个字符")。
pub static IME_PROXY_ACTIVE: AtomicBool = AtomicBool::new(false);

use ruffle_core::backend::ui::{
    DialogResultFuture, FileDialogResult, FileFilter, FontDefinition, FullscreenError,
    LanguageIdentifier, MouseCursor, MultiDialogResultFuture, MultiFileDialogResult, UiBackend,
    US_ENGLISH,
};
use ruffle_core::font::{FontFileData, FontQuery};
use url::Url;

/// 优先用**体积最小**的中文正文字体,而不是无脑吃 31MB 的 NotoSansCJK-Regular.ttc。
///
/// ★ 性能:主人反馈"太卡"。设备字体兜底是把整份字体交给 ruffle 解析,31MB 的 TTC
///   解析/取字形开销远大于 10MB 级别的 .ttf/.otf。这里把所有存在的候选都列出来,
///   按**文件体积从小到大**挑一个用(很多 ROM 有 NotoSansSC/SourceHanSans 的 otf,
///   比完整的 CJK TTC 小好几倍)。挑不到小的才退回 TTC。
const CJK_FONT_CANDIDATES: &[&str] = &[
    "/system/fonts/NotoSansSC-Regular.otf",
    "/system/fonts/NotoSansCJKsc-Regular.otf",
    "/system/fonts/NotoSansHans-Regular.otf",
    "/system/fonts/SourceHanSansCN-Regular.otf",
    "/system/fonts/SourceHanSansSC-Regular.otf",
    "/system/fonts/HarmonyOS_Sans_SC_Regular.otf",
    "/system/fonts/MiSans-Regular.otf",
    "/system/fonts/SysSans-Hans-Regular.ttf",
    "/system/fonts/SysFont-Hans-Regular.ttf",
    "/system/fonts/DroidSansFallback.ttf",
    "/system/fonts/DroidSansChinese.ttf",
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/NotoSansSC-Regular.ttf",
];

fn load_cjk_font() -> Vec<u8> {
    // 先收集所有存在的候选(路径 + 字节),再按字节数升序取最小的一个。
    let mut found: Vec<(usize, Vec<u8>, &str)> = Vec::new();
    for path in CJK_FONT_CANDIDATES {
        if let Ok(data) = std::fs::read(path) {
            if !data.is_empty() {
                found.push((data.len(), data, path));
            }
        }
    }
    found.sort_by_key(|(len, _, _)| *len);
    if let Some((len, data, path)) = found.into_iter().next() {
        log::warn!(
            "MoleRuffle: 加载系统 CJK 字体 {path}({} KB),用作缺失设备字体的回退",
            len / 1024
        );
        return data;
    }
    log::error!("MoleRuffle: 未找到任何系统 CJK 字体,游戏内中文可能缺字");
    Vec::new()
}

pub struct AndroidUiBackend {
    /// 设备 CJK 字体字节,`Arc` 共享给每个字体请求(零拷贝)。
    cjk_font: Arc<Vec<u8>>,
}

impl AndroidUiBackend {
    pub fn new() -> Self {
        Self {
            cjk_font: Arc::new(load_cjk_font()),
        }
    }
}

impl Default for AndroidUiBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl UiBackend for AndroidUiBackend {
    // ★ 唯一的实质实现:任何未知设备字体都回退到系统 CJK 字体。
    fn load_device_font(&self, query: &FontQuery, register: &mut dyn FnMut(FontDefinition)) {
        if self.cjk_font.is_empty() {
            return;
        }
        register(FontDefinition::FontFile {
            name: query.name.clone(),
            is_bold: query.is_bold,
            is_italic: query.is_italic,
            // Arc<Vec<u8>> 强转 Arc<dyn AsRef<[u8]>>,共享同一份字节。
            data: FontFileData::new_shared(self.cjk_font.clone()),
            index: 0,
        });
    }

    // 以下全部照抄 NullUiBackend 的 no-op。
    fn mouse_visible(&self) -> bool {
        true
    }

    fn set_mouse_visible(&mut self, _visible: bool) {}

    fn set_mouse_cursor(&mut self, _cursor: MouseCursor) {}

    fn clipboard_content(&mut self) -> String {
        "".into()
    }

    fn set_clipboard_content(&mut self, _content: String) {}

    fn set_fullscreen(&mut self, _is_full: bool) -> Result<(), FullscreenError> {
        Ok(())
    }

    fn display_root_movie_download_failed_message(&self, _invalid_swf: bool, _fetch_error: String) {}

    fn message(&self, _message: &str) {}

    fn display_unsupported_video(&self, _url: Url) {}

    fn sort_device_fonts(
        &self,
        _query: &FontQuery,
        _register: &mut dyn FnMut(FontDefinition),
    ) -> Vec<FontQuery> {
        Vec::new()
    }

    fn open_virtual_keyboard(&self) {
        // MoleRuffle:文本框获得焦点 → 置聚焦标志 + 聚焦脉冲 +1(主循环识别新聚焦)。
        VIRTUAL_KBD.store(true, std::sync::atomic::Ordering::Relaxed);
        FOCUS_PULSE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }

    fn close_virtual_keyboard(&self) {
        // MoleRuffle:文本框失焦 → 请求收起软键盘。
        VIRTUAL_KBD.store(false, std::sync::atomic::Ordering::Relaxed);
    }

    fn language(&self) -> LanguageIdentifier {
        US_ENGLISH.clone()
    }

    fn display_file_open_dialog(&mut self, _filters: Vec<FileFilter>) -> Option<DialogResultFuture> {
        Some(Box::pin(async move { Ok(FileDialogResult::Canceled) }))
    }

    fn display_file_open_dialog_multiple(
        &mut self,
        _filters: Vec<FileFilter>,
    ) -> Option<MultiDialogResultFuture> {
        Some(Box::pin(async move { Ok(MultiFileDialogResult::Canceled) }))
    }

    fn close_file_dialog(&mut self) {}

    fn display_file_save_dialog(
        &mut self,
        _file_name: String,
        _domain: String,
    ) -> Option<DialogResultFuture> {
        None
    }
}
