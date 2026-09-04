# MailM3U8 TV 设计文档（v0.6.3）

> TV 端「邮箱投递影片 → 合并到本地 JSON → 列表选择 → 播放 m3u8」
> 状态：已实现（收片通、UI 打磨中）
> 日期：2026-09-02

---

## 1. 目标与边界

### 1.1 核心目标
1. 启动应用 → 拉取指定邮箱邮件 → 解析 JSON → 合并到本地 `library.json`。
2. 单页列表展示，遥控器上下选行、右移到播放键、OK 即播，无详情页。
3. 播放使用 ExoPlayer 2.18.5（TV 设备低版本友好）。

### 1.2 明确不做
- 不用 Room 数据库，仅一个 JSON 数组文件 `library.json`。
- 不做后台轮询 / 开机自启：启动时拉取一次 + 列表页「刷新」键手动拉取。
- 不做详情页 / 海报 / 续播记忆 / 多集菜单。
- 不做 SMTP 回执。

---

## 2. 投递 JSON 协议

### 2.1 邮件主题

固定为 `m3u8_view`（可在 config.json 改 `mail.subject_prefix`）。

### 2.2 投递方式（两种都支持）

**方式 A：正文 JSON + 分隔符（推荐，简单）**

正文是纯 JSON 数组，最后追加 `**********` 作为分隔符（分隔符之前的内容就是 JSON）。

```json
[
  {
    "title": "启示录",
    "year": 2006,
    "country": "美国",
    "director": "梅尔吉普森",
    "type": "动作",
    "url": "https://v.gsuus.com/play/NbWVpgay/index.m3u8"
  }
]
**********
```

**方式 B：`.json` 附件（备选，最稳）**

邮件带一个 `.json` 后缀的附件（Content-Type: `application/json`），附件内容就是 JSON 数组。JavaMail 自动解码 base64/GBK/QP，直接解析附件。

**解析优先级**：附件 > 分隔符 `**********` 之前 > 直接以 `[` 或 `{` 开头 > 正则 fallback。

> 正文模式下，即使没有分隔符，只要正文是纯 JSON（以 `[` 或 `{` 开头），也能被正则 `\[...\]` 或 `\{...\}` fallback 捕获。分隔符的作用是**从可能包含额外文字的正文里精确定位 JSON 块**。

### 2.3 单条 JSON 字段

