# MailM3U8 TV

一款跑在 Android TV / 机顶盒上的极简影片播放器。开发者通过邮件投递 JSON，应用自动拉取合并到本地列表，遥控器选片即播。

> **解决的问题**：电视盒子上装流媒体应用太麻烦 / 很多片源找不到。把 m3u8 链接塞进邮件，盒子自动收片、列表、播放。

## 特性

- 📬 **IMAP 收片**：定期拉取指定邮箱中 `m3u8_view` 前缀主题的邮件
- 📄 **两种投递格式**：正文 JSON（+ `**********` 分隔符）或 `.json` 附件，应用自动识别
- 🧩 **幂等合并**：按 `title + episode` 唯一键去重，重复投递只覆盖 url/元信息，不乱序
- 🎬 **TV 端单页 UI**：表头表体对齐、遥控器焦点导航、菜单键一键刷新
- 🔒 **低依赖**：minSdk 21，ExoPlayer 2.18.5，小米盒子 5（Android TV 5）验证通过

## 快速开始

### 环境

- JDK 17
- Android SDK（compileSdk 34）
- Gradle 8.9（项目带 wrapper）
- 一台 Android TV 盒子 / 模拟器（minSdk 21）

### 配置

**1. 创建配置文件**

项目里只有 `app/src/main/assets/config.example.json`（模板）。需要复制一份命名为 `config.json`，填入你的真实凭据：

```bash
cp app/src/main/assets/config.example.json app/src/main/assets/config.json
# 然后编辑 config.json
```

```jsonc
{
  "mail": {
    "host": "imap.163.com",
    "port": 993,
    "user": "you@163.com",
    "auth_code": "YOUR_163_AUTH_CODE_HERE",   // ← 不是登录密码！是邮箱设置里生成的「客户端授权码」
    "subject_prefix": "m3u8_view",              // ← 应用只拉取主题以此开头的邮件
    "folder": "INBOX"
  },
  "list_columns": ["title", "country", "type", "year", "director"]
}
```

首次启动应用会把 `assets/config.json` 复制到应用私有目录。**已有文件不会被覆盖**——所以你可以部署后直接用 adb 改配置，不用重装。

**2. 163 授权码怎么拿**

163 网页端登录 → 设置 → POP3/SMTP/IMAP → 开启 IMAP → 生成「客户端授权码」。把这个填到 `auth_code` 字段。**登录邮箱密码不能用**。

### 构建

```powershell
cd TV_android_mail-m3u8-view
.\gradlew.bat :app:assembleDebug
# 输出: app\build\outputs\apk\debug\app-debug.apk
```

### 安装到小米盒子

1. 盒子：设置 → 关于 → 连续点「版本号」7 次开启开发者模式
2. 盒子：设置 → 开发者选项 → 打开「网络调试」
3. 记下盒子 IP（如 `192.168.1.123`）
4. 电脑：
   ```powershell
   adb connect 192.168.1.123:5555
   adb install -r .\app\build\outputs\apk\debug\app-debug.apk
   ```
5. 盒子上启动应用，按遥控器「菜单 ☰」键刷新

### 投递影片

一封邮件 = 一批影片 JSON。**邮件主题必须以 `m3u8_view` 开头**。

**方式 A：正文 JSON（推荐）**

```json
[
  {
    "title": "启示录",
    "year": 2006,
    "country": "美国",
    "type": "动作",
    "director": "梅尔吉普森",
    "url": "https://example.com/play/index.m3u8"
  },
  {
    "title": "流浪地球3",
    "episode": 1,
    "year": 2027,
    "country": "中国",
    "type": "科幻",
    "director": "郭帆",
    "actors": "吴京 / 刘德华",
    "url": "https://cdn.example.com/vod/ep01/index.m3u8",
    "headers": { "Referer": "https://example.com/" }
  }
]
**********
```

末尾 `**********` 是分隔符——应用取它之前的内容就是 JSON。**没有分隔符也能跑**（应用会 fallback 找 `[...]` 或 `{...}` JSON 块），但分隔符能避免邮件里有额外文字时解析出错。

