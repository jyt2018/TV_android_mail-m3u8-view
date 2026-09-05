# MailM3U8 TV 设计文档（v0.7.6）

> TV 端「Gitee 片源清单 → 合并到本地 JSON → 列表选择 → 播放 m3u8」+ 手机版（phone flavor）
> 状态：已实现（Gitee 收片、双端自动更新已上线、UI 打磨中）
> 日期：2026-09-05

> **v0.7.6 变更**：TV 播放页 OK 键直接切换播放/暂停（`controllerAutoShow=false` + dispatchKeyEvent 分发层拦截，不再弹控制条）；手机端：关于弹窗（点左上角标题）、设置输入框 3 行换行显示长地址、列表行删除按钮移到片名行右侧、播放按钮(40dp/14sp)与刷新键一致。
> **v0.7.5 重大变更**：全面移除邮箱(IMAP)收片通道，改为 Gitee 仓库 `library.json` 片源清单（一次 HTTPS GET，零配置可用）。
> 历史邮件方案的全部设计/坑记录保留在 §2/§7 作留底。

---

## 1. 目标与边界

### 1.1 核心目标
1. 启动应用 → 从 Gitee 片库地址（默认内置）拉取 `library.json` → 解析 JSON → 合并到本地 `library.json`。
2. 单页列表展示，遥控器上下选行、右移到播放键、OK 即播，无详情页。
3. 播放使用 ExoPlayer 2.18.5（TV 设备低版本友好）。

### 1.2 明确不做
- 不用 Room 数据库，仅一个 JSON 数组文件 `library.json`。
- 不做后台轮询 / 开机自启：启动时拉取一次 + 列表页「刷新」键手动拉取。
- 不做详情页 / 海报 / 多集菜单。
- ~~不做 SMTP 回执~~（邮箱通道已于 0.7.5 整体移除）。
- 播放进度云同步不做（本地 files/progress.json 已够用，讨论结论 2026-09-05）。
- （v0.7.1 已补充实现：断点续播记忆，见 §11.2）

---

## 2. 片源清单协议（Gitee library.json）

片源以 JSON 数组放在 Gitee 公开仓库 `unixsam/mailvod-release` 的 `library.json`（与 version.json/APK 同仓库）。应用按 config.json 的 `library_url`（raw 直链，默认指向该文件）拉取。

> 历史方案（0.7.4 及以前）：邮件主题 `m3u8_view` + 正文 JSON（`**********` 分隔符）或 `.json` 附件，
> 自写 IMAP 单连接 + MIME 多级解码。0.7.5 起移除，完整设计见 git 历史与 §7 坑表。

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
片源清单中重复 key 或与本地重复 → 覆盖旧条目（更新 url 或元信息），**保留原编号和入库时间**。

**维护方式**：网页端 Gitee 直接编辑 `library.json`，或本地编辑后用 `_tmp/push_library.py` 上传（自动从电视设备读取现有片库转投递格式）。raw 直链有分钟级 CDN 缓存，改完稍等再刷新。

---

## 3. 配置文件 config.json

位置：应用私有目录 `/data/data/<包名>/files/config.json`。
**零配置可用**：文件不存在或损坏时全部走默认值；「设置」按钮可改 `library_url`。旧版本遗留的 `mail` 段会被 ignoreUnknownKeys 忽略，不影响读取。

```jsonc
{
  "library_url": "https://gitee.com/unixsam/mailvod-release/raw/master/library.json",
                                   // 片源清单地址, 设置页可改
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
| **返回** | 播放页 2 秒内连按两次返回片库页（v0.7.4 防误触：第一次弹 Toast「再按一次返回键返回列表」，进度照常落盘；手机版无此拦截） |

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

## 6. 片库同步流程

```
[启动/刷新]
   │
   1) 读 config.json（library_url, 默认 Gitee raw 直链）
   │
   2) LibrarySync.fetch: 一次 HTTPS GET（15s 超时, TlsCompat 全局证书）
   │
   3) parse: 数组/单对象直接 parse, 杂文包裹时正则回退找 JSON 块;
      逐项校验 title/url 非空白, headers 过滤空白值
   │
   4) LibraryStore.merge: title+episode 匹配 → 覆盖 / 新增 → 落盘
   │
   5) 刷新列表页显示, Toast "拉取完成, 新增 X 条"
