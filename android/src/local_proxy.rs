//! MoleRuffle:本地 TCP 转发代理(带真实服务器自动探测)。
//!
//! 平行服(mole.61player.com)的 `config/Server.xml` 把 `GameSer` 配成
//! `127.0.0.1:3200` —— 这是给"网页 + 本地 ws 桥"方案用的(浏览器里 Ruffle WASM
//! 不能裸 TCP,靠 ws://127.0.0.1:9882 桥转发)。原生端没有桥,游戏直连
//! 127.0.0.1:3200 会被拒。
//!
//! 难点:官方把 3200 指向哪台真实服务器并没有写在任何配置文件里
//! (官网 Server.xml 的 GameSer 是 123.206.131.63:3200,平行服白名单里则是
//! 63:3001 / 236:1865 / 236:1965 / 236:1201)。所以这里不再硬猜,而是
//! **逐个探测**:把游戏发来的首个包依次投给候选服务器,谁先回数据就用谁,
//! 之后双向透传,对游戏完全透明。全过程写日志(可在 App 内「诊断日志」查看)。

use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::thread;
use std::time::Duration;

/// 游戏要连的地址(Server.xml GameSer,不动游戏配置)。
const LISTEN: &str = "127.0.0.1:3200";

/// 候选真实游戏服务器。
///
/// 排序依据:平行服官方安卓端(Moleverse-Beta.apk)的桥**只允许**这几个地址
/// (ruffle-hosts.json),而官服 Server.xml 的 GameSer 123.206.131.63:3200
/// 并不在其中 → 平行服多半不接官服游戏端口。所以把平行服主机上的端口排前面,
/// 官服 3200 只作兜底。
const CANDIDATES: [&str; 5] = [
    "123.206.131.236:1865",
    "123.206.131.236:1965",
    "123.206.131.236:1201",
    "123.206.131.63:3001",
    "123.206.131.63:3200",
];

const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
const FIRST_PACKET_WAIT: Duration = Duration::from_millis(2500);
const REPLY_WAIT: Duration = Duration::from_millis(2500);

/// 启动本地代理(独立线程,失败只记日志不致命)。
pub fn start() {
    thread::Builder::new()
        .name("mole-local-proxy".into())
        .spawn(|| {
            let listener = match TcpListener::bind(LISTEN) {
                Ok(l) => l,
                Err(e) => {
                    log::warn!("MoleRuffle: 本地代理 {} 绑定失败: {}", LISTEN, e);
                    return;
                }
            };
            log::warn!("MoleRuffle: 本地代理 {} 已启动,候选: {:?}", LISTEN, CANDIDATES);
            for stream in listener.incoming() {
                match stream {
                    Ok(client) => {
                        thread::spawn(move || handle(client));
                    }
                    Err(e) => {
                        log::warn!("MoleRuffle: 本地代理 accept 失败: {}", e);
                    }
                }
            }
        })
        .ok();
}

