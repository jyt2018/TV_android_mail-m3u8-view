# MailM3U8（松松看片）

一款跑在 Android TV / 机顶盒与 Android 手机上的极简影片播放器。片源以 JSON 清单放在 Gitee 仓库，应用自动拉取合并到本地列表，选片即播。单模块双 flavor：**TV 版**（遥控器交互）与**手机版**（触屏交互）共用同一套收片 / 下载 / 播放核心。

> **解决的问题**：电视盒子上装流媒体应用太麻烦 / 很多片源找不到。把 m3u8 链接放进 Gitee 片源清单，设备自动收片、列表、播放。

## 特性

- 📬 **Gitee 收片**：从片源清单（Gitee 仓库 `library.json`）一键拉取，零配置安装即用，无任何账号与凭据
- 🧩 **幂等合并**：按 `title + episode` 唯一键去重，清单更新只覆盖 url / 元信息，不乱序
- 🎬 **双端 UI**：TV 遥控器焦点导航（菜单键一键刷新、播放页 OK 直接切换播放/暂停、返回二次确认防误触）；手机触屏卡片列表（点标题弹关于、删除键与片名同行）
- ⏯️ **断点续播**：播放进度每 10 秒落盘，下次自动定位并提示；看完（≥98%）自动清除
- ⤓ **先下后播**：m3u8 解析 → 分片下载（断点续传）→ AES-128 解密 → 拼接 TS；本地播放失败自动切在线兜底
- 🔄 **Gitee 自动更新**：TV / 手机双通道共用一个发布仓库，应用内检查 → 下载 → MD5 校验 → 弹窗安装
- 🛡️ **TLS 兼容**：打包 ISRG Root X1 证书，老安卓（≤7.1.1）也能播放 Let's Encrypt 签发的源
- 🔒 **低依赖**：minSdk 21，ExoPlayer 2.18.5，小米盒子 5（Android TV 5）验证通过
- 📜 **历史**：0.7.4 及以前经 IMAP 邮件收片（自写单连接 IMAP 客户端），0.7.5 起移除，邮件方案的踩坑记录保留在 design.md

## 快速开始

### 环境

- JDK 17
- Android SDK（compileSdk 34）
- Gradle 8.9（若项目内 wrapper 不可用，直接用本地安装的 Gradle 8.9）
- 一台 Android TV 盒子 / Android 手机（minSdk 21）

### 构建

```powershell
gradle assembleTvDebug      # TV 版  → app\build\outputs\apk\tv\debug\app-tv-debug.apk
gradle assemblePhoneDebug   # 手机版 → app\build\outputs\apk\phone\debug\app-phone-debug.apk
```

版本号（versionCode / versionName）分别定义在 `app/build.gradle.kts` 的 `productFlavors` 内，两端独立演进。

### 安装与使用

```powershell
adb connect 192.168.1.123:5555        # 盒子（设置→开发者选项→网络调试）
adb install -r .\app\build\outputs\apk\tv\debug\app-tv-debug.apk
```

1. 启动即用——片源清单地址已内置默认值（Gitee 公开仓库，无需登录、无凭据）
2. 按遥控器「菜单 ☰」键（或刷新按钮）从片源清单拉取影片
3. 「设置 ⚙」按钮可改片源清单地址（默认值预填）

已装用户后续更新走应用内**自动更新**（Gitee），无需 adb。

### 自动更新（Gitee 双通道）

TV 与手机共用发布仓库 `unixsam/mailvod-release` 与同一份 `version.json`：

```jsonc
{
  "versionCode": 40, "versionName": "0.7.6", "apk": "…/app-tv-debug.apk", "md5": "…",
  "phone": { "versionCode": 5, "versionName": "0.1.4", "apk": "…/app-phone-debug.apk", "md5": "…" }
}
```

- 顶层 = TV 段（兼容旧包），`"phone"` 子对象 = 手机版
- 应用按 channel 取段：远端 versionCode 更大 → 下载 APK → MD5 校验 → 弹窗确认 → 系统安装器安装
- 发布新版本：改对应 flavor 版本号 → 构建对应 APK → `py _tmp/publish_gitee.py --flavor tv|phone`（脚本自动合并另一端段落，先删同名附件再上传）

### 片源清单维护

片源 = Gitee 仓库 `unixsam/mailvod-release` 的 `library.json`（JSON 数组，每片一个对象）。三种维护方式：

1. **Gitee 网页端直接编辑**——改完等分钟级 CDN 缓存过期再刷新
2. `py _tmp/push_library.py`——自动从电视端现有片库转换并上传
3. 本地编辑 JSON 后 `py _tmp/push_library.py --src 文件.json` 上传

### 单条字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `title` | **是** | 片名，不能空/空白 |
| `url` | **是** | m3u8 地址，http/https |
| `episode` | 否 | 集号（整数）。电影不填或填 0 |
| `year` / `country` / `type` / `director` / `actors` | 否 | 元信息 |
| `headers` | 否 | 防盗链请求头（如 `Referer` / `User-Agent`） |

## 工作原理

