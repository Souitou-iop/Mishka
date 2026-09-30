<div align="center">

# Mishka

**基于 [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) 和 [mihomo](https://github.com/MetaCubeX/mihomo) 的 Android 代理客户端**

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

</div>

---

## 简介

Mishka 是一个使用 [mihomo](https://github.com/MetaCubeX/mihomo) 内核的 Android 代理客户端，界面基于 [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) 和 Jetpack Compose，数据层使用 Room、Ktor 与 Koin。内核通过 JNI 与独立进程两条路径接入，支持 VPN、ROOT TUN 与 ROOT TPROXY 三种运行模式。

应用版本不在 README 中重复维护：构建时从 `BuildConfig.VERSION_NAME` 与 `BuildConfig.VERSION_CODE` 生成。

## 仓库与子模块

当前工作区对应的可用远程仓库如下：

- Mishka fork：[`Souitou-iop/Mishka`](https://github.com/Souitou-iop/Mishka)
- mihomo 子模块 fork：[`Souitou-iop/mihomo`](https://github.com/Souitou-iop/mihomo)，分支为 `Mishka`
- scripta 子模块：[`YuKongA/scripta`](https://github.com/YuKongA/scripta)
- mihomo 上游项目：[`MetaCubeX/mihomo`](https://github.com/MetaCubeX/mihomo)

克隆 fork 并初始化子模块：

```bash
git clone https://github.com/Souitou-iop/Mishka.git
cd Mishka
git submodule update --init --recursive
```

> TODO（维护者确认）：当前 checkout 的 `origin` 指向 `YuKongA/Mishka`、`fork` 指向 `Souitou-iop/Mishka`。如 PR 目标或默认 remote 约定不同，请在发布前同步修改本节；本文不把上游 PR 流程当作已确认事实。

## 功能

### 代理与内核

- 内置 mihomo 内核（Mishka fork），统一的 `libmihomo.so` 同时承担运行时入口与订阅导入 JNI 接口。
- 三种隧道模式：
  - **VPN**：使用系统 `VpnService`，无需 Root。
  - **ROOT TUN**：由 Root mihomo 进程创建 TUN，并支持大包聚合配置。
  - **ROOT TPROXY**：使用 mihomo `tproxy-port`、iptables 与策略路由透明接管流量。
- 分应用代理：VPN、ROOT TUN、ROOT TPROXY 分别使用系统 VPN、mihomo 包过滤或 iptables UID 规则。
- ROOT 热点流量支持绕过代理或进入透明代理路径；内核不支持 `xt_TPROXY` 时，热点代理路径可降级到较慢的 TUN 方案。
- 实时流量、连接、日志、DNS 查询与 Provider 管理。

### 订阅与配置

- 支持 URL、本地文件、二维码与深链导入。
- Pending → Processing → Imported 三阶段导入管线，支持校验、取消与失败后重试。
- 支持每个订阅单独设置 User-Agent。
- 支持 **age 加密订阅**：密钥按订阅保存；配置与 Provider 文件保持加密落盘，在校验或运行时由 mihomo 解密。设置页可生成 X25519 或混合密钥对。
- 支持订阅级 **YAML** 与 **JavaScript** override，可从远程 URL、本地文件或空白模板创建，并按订阅选择与排序后应用。
- 支持自动更新与手动更新全部订阅。

### Tailscale

- 支持将 Tailscale 作为 mihomo 出站，生成名为 `Mishka Tailscale` 的代理，并为 Tailnet IPv4/IPv6 网段插入规则。
- 设置页支持 Auth key、Control server URL、设备名、Exit node、接受路由、UDP、临时设备与 Exit node 局域网访问选项。
- 代理运行时可查看当前设备与 Tailnet 设备状态，包括在线状态、地址和最近活动时间。

### 备份与体验

- 支持本地文件与 WebDAV 备份/恢复，归档包含订阅、override、必要偏好与本地配置目录。
- 恢复前要求停止代理；恢复会覆盖本地数据，并在完成后重启应用以重新加载内存状态。
- WebDAV 使用固定文件名覆盖式备份；凭据不作为普通偏好导出。
- 深浅色、跟随系统、Monet 动态取色、宽屏 NavigationRail、Quick Settings 磁贴、开机自启与 Wi-Fi 自动切换。

### ADB CLI

Mishka 通过仅允许 ADB shell 调用的 ContentProvider 暴露结构化 CLI，宿主机可使用 [`scripts/mishka-cli`](scripts/mishka-cli) 控制代理、订阅、override、设置、诊断与运行中的 mihomo。它不新增 LAN 监听端口，详见 [`docs/cli.md`](docs/cli.md)。

## Tailscale 配置

1. 打开 **设置 → Tailscale**。
2. 填入 Auth key 并打开 **Enable**。只有在功能启用且 Auth key 非空时，启动代理才会生成 Tailscale 出站配置。
3. 按需填写：
   - **Control server URL**：留空使用默认控制服务器；填写时必须是带主机名的 `http://` 或 `https://` URL。
   - **Device name**：留空使用 Android 设备名。
   - **Exit node**：留空不使用 Exit node；填写后可按需打开 Exit node 局域网访问。
   - **Accept routes / UDP / Ephemeral**：按 Tailnet 策略选择。
4. 修改配置后停止并重新启动代理，使新的 transform 配置生效。
5. 代理运行后返回 Tailscale 设置页，可刷新设备状态。设备列表只在代理连接可用时请求 mihomo 的 `/tailscale/status` 接口。

Auth key 属于敏感凭据，不要提交到 Git、截图或日志中。自建控制服务器的实际兼容性取决于其 Tailscale 服务端配置，Mishka 这里只做 URL 格式校验。

## 构建与开发

### 环境

- JDK 21
- Android SDK 与 NDK（包含 clang）
- Go（以 `mihomo/go.mod` 的模块要求为准）
- Git（用于子模块与构建版本号）

### 本地构建

```bash
# 仅修改 Kotlin 时的快速编译，跳过 mihomo cgo 构建
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a

# 下载 GeoIP 资源；该任务被显式调用时会重新获取上游 latest 数据
./gradlew :app:downloadGeoFiles

# 构建 APK；assemble 会触发 mihomo cgo 交叉编译与 CMake
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

### 发布流程

1. 在干净 clone 上初始化递归子模块，并确认 `mihomo` 指向维护者 fork 的 `Mishka` 分支。
2. 运行 `:app:downloadGeoFiles`。任务会检查 HTTP 状态和文件大小，先写入临时文件再替换目标；不要把任务显示为成功当作 GeoIP 内容已经正确，发布前应确认资源文件实际存在。
3. 运行 `:app:assembleDebug` 与 `:app:assembleRelease`，确认 APK 产物、ABI 与签名状态符合发布目标。签名凭据只从构建环境提供，不能写入 README 或仓库。
4. 如需刷新 Baseline Profile，在连接的 ARM64 真机上运行：

   ```bash
   ./gradlew :app:generateReleaseBaselineProfile
   ```

   该任务需要 `adb` 和真实设备；生成结果写入 `app/src/release/generated/baselineProfiles/`，应在审阅后提交。CI 不负责生成 Baseline Profile。

5. 当前 GitHub Actions 工作流会依次执行 GeoIP 下载、Debug 构建、Release 构建并上传 APK artifact；它不是签名发布或真实设备兼容性证明。
6. 发布前运行 `git diff --check`，检查构建版本来自 `BuildConfig.VERSION_NAME`/`BuildConfig.VERSION_CODE`，并保留构建日志与 artifact 记录。

## 已知限制与真实设备兼容性

- 当前 Gradle 配置只构建 `arm64-v8a`，因此不能把 x86/x86_64 模拟器当作 native runtime 的兼容性证明；优先使用 ARM64 真机验证。
- ROOT TUN 与 ROOT TPROXY 需要设备具备可用 Root 环境。ROOT TPROXY 还要求内核提供 `xt_TPROXY`/`xt_socket` 能力；不满足时，ROOT TPROXY 模式可能无法启动，热点代理的 TPROXY 路径则可能降级。
- ROOT 热点规则依赖真实设备上的接口名称、iptables/ip6tables、策略路由与 ROM 行为；不同厂商 ROM、内核和 Root 管理器不能仅凭编译结果推断兼容。
- Tailscale、WebDAV、age 解密与三种代理模式都需要在目标设备和目标网络上分别验证；CI 构建通过不等于这些运行时路径已通过验收。
- 当前仓库没有在 README 中维护完整的机型/ROM 兼容矩阵。TODO：待真实设备验收结果确定后，再补充设备、ROM、Root 方案与验证日期；不要把单台设备结果扩大为全平台承诺。

## 致谢

- [mihomo](https://github.com/MetaCubeX/mihomo) —— 代理核心
- [miuix](https://github.com/miuix-kotlin-multiplatform/miuix) —— UI 组件库
- [scripta](https://github.com/YuKongA/scripta) —— 配置编辑器


## 许可证

本项目以 [GPL-3.0](LICENSE) 许可证开源；内核 [mihomo](https://github.com/MetaCubeX/mihomo) 也遵循 GPL-3.0。
