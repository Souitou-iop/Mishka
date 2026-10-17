# 复盘：三台设备 Tailscale 全部变成 ace-mishka（Duplicate node key）

- 日期：2026-10-01
- 涉及版本：备份快照由 v1.1.0 生成（11:23），恢复到 3 台设备
- 影响：多台设备共享同一个 Tailscale 节点身份；控制台报 "Duplicate node key"

## TL;DR

**WebDAV 备份把 `tailscaled.state`（Tailscale 节点身份文件）一起打包了。** 该文件位于订阅工作目录
`imported/{uuid}/tailscale/` 之下，而备份会把 `files/imported/` 整棵目录树打进 zip。同一份快照恢复
到 N 台设备，N 台设备就持有同一个 machine key / node key——控制服务器看到同一节点身份来自多台机器，
报 "Duplicate node key"，节点名显示为原设备的 ace-mishka。

这不是主机名的问题（v1.1.2 已修的主机名同步是另一层），也不是 auth key 备份功能的问题
（本快照生成于该功能发布之前，backup.json 里没有 auth key）——是第三个独立缺口：**运行时身份状态
混进了本应只含「配置 + 订阅文件」的备份范围**。

## 根因链（每步有实证）

1. **状态目录落在 profile 工作目录内**。mihomo tailscale 出站的 `state-dir` 未设置时默认
   `"tailscale"`，按 home path 解析（`mihomo/adapter/outbound/tailscale.go:120-123`）。Mishka
   生成的 JS transform（`ProfileTransformWriter.buildTailscaleScript`）没有设置 `state-dir` 字段，
   于是 VPN 模式（工作目录 = `files/mihomo/imported/{uuid}/`）下，节点身份写到
   `imported/{uuid}/tailscale/tailscaled.state`。

2. **备份整棵打包 `imported/`**。`BackupManager.zipDirIfExists` 只排除符号链接与
   `GEODATA_FILES` 名单，其余文件全部进 zip。服务器实拍快照条目（`unzip -l`）：

   ```
   files/imported/177ba4ad-…/tailscale/tailscaled.state        2414 B
   files/imported/177ba4ad-…/tailscale/tailscaled.log.conf
   files/imported/177ba4ad-…/tailscale/profile-data/7e03/netmap-cache/…
   ```

   `tailscaled.state` 内含 machine key、node key 与控制服务器分配的节点身份（含节点名
   Ace-Mishka）。

3. **同一快照恢复到 3 台设备**。恢复管线原样换入 `imported/` 目录树 → 三台设备磁盘上出现
   完全相同的节点身份。

4. **控制服务器判定重复**。多台机器以同一 node key 连接 → admin 控制台标 "Duplicate node
   key"；由于身份里带着原设备注册时的节点名，三台都显示 ace-mishka 而不是各自的设备名。

## 为什么 v1.1.2 没防住

v1.1.2 把 `tailscale_hostname` 拉进备份黑名单，解决的是 **prefs 层**的「名字跨设备漂移」。
本次事故的载体在 **文件树层**（`tailscaled.state`），是第三条独立通道：

| 层 | 载体 | 状态 |
|---|---|---|
| prefs | `tailscale_hostname` | v1.1.2 已拉黑 |
| prefs | `tailscale_auth_key`（专用字段明文） | v1.1.1 起（用户明示接受的取舍） |
| 文件树 | `imported/{uuid}/tailscale/tailscaled.state` | **未处理——本次事故根因** |

## 犯了什么错

1. **备份边界设计错误**：备份的语义应是「可再生的配置与订阅文件」，而 `imported/{uuid}/` 里的
   `tailscale/` 是**运行时派生的身份状态**，不该出现在任何备份里。写备份排除名单时只想到
   geodata（体积）与 symlink（实体化风险），没有审视「这棵树里还有什么」。
2. **验收只核对了一半产物**：此前对备份内容的核验只解开了 `backup.json` 看 prefs 键名，
   没有核对 zip 条目清单。状态文件就这样静默搭车，直到多设备恢复才暴露。
3. **更深的结构问题**：节点身份是 **per-profile** 的（跟着订阅工作目录走）。即使没有备份，
   切换订阅也会生成新的节点身份——每切一次 profile，Tailnet 里就多一台「新设备」。
   身份状态本来就不该是 profile 级的。

## 处置

**立即（三台设备各自恢复独立身份）**：

1. 生成**新的** Tailscale auth key（旧 key 若为一次性类型，首次注册时已消费；可开 reusable，
   或每台发一把）。三台设备恢复出来的 prefs 里 auth key 是空的，重置后必须重新填。
2. 每台设备清掉共享的节点身份：
   - debug 构建（可 run-as）：`adb shell run-as top.yukonga.mishka rm -rf files/mihomo/imported/*/tailscale`
   - 通用：`adb shell pm clear top.yukonga.mishka`（清全部数据），随后从**干净备份**重装恢复
   - 已 root：`su rm -rf /data/data/top.yukonga.mishka/files/mihomo/imported/*/tailscale`
3. 每台在 Tailscale 设置里填**各自的主机名**（或留空回落 Android 设备名——v1.1.2 起恢复不再
   覆盖它），粘贴 auth key，重新启用。三台会在控制台注册为三个独立节点。
4. admin 控制台删掉多余的 ace-mishka 节点。

**注意**：在修复版本发布前，不要再把服务器上现有快照恢复到任何新设备——它仍携带共享身份。

## 修复建议（代码，待做）

1. **短期（App 侧，一处）**：`BackupManager.zipDirIfExists` 排除工作目录内的 `tailscale/`
   子树（与 geodata 同类：可重建的运行时状态），导出与恢复双向隔绝。
2. **治本（transform 侧）**：`buildTailscaleScript` 显式生成 `state-dir`，指向 profile 工作目录
   之外（如 `files/mihomo/tailscale/`）——节点身份升为 app 级：跨订阅切换保持稳定，且天然不在
   备份范围内。取舍：身份从 per-profile 变为 per-app，删除单个订阅不再连带注销节点（更符合
   直觉）。
3. **回归防线**：备份产物验收补一条「zip 条目清单核对」（临时状态文件不得出现），并将
   「新增文件到工作目录前先问会不会被备份带走」沉淀为约束。

## 教训

- 备份 = 配置 + 源文件，**绝不包含运行时身份**。凡是「设备自我标识」的东西（节点身份、主机名、
  PID 类状态），恢复到另一台机器都是错的。
- 排除名单是黑名单思维，注定追着新成员跑；目录布局若能把「可再生状态」与「需要备份的数据」
  分开存放，才是一劳永逸的白名单边界。
- 恢复类特性的验收要对着**完整产物清单**（zip 条目级），元数据核对（backup.json 键名）不够。