**方式 B：`.json` 附件**

邮件带一个 `.json` 后缀的附件（Content-Type: `application/json`），附件内容就是上面的 JSON 数组。

**两种方式可以混合**，应用优先取附件，没有附件时从正文解析。

### JSON 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `title` | **是** | 片名，不能空/空白 |
| `url` | **是** | m3u8 地址，http/https |
| `episode` | 否 | 集号（整数）。电影不填或填 0 |
| `year` / `country` / `type` / `director` / `actors` | 否 | 元信息 |
| `headers` | 否 | 防盗链请求头（如 `Referer` / `User-Agent`） |

## 工作原理

```
[按菜单键 / 启动]
    │
    ├── IMAP 单连接 SSLSocket（自写，300 行，零连接池零反射）
    │   LOGIN → ID → SELECT INBOX → SEARCH ALL → FETCH RFC822
    │   （READ_ONLY，不标记已读，完全幂等）
    │
    ├── 强制 ByteArrayDataSource + MimeMultipart 解析 MIME
    │   附件优先 > 分隔符切分 > 直接 parse > 正则 fallback
    │
    ├── LibraryStore.merge：title+episode 匹配
    │   命中 → 覆盖业务字段，保留原编号和入库时间
    │   未命中 → 新编号 max+1，插入数组头部
    │
    └── ExoPlayer 2.18.5 播放 m3u8
```

## 踩过的坑

| 坑 | 根因 | 解决 |
|---|---|---|
| 163 邮箱 `Unsafe Login` | 163 风控要求 IMAP `ID` 命令，LOGIN 后 EXAMINE 前必须发 | 自写单连接 SSLSocket，LOGIN → ID → SELECT 顺序发送 |
| JavaMail 连接池 | store 连接和 folder 连接是两条独立物理 socket，反射发的 ID 到不了 EXAMINE 那条 | 完全抛弃 JavaMail Store/Folder，改用自写单连接 FETCH RFC822 |
| `msg.content` 返回 MIME raw 文本 | 某些 multipart 邮件 JavaMail 不返回 Multipart 对象而是 String | 强制 `ByteArrayDataSource + MimeMultipart` 构造 |
| Android Button 尺寸缩不下来 | Button 类有默认 `minWidth=48dp` | 显式设 `android:minWidth="0dp" android:minHeight="0dp"` |
| 表头表体列错位 | 两边各自定义列宽、末尾按钮没留占位 | 列宽统一到 `VideoAdapter.buildColumnLayoutParams()` + 表头末尾加精确等宽占位 |

## 项目结构

```
TV_android_mail-m3u8-view/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   └── config.example.json     ← 配置模板（git 追踪）
│   ├── java/com/tv/mailvod/
│   │   ├── App.kt
│   │   ├── config/     Config.kt + ConfigLoader.kt
│   │   ├── store/      VideoItem.kt + LibraryStore.kt
│   │   ├── mail/       MailFetcher.kt  ← 自写 IMAP 客户端
│   │   └── ui/         ListActivity.kt + VideoAdapter.kt + PlayerActivity.kt
│   └── res/
│       ├── layout/     activity_list.xml + item_video.xml + activity_player.xml
│       ├── drawable/   row / btn selector 背景
│       └── values/     colors + strings + themes
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── .gitignore
```

## 依赖

- Kotlin 1.9.22 + kotlinx.serialization-json 1.6.2
- ExoPlayer 2.18.5（tv 低版本兼容）
- OkHttp 4.9.3
- android-mail 1.6.7（只用它的 MimeMessage 解析 MIME）

minSdk 21 / targetSdk 34 / compileSdk 34

## 本地开发工具（不包含在仓库里）

仓库刻意不包含 `_tmp/`（Python 发邮件脚本、IMAP 测试脚本、maven 依赖下载器）——这些脚本里有真实邮箱授权码。如果需要在本地跑通发邮件测试，手动创建 `_tmp/library.json` 和 `_tmp/send_body_mail.py`，从 `config.json` 读凭据即可。

## License

MIT