```
[启动 / 刷新 / 菜单键]
    │
    ├── LibrarySync: HTTPS GET 片源清单(library_url, 默认 Gitee raw 直链)
    │   JSON 数组解析(title/url 校验, 杂文包裹时正则回退)
    │
    ├── LibraryStore.merge：title+episode 匹配
    │   命中 → 覆盖业务字段，保留原编号和入库时间
    │   未命中 → 新编号 max+1，插入数组头部
    │
    ├── ExoPlayer 2.18.5 播放 m3u8（防抖 headers 注入）
    │   断点续播：进度 10s 落盘，起播前定位；本地文件损坏自动切在线兜底
    │
    └── Gitee 自动更新：检查 version.json → 下载 APK → MD5 校验 → 弹窗安装
```

## 踩过的坑

前四行是邮件收片时代（0.7.4 及以前）的留底，现方案已不涉及：

| 坑 | 根因 | 解决 |
|---|---|---|
| 163 邮箱 `Unsafe Login` | 163 风控要求 IMAP `ID` 命令，LOGIN 后 EXAMINE 前必须发 | 自写单连接 SSLSocket，LOGIN → ID → SELECT 顺序发送 |
| JavaMail 连接池 | store 连接和 folder 连接是两条独立物理 socket，反射发的 ID 到不了 EXAMINE 那条 | 完全抛弃 JavaMail Store/Folder，改用自写单连接 FETCH RFC822 |
| `msg.content` 返回 MIME raw 文本 | 某些 multipart 邮件 JavaMail 不返回 Multipart 对象而是 String | 强制 `ByteArrayDataSource + MimeMultipart` 构造 |
| 163 网页端邮件乱码 | 正文为 charset=gbk + base64，按 UTF-8 强解导致乱码 | 字节级按声明 charset → UTF-8 → GBK 严格解码逐级 fallback |
| 断点提示续播却从 0 开始 | ExoPlayer `setMediaItem/setMediaSource` 默认 resetPosition，先 seekTo 再设媒体源会丢位置 | 顺序改为：设媒体源 → seekTo(断点) → prepare() |
| 首装拉取 0 条 | 配置默认值 `subject_prefix` 与实际投递主题不符，客户端前缀过滤全跳过 | 默认值统一为 `m3u8_view`，应用内帮助文案同步 |
| Android Button 尺寸缩不下来 | Button 类有默认 `minWidth=48dp` | 显式设 `android:minWidth="0dp" android:minHeight="0dp"` |
| 表头表体列错位 | 两边各自定义列宽、末尾按钮没留占位 | 列宽统一到 `VideoAdapter.buildColumnLayoutParams()` + 表头末尾加精确等宽占位 |
| Gitee Contents API 新建文件 400 | 新建文件用 PUT 且不带 sha——Gitee 新建必须 POST，更新才 PUT+sha | `push_library.py` 按远端存在性选 POST/PUT |

## 项目结构

```
TV_android_mail-m3u8-view/
├── app/src/
│   ├── main/                          共用（双版本打包同一份）
│   │   ├── java/com/tv/mailvod/
│   │   │   ├── net/        LibrarySync.kt        Gitee 片源清单拉取与解析
│   │   │   │               UpdateChecker.kt      双通道版本清单
│   │   │   │               AppUpdater.kt         更新检查/下载/安装弹窗
│   │   │   │               TlsCompat.kt          ISRG 根证书兼容
│   │   │   ├── download/   M3u8Downloader.kt     分片下载/AES-128/TS 拼接
│   │   │   │               MovieFiles.kt         本地文件管理
│   │   │   ├── playback/   VodPlayer.kt          ExoPlayer 核心(HLS/续播/兜底)
│   │   │   ├── store/      LibraryStore / ProgressStore / VideoItem
│   │   │   ├── config/     Config(library_url) / ConfigLoader
│   │   │   └── App.kt
│   │   ├── assets/certs/               ISRG Root X1/X2 证书（TLS 兼容）
│   │   ├── assets/config.example.json  配置模板（仅参考，零配置也可用）
│   │   └── AndroidManifest.xml         权限 + FileProvider
│   ├── tv/                            TV 版专属：遥控器 UI / Leanback 主题 / banner
│   │   └── AndroidManifest.xml        LEANBACK_LAUNCHER + REQUEST_INSTALL_PACKAGES
│   └── phone/                         手机版专属：触屏 UI / AppCompat 主题 / 横屏播放
│       └── AndroidManifest.xml        LAUNCHER + REQUEST_INSTALL_PACKAGES
├── build.gradle.kts                   AGP 8.5.2 / Kotlin 2.0.20 / productFlavors
├── design.md                          设计文档（协议/存储/UI/踩坑 全记录）
└── README.md
```

## 依赖

- Kotlin 2.0.20 + kotlinx.serialization-json 1.5.1 + Coroutines 1.6.4
- ExoPlayer 2.18.5（旧包名 com.google.android.exoplayer，低版本兼容）
- OkHttp 4.9.3
- androidx：core-ktx 1.9.0 / appcompat 1.6.1 / recyclerview 1.3.2 / lifecycle 2.5.1 / leanback 1.0.0（仅 TV）

minSdk 21 / targetSdk 34 / compileSdk 34

## 本地开发工具（不包含在仓库里）

仓库刻意不包含 `_tmp/`（发布脚本 `publish_gitee.py`、片源上传 `push_library.py`、构建计划任务脚本等）——这些脚本里有真实令牌与凭据。发布流程见上文「自动更新」与「片源清单维护」。

## License

MIT