fn handle(mut client: TcpStream) {
    let _ = client.set_nodelay(true);
    let peer = client.peer_addr().ok();

    // 游戏连进来后通常立刻发登录/进服包;也可能先等服务器说话。两种都支持。
    let first = read_some(&mut client, FIRST_PACKET_WAIT);
    log::warn!(
        "MoleRuffle: 游戏连入本地 3200(peer={:?}),首发 {} 字节",
        peer,
        first.len()
    );

    // 逐个候选试:谁回的数据最多,就认定谁是真实游戏服。
    // (错误/拒绝包通常很短甚至没有,真实进服包会长得多,所以按字节数挑最优。)
    let mut best: Option<(usize, String, TcpStream, Vec<u8>)> = None;
    for (i, target) in CANDIDATES.iter().enumerate() {
        match probe(target, &first) {
            Ok((server, reply)) => {
                let n = reply.len();
                log::warn!("MoleRuffle: 候选[{}] {} 回了 {} 字节", i + 1, target, n);
                let better = match &best {
                    Some((bn, _, _, _)) => n > *bn,
                    None => true,
                };
                if better {
                    best = Some((n, target.to_string(), server, reply));
                }
            }
            Err(e) => {
                log::warn!("MoleRuffle: 候选[{}] {} 不可用: {}", i + 1, target, e);
            }
        }
    }

    if let Some((n, target, server, reply)) = best {
        log::warn!("MoleRuffle: 代理选定 {} (响应 {} 字节)", target, n);
        if client.write_all(&reply).is_err() {
            return;
        }
        // 注意:探测阶段 probe() 已经把首发包发给过这台服务器,这里不能重复发。
        pump_both(client, server);
        return;
    }

    // 全部无响应:退回第一个候选盲转,至少不让连接悬空。
    log::warn!(
        "MoleRuffle: {} 个候选均无响应,回退盲转 {}",
        CANDIDATES.len(),
        CANDIDATES[0]
    );
    match TcpStream::connect_timeout(&resolve(CANDIDATES[0]), CONNECT_TIMEOUT) {
        Ok(mut server) => {
            let _ = server.set_nodelay(true);
            if !first.is_empty() {
                let _ = server.write_all(&first);
            }
            pump_both(client, server);
        }
        Err(e) => {
            log::warn!("MoleRuffle: 回退连接也失败: {}", e);
        }
    }
}

/// 连上候选服务器,转发游戏首个包,取回它的第一包回应(不清空,交给调用方)。
fn probe(target: &str, first: &[u8]) -> Result<(TcpStream, Vec<u8>), String> {
    let addr = resolve(target);
    let mut server = TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT)
        .map_err(|e| format!("连接失败 {}", e))?;
    let _ = server.set_nodelay(true);

    if !first.is_empty() {
        server
            .write_all(first)
            .map_err(|e| format!("发送首发包失败 {}", e))?;
    }

    server
        .set_read_timeout(Some(REPLY_WAIT))
        .map_err(|e| format!("设置读超时失败 {}", e))?;

    let mut buf = [0u8; 8192];
    match server.read(&mut buf) {
        Ok(0) => Err("服务器立刻断开".to_string()),
        Ok(n) => {
            server
                .set_read_timeout(None)
                .map_err(|e| format!("清除读超时失败 {}", e))?;
            Ok((server, buf[..n].to_vec()))
        }
        Err(e) => Err(format!("等待回应超时/出错 {}", e)),
    }
}

fn resolve(target: &str) -> SocketAddr {
    target
        .to_socket_addrs()
        .ok()
        .and_then(|mut it| it.next())
        .unwrap_or_else(|| "127.0.0.1:1".parse().unwrap())
}

/// 在超时窗口内尽量读一点客户端数据;没数据就返回空。
fn read_some(client: &mut TcpStream, wait: Duration) -> Vec<u8> {
    let _ = client.set_read_timeout(Some(wait));
    let mut buf = [0u8; 8192];
    let out = match client.read(&mut buf) {
        Ok(n) => buf[..n].to_vec(),
        Err(_) => Vec::new(),
    };
    let _ = client.set_read_timeout(None);
    out
}

fn pump_both(client: TcpStream, server: TcpStream) {
    let c2 = match client.try_clone() {
        Ok(c) => c,
        Err(_) => return,
    };
    let s2 = match server.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let up = thread::spawn(move || pump(c2, s2)); // 游戏 -> 服务器
    let down = thread::spawn(move || pump(server, client)); // 服务器 -> 游戏
    let _ = up.join();
    let _ = down.join();
    log::warn!("MoleRuffle: 一条代理连接结束");
}

fn pump(mut from: TcpStream, mut to: TcpStream) {
    let mut buf = [0u8; 16384];
    loop {
        match from.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                if to.write_all(&buf[..n]).is_err() {
                    break;
                }
            }
        }
    }
    let _ = to.shutdown(Shutdown::Write);
}