```json
{
  "title": "流浪地球3",
  "episode": 1,
  "year": 2027,
  "country": "中国",
  "type": "科幻",
  "director": "郭帆",
  "actors": "吴京 / 刘德华",
  "url": "https://cdn.example.com/vod/ep01/index.m3u8",
  "headers": { "Referer": "https://example.com/", "User-Agent": "okhttp/4.12" }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `title` | **是**（不能空/空白） | 片名 |
| `url` | **是**（不能空/空白） | m3u8 地址，http/https |
| `episode` | 否 | 集号（整数）。电影不填或填 0 |
| `year` `country` `type` `director` `actors` | 否 | 元信息 |
| `headers` | 否 | 防盗链请求头 |

**唯一键（去重覆盖）**：`title + episode`（episode 缺省按 0 处理）。
重复投递同一 key → 覆盖旧条目（更新 url 或元信息），**保留原编号和入库时间**。

---

## 3. 配置文件 config.json

位置：应用私有目录 `/data/data/<包名>/files/config.json`。
首次启动从 `assets/config.json` 复制过去。已有文件**不会**被覆盖（便于部署后手动改配置）。

```jsonc
{
  "mail": {
    "host": "imap.163.com",        // IMAP 服务器地址
    "port": 993,                   // IMAP 端口 (SSL)
    "smtp_host": "smtp.163.com",   // SMTP 服务器 (备用)
    "smtp_port": 465,              // SMTP 端口 (SSL)
    "user": "you@163.com",         // 邮箱账号 (完整地址)
    "auth_code": "你的163授权码",   // 不是登录密码,是邮箱设置里生成的客户端授权码
    "subject_prefix": "m3u8_view", // 只拉取主题以这个前缀开头的邮件
    "folder": "INBOX"              // 要扫描的 IMAP 文件夹,默认收件箱
  },
  "list_columns": ["title", "country", "type", "year", "director"],
                                   // 列表每行显示哪些字段,顺序即显示顺序
                                   // 可选值: title, country, type, year, director, actors, episode
  "player": {
    "auto_next": false             // 播放完是否自动播下一集 (暂未实现,预留)
  }
}
```

---

## 4. 本地存储 library.json

位置：应用私有目录，单一 JSON 数组：

```json
[
  { "_id": 1234, "title": "流浪地球3", "episode": 1, "year": 2027, "country": "中国", "type": "科幻", "director": "郭帆",
    "url": "https://...ep01.m3u8", "headers": {...}, "_added_at": 1788200000 },
  { "_id": 1233, "title": "流浪地球3", "episode": 2, ... }
]
```

- **`_id`**：唯一递增整数，4 位补零显示（`12` → `0012`），删除后不回收。
- **`_added_at`**：入库时间戳（秒），列表按此逆序排列（最新在上）。
- **合并策略**：拉取到的条目按 `title + episode` 唯一键（episode 缺省按 0）查找本地：
  - **命中（重复投递）**：覆盖 url / headers / type / country / director / actors / year 等业务字段，**保留原 `_id` 与 `_added_at`**（编号不变、位置不变、不重复计数）。可用于修正 url 或补全元信息。
  - **未命中（新片）**：分配新 `_id = max(existing ids) + 1`，记当前时间戳 `_added_at`，**插入数组头部**（按时间逆序排列，新片置顶）。
  - **跳过**：title 或 url 为空/空白的条目直接跳过，不写库，不计入「新增 N 条」统计。
  - **已删除条目**：如果之前在应用里删除过某条目（`_id` 从 library.json 移除），后续邮箱又投递同一 title+episode → 会被当作新片重新入库（分配新编号）。

---

## 5. UI（v0.6.4）

### 5.0 页面命名（沟通约定）

| 页面名 | 类 / 布局 | 说明 |
|---|---|---|
| **片库页** | `ListActivity` / activity_list | 主页影片列表, 打开 app 即此页 |
| **播放页** | `PlayerActivity` / activity_player | 在线 HLS 或本地 ts 播放, 头部显示片名 |
| **搜索页** | `SearchActivity` / activity_search | 界面壳(v0.6.4 设计, 逻辑未实现): 左上角 ← 返回按钮(遥控器返回键等效) + 搜索框 + 搜索按钮 + 结果列表 |
| **设置弹窗** | 片库页内 `AlertDialog` / dialog_settings | 输入邮箱账号 + 授权码, 确定后写入 config.json (v0.6.4) |
| **关于弹窗** | 片库页内 `AlertDialog` | 片库页头像图标点击触发: 版本 / 开发者 / 操作使用说明 (v0.6.4) |
| **下载弹窗** | 片库页内 `AlertDialog` / dialog_download | 先下后播的进度弹窗 (解析→下载分片 x/y→拼接 TS) |
| **删除确认弹窗** | 片库页内 `AlertDialog` | 确认文案 + 复选框"同时删除已下载内容"(默认勾选) |

### 5.1 片库页布局

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ [🐶] 松松看片 (共3) v 0.6.4  ──────  按遥控器【菜单☰】按钮  [刷新🔄] [设置⚙️] [搜索🔎] │
├────┬──────┬──────────────────────────┬──────────┬────────┬─────────┤
│已下载│ 编号 │ 标题  国家  类型  年份 导演 │ 在线播放▶ │ 先下后播⤓│ 删除 ✕  │ ← 表头
├────┼──────┼──────────────────────────┼──────────┼────────┼─────────┤
│ ✔  │ 0006 │ 守法公民   美国   剧情 2009 F·加里 │[在线播放]│[本地播放]│[删除]│
│    │ 0005 │ ...                        │[在线播放]│[先下后播]│[删除]│
└────┴──────┴──────────────────────────┴──────────┴────────┴─────────┘
  ↑64dp  ↑72dp  ↑120+70+50+160          ↑84dp     ↑84dp    ↑56dp
         title 自适应 (weight=1) + 固定列宽
```

