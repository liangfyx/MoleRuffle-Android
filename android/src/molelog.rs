//! MoleRuffle:组合日志器。
//!
//! 原生端排查"进不去游戏"全靠日志。logcat 需要 adb,真机上不方便;
//! 这里在 android_logger(写 logcat)之外,再把每条日志**追加写入**应用私有目录的
//! `mole.log`,Kotlin 侧的「诊断日志」面板直接读这个文件展示/分享。
//!
//! 文件大小超过 1.5MB 时自动截断重写,避免撑爆磁盘。

use android_logger::AndroidLogger;
use log::{Log, Metadata, Record};
use std::fs::{File, OpenOptions};
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicUsize, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_BYTES: usize = 1_500_000;
const MAX_LINE: usize = 600;

static PATH: Mutex<Option<PathBuf>> = Mutex::new(None);
static SIZE: AtomicUsize = AtomicUsize::new(0);
/// 日志开关(默认关 —— 由设置面板控制,避免长期写盘)。
static ENABLED: AtomicBool = AtomicBool::new(false);
/// 设备时区相对 UTC 的偏移(秒)。由 Kotlin 启动时把 `TimeZone.getDefault().getOffset()`
///   传进来 —— 日志行首的 MM-DD HH:MM:SS 才和手机时间一致(之前是 UTC,差 8 小时)。
static TZ_OFFSET: AtomicI64 = AtomicI64::new(0);

/// 设置时区偏移(秒)。Kotlin 侧启动时调用一次。
pub fn set_tz_offset(secs: i64) {
    TZ_OFFSET.store(secs, Ordering::Relaxed);
}

/// 当前"本地时间"的 epoch 秒(已加时区偏移)。
fn local_secs() -> u64 {
    let utc = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0) as i64;
    (utc + TZ_OFFSET.load(Ordering::Relaxed)).max(0) as u64
}

/// 设置里打开/关闭日志记录。关闭时立即停止落盘(路径保留,下次打开能接着写)。
pub fn set_enabled(on: bool) {
    // 关日志时要把句柄 flush 并关掉,保证日志文件完整可读。
    if !on {
        close_writer();
    }
    ENABLED.store(on, Ordering::Relaxed);
}

pub fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

/// 按保留天数清理旧日志(只保留最近 days 天的行)。仅在启用时执行。
pub fn prune_retention(days: u32) {
    if !enabled() {
        return;
    }
    let path = match PATH.lock() {
        Ok(guard) => match guard.as_ref() {
            Some(p) => p.clone(),
            None => return,
        },
        Err(_) => return,
    };
    close_writer();
    let Ok(content) = std::fs::read_to_string(&path) else {
        return;
    };
    let cutoff = today_ordinal().saturating_sub(days);
    let kept: Vec<&str> = content
        .lines()
        .filter(|line| {
            match parse_line_ordinal(line) {
                Some(ord) => ord >= cutoff,
                // 认不出日期的行(老格式)一律保留,宁可多留别误删。
                None => true,
            }
        })
        .collect();
    if kept.len() == content.lines().count() {
        return;
    }
    let mut out = kept.join("\n");
    out.push('\n');
    let _ = std::fs::write(&path, out);
    SIZE.store(0, Ordering::Relaxed);
}

/// 事件循环里拿到存储目录后调用,之后日志开始落盘(仅当开关已开)。
pub fn set_path(path: PathBuf) {
    // ★ 以前这里有 `if !enabled() { return; }` —— 启动时日志是关的,路径就**永远没被设置**,
    //   之后在设置里打开日志,append() 因 PATH 为空直接返回 → **必须重启才生效**
    //   (主人报的 bug)。现在不管开关状态都把路径记下来。
    close_writer();
    if let Ok(mut guard) = PATH.lock() {
        *guard = Some(path);
    }
}

pub fn path() -> Option<PathBuf> {
    PATH.lock().ok().and_then(|g| g.clone())
}

pub struct MoleLogger {
    inner: AndroidLogger,
}

impl MoleLogger {
    pub fn new(inner: AndroidLogger) -> Self {
        Self { inner }
    }
}

impl Log for MoleLogger {
    fn enabled(&self, _metadata: &Metadata) -> bool {
        // 落盘不过滤:logcat 侧的 filter 只作用于 android_logger。
        true
    }

    fn log(&self, record: &Record) {
        self.inner.log(record);
        append(record);
    }

    fn flush(&self) {
        self.inner.flush();
    }
}

