# PCAPdroid MCP v2.1 · 诗情魔改版

> **这是一个魔改版 PCAPdroid**：在开源 [PCAPdroid](https://github.com/emanuele-f/PCAPdroid)（GPL-3.0）基础上，**内置了一个轻量 MCP（Model Context Protocol）服务器**，让 AI 客户端（例如 Claude Desktop）能在电脑上直接读取手机端实时抓到的网络连接数据。本项目由 **AI 辅助编写**，主要用于学习与二次开发。

---

## 这是什么？它做什么？

- **PCAPdroid** 是一个隐私友好的安卓网络抓包 App：通过模拟本地 VPN 抓取手机里其它 App 的流量，可导出 pcap、查看 HTTP 请求、解密 TLS 等，**无需 root**。
- **本魔改版**在它基础上新增了：
  - 一个**内嵌的 MCP 服务器**（纯 Java 标准库实现），暴露 SSE 端点，供 AI 客户端连接并读取实时连接。
  - **前台保活服务**：MCP 开关打开时让 App 在后台 / Doze 下不被系统杀掉，保证 MCP 一直可达。
  - 应用名改为 **`PCAPdroid MCP v2.1-诗情`**（主包与 debug 包都改）。
  - 收窄侧边栏：删掉「用户指南 / 捐赠 / 分享 / Telegram 群组」，只保留「关于」。
  - 设置页新增「**MCP 连接地址**」：只读、点按即可复制，形如 `http://<手机IP>:8080/sse`。
  - **root 捕获开关强制显示**（不再因检测不到 su 而隐藏），并放宽了 root 检测（用 `File.exists()` 检查常见 su/Magisk 路径，不拉起进程）。
  - 版本号 `versionCode 96 / versionName 2.1`。

> MCP 连接示例：`http://<电脑或手机的LAN IP>:8080/sse`（默认端口 8080，可在设置里改）。需要手机和电脑处于同一 WiFi。

---

## 我加了什么？加在哪里？（AI 编写）

| 功能 | 改动 | 对应文件 |
|------|------|----------|
| 内置 MCP 服务器 | 自实现 HTTP + SSE 服务器，路由 `/health`、`/sse`、`/messages`，并提供 `list-recent-connections`、`get-connection-payload` 两个工具 | `app/src/main/java/com/emanuelef/remote_capture/mcp/McpHttpServer.java` |
| MCP 保活前台服务 | MCP 开启时启动前台服务（`specialUse`），持有 `PARTIAL_WAKE_LOCK`，后台/Doze 不被杀；MCP 关闭即停 | `app/src/main/java/com/emanuelef/remote_capture/McpKeepAliveService.java` |
| 清单权限 / 服务注册 | 增加 `WAKE_LOCK` 权限 + `McpKeepAliveService` 声明 | `app/src/main/AndroidManifest.xml` |
| 收窄侧边栏 | 删掉 用户指南/捐赠/分享/Telegram，只留「关于」 | `app/src/main/res/menu/nav_items.xml` |
| 侧边栏点击分支 | 删掉对应的 `action_donate` / `action_open_telegram` / `action_open_user_guide` / `action_share_app` 分支 | `app/src/main/java/com/emanuelef/remote_capture/activities/MainActivity.java` |
| 只读可复制的 MCP 地址 | 新增 preference 项 + `PREF_MCP_ADDRESS` 常量 + 点击复制逻辑；`updateMcpEndpoint()` 同时刷新端口与地址的 summary | `app/src/main/res/xml/root_preferences.xml`、`.../model/Prefs.java`、`.../activities/prefs/SettingsActivity.java`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh-rCN/strings.xml` |
| root 捕获开关强制显示 | 删掉 `else mRootCaptureEnabled.setVisible(false);`，保留 `if(Utils.isRootAvailable())` 里注册的监听器（`rootCaptureHideShow` + `checkDecrpytionWithRoot`） | `app/src/main/java/com/emanuelef/remote_capture/activities/prefs/SettingsActivity.java` |
| 放宽 root 检测 | 新增 `ROOT_CHECK_PATHS`，用 `File.exists()` 检查常见 su/Magisk 路径，不开子进程 | `app/src/main/java/com/emanuelef/remote_capture/Utils.java` |
| 版本号 | `versionCode 96` / `versionName "2.1"` | `app/build.gradle` |

---

## 如何构建（打包 APK）

环境要求：**JDK 17 + Android SDK**（用 Android Studio 一键装好即可），并用 **git 拉取子模块**（本仓库用 git 管理 6 个子模块）。

```powershell
# 1) 进仓库目录（把路径换成你自己的）
cd 你的PCAPdroid路径

# 2) 拉取子模块（clone 下来第一次才需要）
git submodule update --init --recursive

# 3) 编译标准版（带 UShark 增强）debug 包
gradlew.bat :app:assembleStandardDebug

# 4) 编译不带 UShark 的版本
gradlew.bat :app:assembleWithoutUsharkDebug
```

编译成功后 APK 在：`app\build\outputs\apk\<flavor>\debug\app-<flavor>-debug.apk`

说明：
- `debug` 版：**自动签名、不做混淆**（源码没被打乱，方便学习/二次开发）。
- 两个 flavor：`standard`（带 UShark 增强）、`withoutUshark`（不带，更小）。
- 想发布正式版请自行配置签名（本仓库以 debug 为主）。

---

## 如何修改 / 把某个改动「拿出来」

1. 每个改动都是**独立的小改动**，都集中在上表列出的文件里。改完重新 `gradlew.bat` 编译，看到 `BUILD SUCCESSFUL` 即可。
2. 想单独拿走「MCP 服务器」：复制以下内容 + 清单里的权限声明即可——
   - `McpHttpServer.java`（服务器本体，纯标准库实现）
   - `McpKeepAliveService.java`（保活前台服务）
   - `MainActivity.java` 里 `startMcpServer()` / `stopMcpServer()` 相关方法
   - `AndroidManifest.xml` 里的 `WAKE_LOCK` 权限 + `<service>` 声明
3. 想单独拿走「root 捕获开关常驻」：改 `SettingsActivity.java` 那一处 `if(Utils.isRootAvailable()) { ... }`，并保留 `Utils.java` 里放宽后的 root 检测。
4. 关键细节都在代码里写了中文注释，搜索关键词：`MCP`、`McpHttpServer`、`McpKeepAliveService`、`root_capture`、`pcapdroid_app_name`。

---

## 如何给 AI 客户端配置 MCP

1. 手机装上 APK，打开**设置**，找到 **MCP** 开关并打开。
2. 记下「MCP 连接地址」（点一下就复制），形如 `http://<手机IP>:8080/sse`。
3. 在 AI 客户端（如 Claude Desktop）的 MCP 配置里填入这个地址。
4. 保持手机和电脑同一 WiFi，App 别被清理。

> 打开 MCP 后通知栏会有一条「MCP server」的前台通知，这是**保活的正常现象**，别当成广告关掉（关掉后台进程会被杀）。

---

## 许可（License）

本项目基于 [PCAPdroid](https://github.com/emanuele-f/PCAPdroid)（**GPL-3.0**）派生，因此**按 GPL-3.0 授权**（不能改成 MIT 等更宽松的协议，否则侵权）。完整文本见 `LICENSE` / `COPYING`。

- 你可以使用、修改、再分发，但**衍生项目必须同样以 GPL-3.0 开源**。
- 商用 / 发布请遵守 GPL-3.0，并保留版权声明与来源。

---

## 免责声明（重要）

- 本仓库由 **AI 辅助编写**，主要目标是**学习与二次开发**，未做大规模真机极端场景测试。
- 抓包 / TLS 解密 / 查看他人流量可能**违反当地法律或服务条款**，请仅对**自有设备、合规场景**使用。
- 作者（及 AI）不对因使用本软件造成的任何损失 / 法律后果负责，**风险自负**。

---

## 致谢

- 感谢 [emanuele-f/PCAPdroid](https://github.com/emanuele-f/PCAPdroid) 及其它开源子模块作者。

---

### English summary (short)

`PCAPdroid MCP v2.1 · 诗情` is an **AI-assisted fork** of [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) (GPL-3.0). It embeds a minimal **MCP server** (`/sse`, `/health`, `/messages`, tool `list-recent-connections` and `get-connection-payload`) so AI clients can read the phone's live captured connections over the local network. Other tweaks: renamed app, a keep-alive foreground service, a read-only tap-to-copy MCP address in Settings, a slimmer sidebar (guide/donate/share/Telegram removed), a "force-show" root-capture switch, a more lenient root check, and a version bump to `2.1` (`versionCode 96`). Built as `debug` (auto-signed, no obfuscation). For learning use only; use it responsibly and at your own risk.