- **标题行**（v0.6.4 重排）：左起为博美头像图标 + 标题 + 小字版本号 + 弹性空白 + 菜单提示 + 刷新/设置/搜索三按钮
  - **头像图标** `ivIcon`（48dp）：`drawable-*/ic_head.png`，由 `songsong-head.png` 抠白底生成透明背景（脚本 `_tmp/make_head_icon.py`，水印遮盖 + 四角 BFS 清近白连通区 + 边缘细化）。可聚焦，聚焦时黄框（`bg_icon_focus`：3dp #FFD700 12dp 圆角描边）；OK 点击弹**关于弹窗**（版本 v x.y.z(code) / 开发者 jyt2018 / 6 条操作说明）
  - **版本号**：紧跟标题后小字体（14sp 灰），格式 `v 0.6.4`（v 后带空格），动态读 PackageInfo
  - **三按钮等宽**（v0.6.4）：刷新🔄 / 设置⚙️ / 搜索🔎，统一 84dp 宽、文字居中、间距 12dp
  - **设置⚙️**：弹窗预填当前账号/授权码，确定后 `ConfigLoader.save()` 写 `files/config.json`（重启/刷新生效）
  - **搜索🔎**：进入搜索页（界面壳）
- **已下载列** (v0.6.1): 该条目存在本地 `编号.ts` 时显示 ✔; 已下载行的"先下后播"按钮文字变为 **"本地播放"**。
- 表头不可聚焦，纯装饰。**表头与表体列宽共用同一个 `VideoAdapter.buildColumnLayoutParams()` 函数**，天然对齐。
- 选中行有**橙色外框 + 深灰背景**（`rowRoot.isSelected=true` 触发 `bg_row_selector` 里的 `state_selected`）。按钮聚焦时 rowRoot 失去焦点但 `isSelected` 仍为 true → 黄框保留。
- 编号列 4 位补零，不可聚焦。
- 行根布局不可聚焦 → 焦点从表头按下时默认落在"在线播放"按钮上（v0.5.4）。

### 5.1.1 搜索页（v0.6.4 界面设计，逻辑未实现）

```
┌────────────────────────────────────────────────────────┐
│ [← 返回]                                                │ ← 左上角, 遥控器返回键等效
│                                                         │
│ [输入片名关键词____________________] [搜索]              │ ← 搜索行
├──────────────────────────────────────────────┬─────────┤
│ 流浪地球3                                     │ [想看]  │ ← item_search 行
│ 2027 / 科幻 / 中国                            │         │
├──────────────────────────────────────────────┼─────────┤
│ ...                                          │ [想看]  │
└──────────────────────────────────────────────┴─────────┘
```

- 返回按钮 `btnBack` → finish；系统返回键同样生效
- `etKeyword` 搜索框 + `btnSearch` 搜索按钮（点击暂提示"搜索功能开发中"）
- `rvResults` 结果列表 + `tvEmpty` 空态提示；行布局 `item_search`：标题(粗体, weight=1) + 摘要 + 行尾 **想看** 按钮(84dp)
- 搜索数据源与"想看"行为待后续实现

### 5.2 列宽策略

**单一数据源**：`VideoAdapter.buildColumnLayoutParams(key, density)` 定义每列宽度。表头 ListActivity 和表体 VideoAdapter.onCreateViewHolder **都调用这个函数**，保证每列宽度完全一致。

| 字段 | 宽度 | 说明 |
|---|---|---|
| title | weight=1 | 自适应剩余空间，超长省略号截断 |
| country | 120dp | 4 个中文字符 |
| type | 70dp | 2 个中文字符（v0.6.2 由 120 收窄） |
| year | 50dp | 4 位数字 |
| director | 160dp | 8 个中文字符，超长截断 |
| actors / episode | 160dp / 40dp | 同字段复用 |

按钮区占位（表头末尾零宽 View）：在线播放 84dp + 先下后播/本地播放 84dp + 删除 56dp + 6dp marginStart，精确匹配表体按钮 → 两边右边界一致。

### 5.3 遥控器焦点

