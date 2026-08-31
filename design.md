# MailM3U8 TV 设计文档（v0.4.8）

> TV 端「邮箱投递影片 → 合并到本地 JSON → 列表选择 → 播放 m3u8」
> 状态：已实现（收片通、UI 打磨中）
> 日期：2026-09-01

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

## 5. UI（单页列表，v0.4.7）

### 5.1 布局

```
┌──────────────────────────────────────────────────────────────────────┐
│ 邮箱影院（共3）     ───────────  按遥控器【菜单☰】按钮  [刷新]         v0.4.8 │
├──────┬───────────────────────────┬─────────┬─────────┬──────┬───────┤
│ 编号 │ 标题  国家  类型  年份 导演 │ 播放 ▶  │ 删除 ✕  │ ← 表头 │
├──────┼───────────────────────────┼─────────┼─────────┼──────┼───────┤
│ 0003 │ 启示录     美国   动作 2006 梅尔吉 | [ 播放 ] │ [ 删除 ]│ ← 选中黄框
│ 0002 │ 所有邪佞... 美国   -    -   -     │ [ 播放 ] │ [ 删除 ]│
│ 0001 │ 杰瑞和玛... 美国   -    -   -     │ [ 播放 ] │ [ 删除 ]│
└──────┴───────────────────────────┴─────────┴─────────┴──────┴───────┘
  ↑ 72dp  ↑ 120+120+50+160  ↑ 56dp  ↑ 56dp
          title 自适应 (weight=1) + 固定列宽
```

- 表头不可聚焦，纯装饰。**表头与表体列宽共用同一个 `VideoAdapter.buildColumnLayoutParams()` 函数**，天然对齐。
- 选中行有**橙色外框 + 深灰背景**（`rowRoot.isSelected=true` 触发 `bg_row_selector` 里的 `state_selected`）。按钮聚焦时 rowRoot 失去焦点但 `isSelected` 仍为 true → 黄框保留。
- **删除了 `>` 箭头**（黄框已足够表达选中态）。
- 编号列 4 位补零，不可聚焦。
- 标题行：左标题 + 弹性空白 + 「按遥控器'菜单'按钮」提示文字 + 刷新按钮 + 版本号。

### 5.2 列宽策略

**单一数据源**：`VideoAdapter.buildColumnLayoutParams(key, density)` 定义每列宽度。表头 ListActivity 和表体 VideoAdapter.onCreateViewHolder **都调用这个函数**，保证每列宽度完全一致。

| 字段 | 宽度 | 说明 |
|---|---|---|
| title | weight=1 | 自适应剩余空间，超长省略号截断 |
| country | 120dp | 4 个中文字符 |
| type | 120dp | 4 个中文字符 |
| year | 50dp | 4 位数字 |
| director | 160dp | 8 个中文字符，超长截断 |
| actors / episode | 160dp / 40dp | 同字段复用 |

表头末尾加 2 个零宽占位 View（56dp + 56dp + 6dp marginStart），精确匹配表体播放/删除按钮宽度 → 两边右边界一致。

### 5.3 遥控器焦点

| 操作 | 行为 |
|---|---|
| **上/下** | 列表行间移动（RecyclerView 默认） |
| **右** | 行内：行 root → 播放键 → 删除键；最右键再右 → 下一行 root（RecyclerView 默认） |
| **左** | 行内：删除键 → 播放键 → 行 root；行 root 左 → 上一行（RecyclerView 默认） |
| **OK** | 焦点在播放键 → 启动播放器；焦点在删除键 → AlertDialog 二次确认；焦点在刷新键 → 重新拉取 |
| **菜单键** | 全局监听 `KeyEvent.KEYCODE_MENU`（=82），等价于点刷新按钮 |
| **返回** | 播放中返回列表（停止播放） |