```

**幂等性**：远端清单是只读输入, 每次刷新全量拉取 → 本地 JSON 去重合并。重装 App 后刷新一次即可重建整个库。
**离线可用**：拉取失败仅 Toast 提示, 本地 library.json 不受影响。

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
| 0.7.3 | 本地视频提示"续播"却从 0 开始 | ExoPlayer `setMediaItem/setMediaSource` 默认 resetPosition，先 seekTo 再设媒体源会丢起始位置 | 顺序改为：设媒体源 → seekTo(断点) → prepare() |
| 0.1.2(phone) | 手机首装拉取 0 条（日志 `SKIP: prefix not match`） | SetupActivity 建的配置落盘了 Config 里过时默认值 `subject_prefix="[TV投递]"`，过滤不到 `m3u8_view` 主题邮件 | 默认值改回 `m3u8_view`；已装设备就地修 config.json；应用内帮助文案同步修正 |

**163 邮箱的两个已知限制**：
1. `SEARCH` 命令对 UTF-8 charset 支持有坑（`SEARCH CHARSET UTF-8 SUBJECT "m3u8_view"` 返回 0）。解决：`SEARCH ALL` 全量拉取后客户端按 subject 过滤，不走 charset SEARCH。
2. IMAP 连接每次新 session 必须发 `ID`，老 session 重连也需要。

---

## 8. 工程结构

```
app/src/
├── main/                          共用(TV/phone 两版本都打包, 只存一份)
│   ├── java/com/tv/mailvod/
│   │   ├── net/LibrarySync.kt         Gitee 片库地址 GET + 投递 JSON 解析(0.7.5 取代 mail/)
│   │   ├── download/M3u8Downloader.kt m3u8 解析/分片下载/AES-128/TS 拼接
│   │   ├── download/MovieFiles.kt     movies/ 本地文件管理(下载集/删除/定位, 2026-09-04 抽取共用)
│   │   ├── playback/VodPlayer.kt      ExoPlayer 核心(HLS/headers/断点续播/本地兜底, 2026-09-04 抽取共用)
│   │   ├── store/                     LibraryStore / ProgressStore / VideoItem
│   │   ├── config/                    Config(library_url 默认值) / ConfigLoader
│   │   ├── net/                       TlsCompat / UpdateChecker(双通道版本清单) / AppUpdater(更新流程)
│   │   └── App.kt                     Application 单例
│   ├── res/                           mipmap 桌面图标 + colors + strings(公共)
│   ├── assets/certs/                  ISRG Root X1/X2(TLS 兼容) + config.example.json(仅参考)
│   └── AndroidManifest.xml            权限 + 公共 application + FileProvider
├── tv/                            TV 版专属(2026-09-04 flavor 化)
│   ├── java/com/tv/mailvod/ui/        ListActivity(遥控器) / VideoAdapter(焦点) /
│   │                                  PlayerActivity(按键壳) / SearchActivity
│   ├── res/                           TV 布局/焦点 drawable/Theme.Leanback 主题/ic_head/ic_banner/dialog_settings(片库地址)
│   └── AndroidManifest.xml            leanback + banner + LEANBACK_LAUNCHER + REQUEST_INSTALL_PACKAGES
└── phone/                         手机版专属(2026-09-04 新增)
    ├── java/com/tv/mailvod/ui/        ListActivity(触屏) / VideoAdapter / PlayerActivity(触控条壳)
    ├── res/                           触屏布局 + Theme.AppCompat 主题 + 措辞覆盖 strings
    └── AndroidManifest.xml            仅 LAUNCHER, 触屏, 无 leanback