| 操作 | 行为 |
|---|---|
| **上/下** | 列表行间移动（RecyclerView 默认） |
| **右** | 行内：在线播放 → 先下后播 → 删除；删除键再右 → 下一行（RecyclerView 默认） |
| **左** | 行内：删除键 → 先下后播 → 在线播放；在线播放左 → 上一行（RecyclerView 默认） |
| **OK** | 焦点在在线播放键 → 播放页(HLS); 先下后播/本地播放 → 下载弹窗或本地播放; 删除键 → 删除确认弹窗; 刷新键 → 重新拉取 |
| **菜单键** | 全局监听 `KeyEvent.KEYCODE_MENU`（=82），等价于点刷新按钮 |
| **返回** | 播放页返回片库页（停止播放） |

**方向键强制路由**：代码中显式设置了 `btnPlay.nextFocusRightId = btnDownloadPlay`、`btnDownloadPlay.nextFocusLeftId = btnPlay`、`btnDownloadPlay.nextFocusRightId = btnDelete`、`btnDelete.nextFocusLeftId = btnDownloadPlay`（v0.6.1 三按钮链），避免 Android 默认焦点引擎选错方向。行根布局不可聚焦（v0.5.4），焦点进入行默认落在 btnPlay。

**选中态管理**：
- **行内切换焦点**（btnPlay ↔ btnDownloadPlay ↔ btnDelete）：任一按钮获得焦点 → `VideoAdapter.setHighlight(rv, pos)` 给该行 `isSelected=true`（黄框+背景），清其他行；判断方式为沿 parent 链找 RV 后代（v0.5.4，焦点在孙子节点按钮上时也能识别）
- **跳出 RecyclerView**（按 ↑ 到刷新按钮）：ListActivity 的 `OnGlobalFocusChangeListener` 检测 `newFocus` 不在 rvList 内 → `setHighlight(rv, -1)` 清所有行

### 5.4 按钮样式

四个行内按钮（在线播放/先下后播/本地播放/删除）统一：`bg_btn_selector` 背景，`minWidth=0dp minHeight=0dp`（Android Button 默认有 ~48dp minWidth，必须显式设 0 才能缩小），`padding 10dp / 4dp`（横向/纵向），`textSize=14sp`。宽度固定：在线播放/先下后播(本地播放) 84dp（4 字文案），删除 56dp；刷新按钮 `wrap_content`。

标题栏三按钮（v0.6.4）：刷新🔄 / 设置⚙️ / 搜索🔎 统一 **84dp 等宽 + gravity 居中**，间距 12dp，同样是 `bg_btn_selector` + `minWidth/minHeight=0dp`。头像图标聚焦态用独立的 `bg_icon_focus`（透明底 + 3dp 黄描边），与按钮的蓝色填充背景区分。

### 5.5 先下后播 / 本地播放（v0.6.x）

**引擎**：`download/M3u8Downloader.kt`，移植自 `H:\downmovie\script\m3u8_download.py`。

- 流程：获取 m3u8（master→子列表）→ 量子源(lz) 广告检测/校验/去除（>6 组或 >30% 判误判跳过）→ 8 线程下载分片（重试 5 次、断点续传、AES-128 解密）→ 二进制拼接 TS → 重命名为 `编号.ts` 播放
- **不重封装 MP4**（v0.6.2 定案）：曾用 MediaExtractor/MediaMuxer 替代 ffmpeg，但慢（2GB 数分钟）且容错差易出半成品，ExoPlayer 原生支持 MPEG-TS，直接播 TS
- 产物位置：`Android/data/com.tv.mailvod/files/movies/编号.ts`；临时分片在 `编号_tmp/`，成功后清理
- 下载弹窗进度：解析（流动条）→ 下载分片 x/y（真实百分比）→ 拼接 TS（流动条）；取消/返回即 cancel
- **本地播放失败自动切在线**（兜底）：PlayerActivity 收 `EXTRA_FALLBACK_URL`，本地源报错时自动改播 HLS 在线流
- **删除条目**：删除确认弹窗勾选"同时删除已下载内容"（默认勾选）→ 连带删除 `编号.ts`/残留 `编号.mp4`/`编号_tmp/`
- 教训：重封装失败分支必须删除半成品 MP4，否则下次被当有效文件播放报 source error（0006 案例）

---

## 6. 收片流程

