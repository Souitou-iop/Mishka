# Changelog

本文件只记录当前仓库历史中可以由提交或现有代码核实的变更。应用版本仍以构建生成的 `BuildConfig.VERSION_NAME` 与 `BuildConfig.VERSION_CODE` 为准。

## 1.1.2 — 2026-10-01

- `backup: keep per-device Tailscale hostname out of backups` (`597856b`)
  - Tailscale 主机名是每台设备自己的身份，加入备份黑名单：导出不携带，恢复不覆盖目标设备已设的名字。
  - 恢复后主机名留空时，Tailscale 侧回落到 Android 设备名。

## 1.1.1 — 2026-10-01

- `backup: include Tailscale auth key in backup and restore` (`5b0ef0f`)
  - 备份快照新增 `tailscaleAuthKey` 专用字段，auth key 明文随备份携带（Keystore 密钥硬件绑定，密文跨设备不可解，敏感级同三表中的 ageSecretKey）。
  - 恢复经 `putSecret` 回写 SecretStore，无需手动重新粘贴；目标机 Keystore 不可用时跳过本项并记日志。
  - 旧版本恢复新备份会忽略该字段，新版本恢复旧备份按未设置处理，`BACKUP_VERSION` 保持不变。

## 1.1.0 — 2026-10-01

- `settings: Add Tailscale Tailnet outbound and device status` (`311e687`)
  - 增加 Tailscale 出站配置与 Tailnet 设备状态页面。
  - 通过生成的 JavaScript transform 注入 `Mishka Tailscale` 出站和 Tailnet 网段规则。
  - 增加 mihomo `/tailscale/status` API 对接，展示当前设备、在线设备与最近活动时间。
- `subscription: Add YAML and JavaScript overrides` (`42c924d`)
  - 增加订阅级 YAML / JavaScript override 的创建、编辑、远程更新、选择与排序。
  - 将 override 选择写入备份数据，并在 mihomo 启动时通过 transform 应用。
  - 增加每个订阅的 age secret key 透传、密钥生成与运行时解密路径。
- `subscription: stream backups instead of holding the whole archive in memory` (`a5b7615`)
  - 备份与恢复改为流式处理，避免把完整压缩包和解压内容同时放入内存。
- `backup: replay the restored tables in one transaction under profileLock` (`76c2575`)
  - 恢复数据库表时使用 profile lock 与单事务重放，减少文件与数据库状态不一致窗口。
- `backup: stage restore files before touching the live ones` (`28c6beb`)
  - 恢复先写入 staging，再通过 rename 换入正式目录，失败时保留回滚路径。
- `fix(backup): follow WebDAV redirects manually and use trailing-slash collection URL` (`42086f4`)
  - WebDAV 请求手动跟随同主机重定向，并保持方法、请求体与凭据安全边界。
- `feat(backup): add WebDAV and local file backup & restore` (`5b4eed3`)
  - 增加本地文件与 WebDAV 备份/恢复入口及固定归档格式。
- `perf: generate and ship a baseline profile` (`eab16cb`)
  - 增加 Baseline Profile 采集路径与 release 产物目录。
- `build: always refetch the geo data when the task is asked to run` (`adb014c`)
  - `downloadGeoFiles` 被显式调用时不依赖旧的 up-to-date 判断，以应对上游 latest 资源原地更新。
- `build: make the geo download fail loudly instead of quietly` (`2aea94f`)
  - GeoIP 下载增加 HTTP 状态、体积与临时文件校验，避免错误响应被当作有效资源打包。

- `cli: expose the Mishka control surface over ADB`
  - 增加受 `DUMP` 权限和调用 UID 校验保护的 ContentProvider/Activity CLI bridge。
  - 覆盖代理、订阅、override、设置、启动项、Wi-Fi、备份、运行时与诊断命令。
  - CLI 复用 GUI 的 controller、repository、profile processor 与 runtime pipeline。
- `backup: use immutable WebDAV snapshots`
  - 新版备份写入 `Mishka/snapshots/`，不再覆盖原版的 `Mishka/mishka-backup.zip`。
  - 支持快照版本查询、最高版本恢复、保留最近历史，以及显式导出原版兼容备份。
  - 本地同步版本不会进入备份内容；远端存在更新快照时普通上传会停止并要求先恢复。
- `diagnostics: add configuration preview and validation`
  - 增加配置预览、订阅 transform、Tailscale 与运行时 override 诊断。
- `service: harden foreground start and runtime state transitions`
  - 统一启动校验、VPN 授权、启动幂等、错误状态与 ROOT attach/restart 路径。
- `ui: add shortcuts, widget and diagnostics entry points`
  - 增加快捷方式、桌面小组件、诊断入口与相关本地化。
- `build: sign CI artifacts with the pinned debug keystore` (`5d02a25`)
  - build.yml 从 secrets 解码 keystore 注入 buildTypes 签名机制，debug/release 产物签名统一，CI 产物可直接覆盖安装到现役设备。
- `docs: add app icon PNG as README header` (`6f34e6a`)
  - README 头部展示应用图标 PNG（按 adaptive icon 矢量坐标重绘导出）。
- `build: add tag-triggered signed release workflow` (`8f93769`)
  - 推送 `v*` tag 触发 release.yml：解码 keystore 签名、apksigner 验签后自动发布 GitHub Release。
- `ui: bump miuix to 0.9.4` (`90beb68`)
- `build: bump some deps` (`94573b0`)
- `chore: bump mihomo to v1.19.31+` (`4e619c0`)

### 验证边界

- JVM 单元测试、Debug Kotlin 编译和 Tailscale 构建通过。
- CLI + Tailscale 版曾在一加真机验证运行时控制面、代理启动/停止/重启、runtime REST/WS 与快照逻辑。
- 本次新版 WebDAV 真实 Android 上传未验收：目标服务器证书链在 Android 端返回 `CertPathValidatorException`；未绕过 TLS 校验，也未声称远端上传成功。

## 说明

- 本 changelog 不把 ROOT TPROXY、age 加密订阅或其他当前代码能力伪装成某个无法核实的单一“新增提交”；README 的功能说明以当前树中的实现为准。
- 当前工作区存在的实验分支、未合入改动和未验证的真实设备行为不应写入本文件。
