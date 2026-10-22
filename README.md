# MoleRuffle Android

把 **摩尔庄园网页版**（官方 Flash 客户端）跑在 Android 上的**原生客户端**——基于 [Ruffle](https://github.com/ruffle-rs/ruffle) 引擎（Rust + wgpu），**非 WASM、非 WebView**，直接原生渲染 + 原生 TCP。

本项目衍生自 [**moleworld-dev/MoleRuffle**](https://github.com/moleworld-dev/MoleRuffle)（跨平台原生客户端），聚焦 **Android 移植 + 兼容性/稳定性/性能修复**。

> 非官方项目，与上海淘米无任何关系。游戏资源与服务器属于上海淘米，本仓库不分发任何游戏资源，运行时由客户端直接从官方地址加载。

---

## 致谢

- [**moleworld-dev/MoleRuffle**](https://github.com/moleworld-dev/MoleRuffle) —— 本项目的基础：Ruffle 原生客户端骨架、原生 TCP 直连、资源加载加固。**没有它就没有这个 Android 版**。
- [Ruffle](https://github.com/ruffle-rs/ruffle) —— Flash 引擎（引擎层，MIT/Apache-2.0）。
- [Earthink030918/mole-flash-android](https://github.com/Earthink030918/mole-flash-android) —— 借鉴了其「GPU 显存看护 / 自动降档」与「崩溃兜底」思路。

---

## 相比上游做了什么

### Android 移植
- Kotlin 壳 + Rust `cdylib`（`android/`），`cargo-ndk` 交叉编译 arm64-v8a；
- 原生 TCP + 本地代理桥（`local_proxy`），打通登录 / 地图 / 走路协议；
- 触屏控件（虚拟摇杆、按键布局、可拖拽 / 隐藏）、全屏沉浸、性能 HUD、渲染分辨率档位。

### 稳定性 / 兼容性修复（对 Ruffle 的改动见 `patches/ruffle-android-fixes.patch`）
| 修复 | 说明 |
|---|---|
| AVM2 递归深度守卫 + 自噬修复 | 防深递归爆栈闪退；并修复「守卫抛错 → 造错误又走 exec → 无限递归」的自噬循环 |
| 时间轴子对象构造 | SWFv9 AVM2 goto 补 `construct_frame`，修复帧脚本访问 timeline 摆放对象为 null（如拉姆教室 tips 槽） |
| 纹理池泄漏 | texture_pool 周期性重置，缓解长期游玩内存增长 → OOM |
| **fence-based 精确回收** | 按 GPU 提交 fence 判断纹理池回收时机，避免「GPU 在用时释放」→ Adreno GLES 驱动 memset 崩 |
| 缓存 stale-while-revalidate | 共享对象缓存过期后立即用本地缓存（不再阻塞逐个联网校验）→ 家园 / 农场 / 背包 / 衣服加载更快、元素一次性出现 |
| 日志 UTF-8 安全截断 | 修复日志含中文时 `truncate` 切在多字节边界 panic |
| 崩溃自动降档 | `ApplicationExitInfo` 检测崩溃 → 自动降一档渲染分辨率（兜底自愈） |

## 已知问题

- **Adreno GLES 驱动崩溃**：部分骁龙设备（如 Adreno 750）切换复杂场景（家园 / 城堡等）时，`wgpu-hal` 的 `gl.flush()` 内厂商驱动 `libGLESv2_adreno.so` 会在 `__memset_aarch64` 崩溃。属**驱动层**问题，应用侧难根治；本项目已加「崩溃自动降档 + 重载」兜底。可尝试在设置里切 **Vulkan / OpenGL ES** 对比稳定性。
- 「家园装饰一个个浮现」：游戏自身用 `QueueLoader` 串行加载多个 SWF，非引擎问题。

## 构建（Android）

```bash
# 1. 准备 ruffle 源码（pin 的版本 304a3c9d），并应用 patches/ruffle-android-fixes.patch
#    android/Cargo.toml 通过 path 引用 ../../ruffle-src
# 2. 编译 .so
cd android
cargo ndk --target arm64-v8a --platform 26 -o app/src/main/jniLibs build --release
# 3. 打包
./gradlew assembleRelease
```

依赖：JDK 17、Android SDK、NDK 27.x、cargo-ndk。

## 结构

```
MoleRuffle-android-v0.1/
├── android/              # Android 壳（Kotlin + Rust cdylib）
├── crates/moleruffle-core/
├── desktop/  ios/  docs/
└── patches/              # 对 ruffle 的修复补丁
```

## License

沿用上游：`MIT OR Apache-2.0`（见 `LICENSE-MIT` / `LICENSE-APACHE`）。Ruffle 为 MIT/Apache-2.0 双许可。游戏内容版权归上海淘米所有，本仓库不含任何游戏资源。