```
[启动/刷新]
   │
   1) 读 config.json（含 host/port/user/auth_code/subject_prefix/folder）
   │
   2) IMAP 单连接（自写 SSLSocket，不用 JavaMail 的 Store/Folder）
      LOGIN user + auth_code
      ID ("name" "mailvod")          ← 163 风控要求
      SELECT INBOX                    ← 同一条连接
      SEARCH ALL                      ← 全量，不看已读状态（实现幂等）
      对每封 MATCH 执行 FETCH RFC822  ← 完整 raw MIME 字节
      （READ_ONLY，不修改邮箱任何状态）
   │
   3) MimeMessage 解析：强制 ByteArrayDataSource + MimeMultipart
      - 优先找 application/json 附件
      - 没附件时取 text/plain 正文
   │
   4) parseVideos:
      - 有 ********** 分隔符 → 切分取前面
      - 直接以 [ 或 { 开头 → 直接 parse
      - 否则正则 fallback 找 JSON 块
      - 逐项校验 title/url 非空
   │
   5) LibraryStore.merge: title+episode 匹配 → 覆盖 / 新增 → 落盘
   │
   6) 刷新列表页显示，Toast "拉取完成，新增 X 条"
```

**幂等性**：邮箱不标记已读（READ_ONLY），每次刷新全量拉取 → 本地 JSON 做去重合并。重装 App 后刷新一次即可从邮箱重建整个库。

---

## 7. 踩过的坑（留底）

| 版本 | 报错 | 根因 | 解决 |
|---|---|---|---|
| 0.1.x | `B3 NO EXAMINE Unsafe Login` | 163 风控要求 IMAP `ID` 命令，登录后 EXAMINE 前必须发 | 自写 SSLSocket 单连接，LOGIN 后立即发 ID |
| 0.2.x | `No field protocol in class IMAPStore` | android-mail 1.6.7 把 protocol 做成了 private，反射取 protocol 字段失败 | 完全抛弃 JavaMail Store/Folder，改用自写 SSLSocket 单连接 FETCH RFC822 |
| 0.2.x | `B3 NO EXAMINE`（ID 发了还是失败） | JavaMail IMAPStore 有连接池：store 连接和 folder 连接是**两条独立物理 socket**。ID 发给了 store 那条，EXAMINE 走 folder 那条，服务器照样拒 | 自写单连接，全程一条 TCP+TLS |
| 0.3.0 | 拉取 0 条，FETCH 卡死 | 自己解析 FETCH 响应时，`BODY[] {size}` 后面的 literal 是一次性发出的，但用 BufferedReader 逐行读时，literal 内部的 CRLF 被当成换行消费，字节流错位 | 完全抛弃 BufferedReader，改用 ByteArrayOutputStream 累积全部响应，遇到 tag OK 终止，再从原始字节里切出 literal |
| 0.3.3 | `msg.content` 返回 MIME raw 文本 | 某些 multipart 邮件（尤其是 163 网页端发的），JavaMail `msg.content` 返回 `String` 类型的原始 MIME 文本（`--boundary` + `Content-Type:` 头），而非 Multipart 对象 | 强制 `ByteArrayDataSource(msg.inputStream, ct)` → 构造 `MimeMultipart(ds)`，不依赖 msg.content 的类型判断 |
| 0.3.4 | 附件邮件正文 fallback 拿到 multipart raw | 已由上面那条解决 | 同上 |
| 0.4.0 | 按钮 padding 改了尺寸没变化 | Android Button 类有默认 `minWidth=48dp minHeight=48dp`，padding 再小也被撑住 | 显式设 `android:minWidth="0dp" android:minHeight="0dp"` |
| 0.4.0 | 表头表体列错位 | 表头和表体各自定义列宽，互不相同；表头末尾没给按钮留占位 | 列宽统一到 `VideoAdapter.buildColumnLayoutParams()`；表头末尾加 56dp+56dp+6dp 占位 View，精确匹配表体按钮宽度 |
| 0.4.0 | 焦点跳到外部时旧行黄框不消失 | `setHighlight` 只在 RecyclerView 子 View 焦点变化时触发，焦点跳到刷新按钮时没人清 | ListActivity 挂 `addOnGlobalFocusChangeListener`，检测焦点不在 rvList 内时调 `setHighlight(rv, -1)` |

