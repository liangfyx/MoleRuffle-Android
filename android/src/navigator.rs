use ruffle_frontend_utils::backends::navigator::NavigatorInterface;
use std::fs::File;
use std::path::Path;
use url::Url;

#[derive(Clone)]
pub struct AndroidNavigatorInterface;

// TODO: Prompt the user for these things!
impl NavigatorInterface for AndroidNavigatorInterface {
    fn navigate_to_website(&self, url: Url) {
        // TODO: Implement `ask` behavior!
        let _ = webbrowser::open(url.as_ref());
    }

    async fn open_file(&self, path: &Path) -> std::io::Result<File> {
        File::open(path)
    }

    async fn confirm_socket(&self, host: &str, port: u16) -> bool {
        // MoleRuffle:记录游戏实际连了哪(诊断卡进度条/白屏用,logcat 过滤 ruffle)。
        log::info!("MoleRuffle: 放行 socket 连接 {host}:{port}");
        true
    }
}