/// ★ 日志文件的**常驻句柄**。
///   以前每一条日志都 `OpenOptions::open()` 一次 + 写 + flush —— 开着日志时每秒几百行,
///   全变成系统调用,直接吃游戏帧(主人反馈"动画不流畅"的重要原因之一)。
///   现在只开一次,用 BufWriter 攒着,每 64 行(或关闭/轮转时)才 flush 一次。
static WRITER: Mutex<Option<BufWriter<File>>> = Mutex::new(None);
/// 自上次 flush 以来攒了多少行。
static UNFLUSHED: AtomicUsize = AtomicUsize::new(0);

/// 关闭并 flush 日志句柄(关日志、清理保留天数、换路径时都要先关,
/// 这样外部读文件才能看到完整内容)。
fn close_writer() {
    if let Ok(mut g) = WRITER.lock() {
        if let Some(mut w) = g.take() {
            let _ = w.flush();
        }
    }
    UNFLUSHED.store(0, Ordering::Relaxed);
}

fn append(record: &Record) {
    if !enabled() {
        return;
    }
    let path = match PATH.lock() {
        Ok(guard) => match guard.as_ref() {
            Some(p) => p.clone(),
            None => return,
        },
        Err(_) => return,
    };

    let secs = local_secs();
    let (month, day) = month_day(secs / 86400);
    let h = (secs / 3600) % 24;
    let m = (secs / 60) % 60;
    let s = secs % 60;

    let mut msg = format!(
        "{:02}-{:02} {:02}:{:02}:{:02} {} [{}] {}",
        month,
        day,
        h,
        m,
        s,
        record.level(),
        record.target(),
        record.args()
    );
    msg = msg.replace('\n', " | ");
    if msg.len() > MAX_LINE {
        // ★ MoleRuffle:安全截断 —— String::truncate 在切点不是 UTF-8 字符边界时会 panic
        //   (assertion failed: is_char_boundary),日志含中文常踩。回退到最近的字符边界再截。
        let mut end = MAX_LINE;
        while end > 0 && !msg.is_char_boundary(end) {
            end -= 1;
        }
        msg.truncate(end);
    }
    let line = format!("{}\n", msg);

    let mut guard = match WRITER.lock() {
        Ok(g) => g,
        Err(_) => return,
    };

    // 超过 1.5MB → 轮转:先关掉旧句柄,再截断重建。
    if SIZE.load(Ordering::Relaxed) > MAX_BYTES {
        if let Some(mut w) = guard.take() {
            let _ = w.flush();
        }
        UNFLUSHED.store(0, Ordering::Relaxed);
        SIZE.store(0, Ordering::Relaxed);
        match OpenOptions::new()
            .write(true)
            .truncate(true)
            .create(true)
            .open(&path)
        {
            Ok(f) => *guard = Some(BufWriter::new(f)),
            Err(_) => return,
        }
    }

    // 首次(或轮转后)打开。
    if guard.is_none() {
        match OpenOptions::new().create(true).append(true).open(&path) {
            Ok(f) => *guard = Some(BufWriter::new(f)),
            Err(_) => return,
        }
    }

    if let Some(w) = guard.as_mut() {
        if w.write_all(line.as_bytes()).is_ok() {
            SIZE.fetch_add(line.len(), Ordering::Relaxed);
            let n = UNFLUSHED.fetch_add(1, Ordering::Relaxed) + 1;
            // 每 64 行 flush 一次:既保证日志基本实时,又不会每行都砸系统调用。
            if n >= 64 {
                let _ = w.flush();
                UNFLUSHED.store(0, Ordering::Relaxed);
            }
        }
    }
}

/// 今天在"当年"里的序号(1月1日 = 0),用于按天清理(与行首 MM-DD 同口径)。
fn today_ordinal() -> u32 {
    let days = local_secs() / 86400;
    let (m, d) = month_day(days);
    ordinal_of(m, d)
}

/// 解析行首的 "MM-DD" 为当年序号。
fn parse_line_ordinal(line: &str) -> Option<u32> {
    let b = line.as_bytes();
    if b.len() < 5 || b[2] != b'-' {
        return None;
    }
    let month: u32 = line[0..2].parse().ok()?;
    let day: u32 = line[3..5].parse().ok()?;
    if month < 1 || month > 12 || day < 1 || day > 31 {
        return None;
    }
    Some(ordinal_of(month, day))
}

fn ordinal_of(month: u32, day: u32) -> u32 {
    let mut ord = 0u32;
    for m in 1..month {
        ord += days_in_month(m);
    }
    ord + day - 1
}

fn days_in_month(m: u32) -> u32 {
    match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 => 28,
        _ => 0,
    }
}

/// 把"距 epoch 的天数"换算成(月, 日)(Howard Hinnant 的 civil_from_days 简版)。
fn month_day(days: u64) -> (u32, u32) {
    let z = days + 719468;
    let era = z / 146097;
    let doe = (z - era * 146097) as i64; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; // [0, 399]
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32; // [1, 12]
    (m, d)
}