```

技术栈：Kotlin + RecyclerView + ExoPlayer 2.18.5 + OkHttp 4.9.3 + kotlinx.serialization（0.7.5 移除 android-mail）。

minSdk 21 / targetSdk 34 / compileSdk 34。双 flavor 构建与产物（debug 签名）：
- `gradle assembleTvDebug` → `app/build/outputs/apk/tv/debug/app-tv-debug.apk`（com.tv.mailvod，0.7.6 / 40）
- `gradle assemblePhoneDebug` → `app/build/outputs/apk/phone/debug/app-phone-debug.apk`（com.mailvod.phone，0.1.4 / 5）
- leanback 依赖仅 `tvImplementation`（手机包不携带）；versionCode/Name 定义在 build.gradle.kts 的 productFlavors 内，各版本独立演进

---

## 9. 验收要点

| 场景 | 预期 |
|---|---|
| 片源清单 library.json 加一条新片（Gitee 网页端或 push_library.py） | 刷新后列表顶部出现新行，标题/国家/类型/年份/导演正确显示，播放 OK |
| 同 title+episode 改 url 重传清单 | 覆盖 url，**保留原编号与原位置**，列表不重复 |
| 清单里缺 title 或 url 的条目 | 跳过，日志输出原因，列表无该行 |
| 断网时刷新 | Toast 拉取失败，本地 library.json 与列表不变 |
| 重装 App 后刷新 | 从 Gitee 片库地址全量重建 library.json |
| 选中行显示橙色外框 + 深灰背景 | OK |
| 按钮聚焦时行黄框保留 | OK（isSelected=true 不随 focus 丢失） |
| 焦点从第一行按 ↑ 跳到刷新按钮，旧行黄框消失 | OK（全局焦点监听 setHighlight(rv, -1)） |
| 遥控器在某行按右 → 焦点移到播放键；再右 → 删除键；左 → 回行 | OK（方向键强制路由） |
| 删除后再更新清单 | 分配下一个新编号，不复用旧编号 |
| 按遥控器「菜单」键 | 触发刷新 |
| 表头表体列对齐 | OK（共用 buildColumnLayoutParams + 按钮占位） |
| 按钮尺寸缩小 | OK（minWidth=0dp + padding 10/4） |
| 标题行显示 [头像] 松松看片 (共x) v 0.6.4 + 刷新🔄/设置⚙️/搜索🔎 三等宽按钮 | OK（v0.6.4） |
| 头像透明背景 PNG；遥控器焦点移上出现黄框 | OK（bg_icon_focus 3dp 黄描边） |
| OK 点头像 → 关于弹窗（版本/开发者/操作说明），OK 或返回关闭 | OK |
| 设置⚙️ → 弹窗预填片库地址（默认 Gitee 直链），修改确定后 config.json 更新 | OK（ConfigLoader.save） |
| 搜索🔎 → 进入搜索页；返回按钮 / 遥控器返回键回片库页 | OK（搜索逻辑未实现，点搜索提示开发中） |

---

## 10. 后续可做

- [ ] 自定义按钮聚焦 selector（按钮选中时也有橙色边框，目前是蓝色背景）
- [ ] 空列表时给 btnRefresh 加下一行焦点，让遥控器首次按 OK 就能刷新
- [ ] 开机自启 + 首次启动自动刷新
- [ ] headers 白名单（仅允许 UA/Referer/Origin/Cookie）
- [ ] ConfigLoader 支持通过 adb 覆盖 config.json（目前需要卸载重装才能更新）

### 10.1 删除条目被片源清单刷新回刷（2026-09-04 讨论定方案，暂不实施）

**问题**：电视端删除某电影只操作本地 library.json；Gitee 片源清单里对应条目仍在，下次刷新重新解析后，本地查无此片 → 按"新片"插回列表。

**已定方案（墓碑清单）**：
- library.json 增加 `deleted` 数组，存被删条目的 key（title+episode，或含 url 增强唯一性）
- `LibraryStore.delete` 删除条目的同时写入墓碑
- `LibraryStore.merge` 在"新片编号 max+1"判断之前先查墓碑，命中则跳过（且不重复覆盖墓碑）
- 已知代价：重装 app 后 files 目录清空，墓碑丢失 → 重装全量重建时被删片会回来（家用场景可接受）
- UI 可选增强：设置页显示墓碑清单，支持"恢复"单个条目（移出墓碑）

---

## 11. 手机版（phone flavor, v0.1.0）

2026-09-04 从 TV 版派生。采用单模块 + productFlavors（`device` 维度）而非多模块：
共用代码沉到 `src/main`（片库同步、媒体库、下载、播放核心、配置），版本差异只在 `src/tv` / `src/phone` 的 UI 壳与 manifest。

### 11.1 与 TV 版的差异

| 维度 | TV 版 | 手机版 |
|---|---|---|
| 包名 | com.tv.mailvod | com.mailvod.phone（可共存/并行调试） |
| 版本 | 0.7.6 / 40 独立演进 | 0.1.4 / 5 独立演进 |
| 入口 | LEANBACK_LAUNCHER + LAUNCHER | 仅 LAUNCHER |
| 主题 | Theme.Leanback 系 | Theme.AppCompat.NoActionBar 系（同深色配色） |
| 播放交互 | 遥控器 OK=播放/暂停直接切换(0.7.6 起不弹控制条, dispatchKeyEvent 拦截), 左右 ±10s, 返回=二次确认退出 | ExoPlayer 默认触控条, 默认横屏(sensorLandscape), 返回直接退出 |
| 列表交互 | D-pad 焦点高亮 + 表头表格 | 卡片行(标题/元信息/已下载标签/删除键靠右与片名同行) + 按钮行(播放/先下后播, 与刷新键同规格) |
| 关于弹窗 | 点头像图标弹出 | 点左上角标题「松松看片」弹出(0.1.4 起) |
| 设置弹窗 | dialog_settings 布局(片库地址) | 代码构建布局(片库地址) |
| 自动更新 | 有(REQUEST_INSTALL_PACKAGES) | 有(0.1.1 起, 同权限) |
| 搜索页 | 界面壳已实现 | MVP 无，后续补 |

### 11.2 共用抽取（避免两份拷贝）

- `MovieFiles`：movies 目录/已下载集合/本地文件定位与删除（原 TV ListActivity 私有方法上提）
- `VodPlayer`：ExoPlayer 构建/HLS+headers/断点续播(10s 落盘)/本地损坏切在线兜底（原 TV PlayerActivity 主体上提；TV 只留按键处理，phone 只留生命周期转发）
- `LibrarySync`：Gitee 片库拉取与解析（0.7.5 取代原 MailFetcher+SetupActivity 链路）

### 11.3 自动更新（Gitee 双通道, 2026-09-05）

两版本共用同一个 Gitee 仓库 `unixsam/mailvod-release` 与同一份 `version.json`：
- 顶层 = tv 段（保持旧格式，兼容已装旧包）；`"phone": {...}` 子对象 = 手机版
- `UpdateChecker` 按 channel 取段；检查/下载/安装弹窗逻辑统一在共用 `AppUpdater`
- 发布：`py _tmp\publish_gitee.py --flavor tv|phone`（自动合并另一 flavor 的段；合并基准必须走 Gitee contents API——raw 直链有 CDN 缓存，曾把 phone 段覆盖丢失）
- 手机已装包用旧格式判断无影响；phone 从 0.1.1 起具备自动更新能力
- **端到端实证（2026-09-05）**：手机 0.1.1→0.1.2、电视 0.7.3→0.7.4 均经 Gitee 自动更新完成（检查→下载→MD5 校验→弹窗→用户确认→系统安装器），双通道互不干扰

### 11.4 phone 版后续可做

- 搜索页（对齐 TV）
- 竖屏海报式列表（当前为信息行式）
- 播放页返回键二次确认（TV 已加 0.7.4，手机暂无需求）