**163 邮箱的两个已知限制**：
1. `SEARCH` 命令对 UTF-8 charset 支持有坑（`SEARCH CHARSET UTF-8 SUBJECT "m3u8_view"` 返回 0）。解决：`SEARCH ALL` 全量拉取后客户端按 subject 过滤，不走 charset SEARCH。
2. IMAP 连接每次新 session 必须发 `ID`，老 session 重连也需要。

---

## 8. 工程结构

```
app/src/main/java/com/tv/mailvod/
├── ui/
│   ├── ListActivity.kt        列表页主 Activity + 表头动态填充 + 全局焦点监听 + 设置/关于弹窗
│   ├── SearchActivity.kt      搜索页界面壳(v0.6.4): 返回按钮 + 搜索框/按钮占位, 逻辑未实现
│   ├── VideoAdapter.kt        适配器 + 方向键路由 + setHighlight + buildColumnLayoutParams()
│   └── PlayerActivity.kt      ExoPlayer 播放
├── mail/
│   └── MailFetcher.kt         自写 SSLSocket IMAP 客户端 + MimeMessage 解析
├── download/
│   └── M3u8Downloader.kt      m3u8 解析 / 广告滤除 / 多线程分片下载 / AES-128 解密 / TS 拼接
├── store/
│   ├── LibraryStore.kt        library.json 读写 / 合并 / 去重 / 删除
│   └── VideoItem.kt          数据模型 + 列值映射（含 type 字段）
├── config/
│   ├── Config.kt              配置模型（含 list_columns 动态列表）
│   └── ConfigLoader.kt        assets/config.json → 私有目录副本（首次启动复制，已有则不覆盖; save() 供设置弹窗写入）
└── App.kt                     Application 单例

app/src/main/res/
├── layout/
│   ├── activity_list.xml      标题行(头像+标题+版本+刷新/设置/搜索) + 表头(编号+llHeaderFields+按钮占位) + RecyclerView
│   ├── item_video.xml         编号(72dp) + llFields(weight=1) + 行内按钮
│   ├── activity_search.xml    搜索页: 返回按钮 + 搜索框/搜索按钮 + 结果列表 + 空态
│   ├── item_search.xml        搜索结果行: 标题(weight=1) + 摘要 + 想看按钮(84dp)
│   └── dialog_settings.xml    设置弹窗: 邮箱账号 / 授权码两个输入框
├── drawable/
│   ├── ic_head.png            博美头像(mdpi/hdpi/xhdpi/xxhdpi 四密度, 透明背景, 由 _tmp/make_head_icon.py 生成)
│   ├── bg_icon_focus.xml      头像聚焦黄框(3dp #FFD700 12dp 圆角描边)
│   ├── bg_row_selector.xml    state_focused/state_selected → 黄框; default → 深灰背景
│   ├── bg_btn_selector.xml    state_focused/state_pressed → 蓝色背景
│   ├── bg_row_focused.xml     圆角4dp + 2dp黄色边框
│   └── bg_row_normal.xml      圆角4dp + 深灰背景
├── mipmap-*/ic_launcher.png   桌面图标(songsong.png 生成, _tmp/make_icons.py)
└── drawable-xhdpi/ic_banner.png  TV 桌面 banner 640x360

_tmp/
├── make_icons.py              桌面图标/banner 生成（水印遮盖 + 多密度缩放）
├── make_head_icon.py          标题头像生成（抠白底透明 + 多密度）
├── send_body_mail.py          正文 JSON 测试邮件发送器（读同目录 library.json，自动追加 **********）
├── send_test_mail.py          附件 JSON 测试邮件发送器
├── imap_test.py               IMAP 连接 / 授权码测试脚本
├── maven_fetch.py             Gradle 依赖预下载到本地 ~/.m2（绕过网络干扰）
└── library.json               纯 JSON 数组，send_body_mail.py 读取并自动追加分隔符
```

技术栈：Kotlin + RecyclerView + ExoPlayer 2.18.5 + OkHttp 4.9.3 + kotlinx.serialization + android-mail 1.6.7。