**方向键强制路由**：代码中显式设置了 `rowRoot.nextFocusRightId = btnPlay`、`btnPlay.nextFocusRightId = btnDelete`、`btnPlay.nextFocusLeftId = rowRoot`、`btnDelete.nextFocusLeftId = btnPlay`，避免 Android 默认焦点引擎选错方向。

**选中态管理**：
- **行内切换焦点**（row ↔ btnPlay ↔ btnDelete）：行 root / 按钮任一获得焦点 → `VideoAdapter.setHighlight(rv, pos)` 给该行 `isSelected=true`（黄框+背景），清其他行
- **跳出 RecyclerView**（按 ↑ 到刷新按钮）：ListActivity 的 `OnGlobalFocusChangeListener` 检测 `newFocus` 不在 rvList 内 → `setHighlight(rv, -1)` 清所有行

### 5.4 按钮样式

三个按钮（刷新/播放/删除）统一：`bg_btn_selector` 背景，`minWidth=0dp minHeight=0dp`（Android Button 默认有 ~48dp minWidth，必须显式设 0 才能缩小），`padding 10dp / 4dp`（横向/纵向），`textSize=14sp`。按钮宽度固定 `56dp`（播放/删除），刷新按钮 `wrap_content`。

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
│   ├── ListActivity.kt        列表页主 Activity + 表头动态填充 + 全局焦点监听
│   ├── VideoAdapter.kt        适配器 + 方向键路由 + setHighlight + buildColumnLayoutParams()
│   └── PlayerActivity.kt      ExoPlayer 播放
├── mail/
│   └── MailFetcher.kt         自写 SSLSocket IMAP 客户端 + MimeMessage 解析
├── store/
│   ├── LibraryStore.kt        library.json 读写 / 合并 / 去重 / 删除
│   └── VideoItem.kt          数据模型 + 列值映射（含 type 字段）
├── config/
│   ├── Config.kt              配置模型（含 list_columns 动态列表）
│   └── ConfigLoader.kt        assets/config.json → 私有目录副本（首次启动复制，已有则不覆盖）
└── App.kt                     Application 单例

app/src/main/res/
├── layout/
│   ├── activity_list.xml      标题行(刷新+菜单提示+版本) + 表头(编号+llHeaderFields+按钮占位) + RecyclerView
│   └── item_video.xml         编号(72dp) + llFields(weight=1) + btnPlay(56dp) + btnDelete(56dp)
└── drawable/
    ├── bg_row_selector.xml    state_focused/state_selected → 黄框; default → 深灰背景
    ├── bg_btn_selector.xml    state_focused/state_pressed → 蓝色背景
    ├── bg_row_focused.xml     圆角4dp + 2dp黄色边框
    └── bg_row_normal.xml      圆角4dp + 深灰背景

_tmp/
├── send_body_mail.py          正文 JSON 测试邮件发送器（读同目录 library.json，自动追加 **********）
├── send_test_mail.py          附件 JSON 测试邮件发送器
├── imap_test.py               IMAP 连接 / 授权码测试脚本
├── maven_fetch.py             Gradle 依赖预下载到本地 ~/.m2（绕过网络干扰）
└── library.json               纯 JSON 数组，send_body_mail.py 读取并自动追加分隔符
```

技术栈：Kotlin + RecyclerView + ExoPlayer 2.18.5 + OkHttp 4.9.3 + kotlinx.serialization + android-mail 1.6.7。

minSdk 21 / targetSdk 34 / compileSdk 34。

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

---

## 10. 后续可做

- [ ] 自定义按钮聚焦 selector（按钮选中时也有橙色边框，目前是蓝色背景）
- [ ] 空列表时给 btnRefresh 加下一行焦点，让遥控器首次按 OK 就能刷新
- [ ] 开机自启 + 首次启动自动刷新
- [ ] 正文模式下不强制分隔符也行（已 fallback 正则捕获），但分隔符更稳
- [ ] headers 白名单（仅允许 UA/Referer/Origin/Cookie）
- [ ] ConfigLoader 支持通过 adb 覆盖 config.json（目前需要卸载重装才能更新）
