//! MoleRuffle:ExternalInterface 后端。
//!
//! 真机日志抓到的致命错误:
//! ```text
//! ERROR [ruffle_core::avm2] Error running AVM2 construction for movie clip:
//!   Error #2067: The ExternalInterface is not available in this container.
//!   at flash.external::ExternalInterface$/addCallback()
//!   at ClientSocketDLL()
//! ```
//! 平行服的 `dll/ClientSocketDLL.swf` 一构造就要 `ExternalInterface.addCallback()`
//! (网页版 Ruffle WASM 由 JS 提供,所以没问题)。原生端没有 provider →
//! `ExternalInterface.available` 为 false → addCallback 直接抛错 →
//! **负责连游戏服务器的 socket DLL 没初始化** → 选服后再无任何连接、进度条卡 0%。
//!
//! 这里照桌面版(desktop/src/backends/external_interface.rs)的做法补上 provider:
//!   - 查询地址栏(`window.location.href` 等)返回游戏地址,满足域名守卫;
//!   - 其余调用记日志后返回 Undefined(与桌面端一致);
//!   - 注册回调也记日志,方便看游戏对外暴露了哪些入口。

use ruffle_core::context::UpdateContext;
use ruffle_core::external::{ExternalInterfaceProvider, Value as ExternalValue};
use url::Url;

pub struct AndroidExternalInterfaceProvider {
    pub game_url: Option<Url>,
}

fn is_location_href(code: &str) -> bool {
    matches!(
        code,
        "document.location.href" | "window.location.href" | "top.location.href"
    )
}

impl ExternalInterfaceProvider for AndroidExternalInterfaceProvider {
    fn call_method(
        &self,
        _context: &mut UpdateContext<'_>,
        name: &str,
        args: &[ExternalValue],
    ) -> ExternalValue {
        // MoleRuffle:把每次调用都记下来,便于下轮看游戏究竟想要什么。
        let arg_str: String = args
            .iter()
            .take(4)
            .map(|v| format!("{:?}", v))
            .collect::<Vec<_>>()
            .join(", ");
        let arg_str: String = arg_str.chars().take(240).collect();
        log::warn!("MoleRuffle: ExternalInterface.call({}) [{}]", name, arg_str);

        if let Some(ref url) = self.game_url {
            if let Some(stripped) = name.strip_suffix(".toString") {
                if is_location_href(stripped) {
                    return url.to_string().into();
                }
            }
        }

        if name == "eval" {
            if let Some(ref url) = self.game_url {
                if let [ExternalValue::String(code)] = args {
                    if is_location_href(code.as_str()) {
                        return ExternalValue::String(url.to_string());
                    }
                }
            }
            return ExternalValue::Undefined;
        }

        ExternalValue::Undefined
    }

    fn on_callback_available(&self, name: &str) {
        log::warn!("MoleRuffle: 游戏注册了 ExternalInterface 回调: {}", name);
    }

    fn get_id(&self) -> Option<String> {
        None
    }
}