minSdk 21 / targetSdk 34 / compileSdk 34。版本号 0.6.4 / versionCode 29；APK 产物 `app/build/outputs/apk/debug/songmovie.apk`（debug 签名）。

---

## 9. 验收要点

| 场景 | 预期 |
|---|---|
| 发一封 `m3u8_view` + 正文 JSON（带 `**********` 分隔符） | 刷新后列表顶部出现新行，标题/国家/类型/年份/导演正确显示，播放 OK |
| 发一封 `m3u8_view` + `.json` 附件 | 刷新后同上（附件路径） |
| 发一封 multipart 正文（163 网页端） | 刷新后正常解析（强制 MimeMultipart 修复生效） |
| 电视剧每集一封 | 列表顶部多行，各自行播放对应集 |
| 同 title+episode 重发（改了 url） | 覆盖 url，**保留原编号与原位置**，列表不重复 |
| 非法 JSON / 缺 title 或 url | 跳过，日志输出原因，列表无该行 |
| 选中行显示橙色外框 + 深灰背景 | OK |
| 按钮聚焦时行黄框保留 | OK（isSelected=true 不随 focus 丢失） |
| 焦点从第一行按 ↑ 跳到刷新按钮，旧行黄框消失 | OK（全局焦点监听 setHighlight(rv, -1)） |
| 遥控器在某行按右 → 焦点移到播放键；再右 → 删除键；左 → 回行 | OK（方向键强制路由） |
| 删除后再发新片 | 分配下一个新编号，不复用旧编号 |
| 重装 App 后刷新 | 全量拉取邮箱，重建 library.json（READ_ONLY 幂等） |
| 按遥控器「菜单」键 | 触发刷新 |
| 表头表体列对齐 | OK（共用 buildColumnLayoutParams + 按钮占位） |
| 按钮尺寸缩小 | OK（minWidth=0dp + padding 10/4） |
| 标题行显示 [头像] 松松看片 (共x) v 0.6.4 + 刷新🔄/设置⚙️/搜索🔎 三等宽按钮 | OK（v0.6.4） |
| 头像透明背景 PNG；遥控器焦点移上出现黄框 | OK（bg_icon_focus 3dp 黄描边） |
| OK 点头像 → 关于弹窗（版本/开发者/操作说明），OK 或返回关闭 | OK |
| 设置⚙️ → 弹窗预填账号/授权码，修改确定后 config.json 更新 | OK（ConfigLoader.save） |
| 搜索🔎 → 进入搜索页；返回按钮 / 遥控器返回键回片库页 | OK（搜索逻辑未实现，点搜索提示开发中） |

---

## 10. 后续可做

- [ ] 自定义按钮聚焦 selector（按钮选中时也有橙色边框，目前是蓝色背景）
- [ ] 空列表时给 btnRefresh 加下一行焦点，让遥控器首次按 OK 就能刷新
- [ ] 开机自启 + 首次启动自动刷新
- [ ] 正文模式下不强制分隔符也行（已 fallback 正则捕获），但分隔符更稳
- [ ] headers 白名单（仅允许 UA/Referer/Origin/Cookie）
- [ ] ConfigLoader 支持通过 adb 覆盖 config.json（目前需要卸载重装才能更新）

### 10.1 删除条目被邮件刷新回刷（2026-09-04 讨论定方案，暂不实施）

**问题**：电视端删除某电影只操作本地 library.json；邮箱里对应邮件仍在，下次刷新（启动自动刷新/菜单键）重新解析后，本地查无此片 → 按"新片"插回列表。

**已定方案（墓碑清单）**：
- library.json 增加 `deleted` 数组，存被删条目的 key（title+episode，或含 url 增强唯一性）
- `LibraryStore.delete` 删除条目的同时写入墓碑
- `LibraryStore.merge` 在"新片编号 max+1"判断之前先查墓碑，命中则跳过（且不重复覆盖墓碑）
- 已知代价：重装 app 后 files 目录清空，墓碑丢失 → 重装全量重建时被删片会回来（家用场景可接受；如不可接受再叠加"已处理邮件 UID 记录"方案，但会破坏全量重建能力，不推荐）
- UI 可选增强：设置页显示墓碑清单，支持"恢复"单个条目（移出墓碑）
