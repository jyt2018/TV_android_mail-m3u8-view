# MailM3U8 设计文档

> TV 端「Gitee 片源清单 → 合并到本地 JSON → 列表选择 → 播放 m3u8」+ 手机版（phone flavor）
> 状态：已实现（Gitee 收片、双端自动更新已上线）

---

## 1. 目标与边界

### 1.1 核心目标
1. 启动应用 → 从片源地址（默认内置 Gitee 直链）拉取 `library.json` → 解析 JSON → 合并到本地 `library.json`。
2. 单页列表展示，遥控器上下选行（已下载行默认聚焦"本地播放"）、OK 即播，无详情页。
3. 播放使用 ExoPlayer 2.18.5（TV 设备低版本友好）。

### 1.2 明确不做
- 不用 Room 数据库，仅一个 JSON 数组文件 `library.json`。
- 不做后台轮询 / 开机自启：启动时拉取一次 + 列表页「刷新」键手动拉取。
- 不做详情页 / 海报 / 多集菜单。
- 播放进度云同步不做（本地 `files/progress.json` 已够用）。

---

## 2. 片源清单协议（library.json）

片源以 JSON 数组放在 Gitee 公开仓库 `unixsam/mailvod-release` 的 `library.json`（与 version.json/APK 同仓库）。应用按 config.json 的 `library_url`（直链，默认指向该文件）拉取。**托管平台不限 Gitee**：LibrarySync 就是一次普通 HTTPS GET + JSON 解析（无令牌/无域名校验），任何公开可访问、返回该 JSON 的地址（自建服务器 / 局域网 HTTP / 对象存储等）均可，在设置页改「片源地址」即可；唯一约束是无登录、无特殊请求头、证书能被老电视信任。

### 2.1 单条 JSON 字段

```json
{
  "title": "流浪地球3",
  "year": 2027,
  "country": "中国",
  "type": "科幻",
  "director": "郭帆",
  "url": "https://cdn.example.com/vod/ep01/index.m3u8",
  "headers": { "Referer": "https://example.com/", "User-Agent": "okhttp/4.12" }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `title` | **是**（不能空/空白） | 片名 |
| `url` | **是**（不能空/空白） | m3u8 地址，http/https |
| `year` `country` `type` `director` | 否 | 元信息 |
| `headers` | 否 | 防盗链请求头 |

**唯一键（去重覆盖）**：`title`。
片源清单中重复 key 或与本地重复 → 覆盖旧条目（更新 url 或元信息），**保留原入库时间**。

**维护方式**：网页端 Gitee 直接编辑 `library.json`，或本地编辑后用 `_tmp/push_library.py` 上传（自动从电视设备读取现有片库转投递格式）。raw 直链有分钟级 CDN 缓存，改完稍等再刷新。

---

## 3. 配置文件 config.json

位置：应用私有目录 `/data/data/<包名>/files/config.json`。
**零配置可用**：文件不存在或损坏时全部走默认值；「设置」按钮可改 `library_url`（片源）与 `update_url`（APK 更新）。

```jsonc
{
  "library_url": "https://gitee.com/unixsam/mailvod-release/raw/master/library.json",
                                   // 片源清单地址, 设置页可改(不限 Gitee)
  "update_url": "https://gitee.com/unixsam/mailvod-release/raw/master/version.json",
                                   // APK 更新检查地址, 设置页可改, 缺省同仓库
  "list_columns": ["title", "country", "type", "year", "director"],
                                   // 列表每行显示哪些字段,顺序即显示顺序
                                   // 可选值: title, country, type, year, director
                                   // (旧配置里残留的 episode 列读取时自动过滤)
  "player": {
    "auto_next": false             // 播放完是否自动播下一集 (暂未实现,预留)
  }
}
```

---

## 4. 本地存储 library.json

位置：应用私有目录，单一 JSON 数组。条目**无编号字段**，以 `title` 为唯一键：

```json
[
  { "title": "流浪地球3", "year": 2027, "country": "中国", "type": "科幻", "director": "郭帆",
    "url": "https://...ep01.m3u8", "headers": {...}, "_added_at": 1788200000 },
  { "title": "启示录", ... }
]
```

- **`_added_at`**：入库时间戳（秒），列表按此逆序排列（最新在上，同刻按片名）。
- **合并策略**：拉取到的条目按 `title` 唯一键查找本地：
  - **命中（重复投递）**：覆盖 url / headers / type / country / director / year 等业务字段，**保留原 `_added_at`**（位置不变、不重复计数）。可用于修正 url 或补全元信息。
  - **未命中（新片）**：记当前时间戳 `_added_at`，加入列表（按时间逆序排列，新片置顶）。
  - **跳过**：title 或 url 为空/空白的条目直接跳过，不写库，不计入「新增 N 条」统计。
  - **已删除条目**：如果之前在应用里删除过某条目，后续片源清单又投递同一 title → 会被当作新片重新入库。
- **旧文件兼容**：旧版带 `_id` 编号的条目读取时忽略该字段；磁盘上遗留的旧「编号.ts」产物会自动改名为「片名.ts」（一次性、幂等）。

---

## 5. UI

### 5.0 页面命名（沟通约定）

| 页面名 | 类 / 布局 | 说明 |
|---|---|---|
| **片库页** | `ListActivity` / activity_list | 主页影片列表, 打开 app 即此页 |
| **播放页** | `PlayerActivity` / activity_player | 在线 HLS 或本地 ts 播放 |
| **刷新页** | `RefreshActivity` / activity_refresh | 说明文字 + 遥控器示意图(TV) + 刷新按钮(默认焦点) + log 文本框; 左上角 ← 返回 |
| **搜索页** | `SearchActivity` / activity_search | 界面壳: 左上角 ← 返回按钮(遥控器返回键等效) + 搜索框 + 搜索按钮 + 结果列表 |
| **设置页** | `SettingsActivity` / activity_settings | 双输入框: APK 更新地址 + 片源地址, 保存后写入 config.json |
| **关于页** | `AboutActivity` / activity_about | 版本 / 开发者 / 已下载统计 / 操作说明 / 检查更新按钮(默认焦点) |
| **下载弹窗** | 片库页内 `AlertDialog` / dialog_download | 先下后播的进度弹窗 (解析→检测广告→下载分片 x/y→拼接 TS) |
| **删除确认弹窗** | 片库页内 `AlertDialog` | 确认文案 + 复选框"同时删除已下载内容"(默认勾选) |

### 5.1 片库页布局

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ [头像] 松松看片 (共3) v x.y.z                            [刷新] [设置] [搜索]   │
├────────┬───────────────────────────────────┬──────────┬──────────┬──────────┤
│ 已下载 │ 片名            国家  类型 年份 导演 │ 在线播放 │ 先下后播 │   删除   │ ← 表头
├────────┼───────────────────────────────────┼──────────┼──────────┼──────────┤
│   ✔    │ 守法公民        美国  剧情 2009 F·加里 │[在线播放]│[本地播放]│ [删除]  │
│        │ ...                               │[在线播放]│[先下后播]│ [删除]  │
└────────┴───────────────────────────────────┴──────────┴──────────┴──────────┘
  ↑64dp   ↑片名自适应(weight=1) + 120 + 70 + 50 + 160  ↑84dp     ↑84dp    ↑56dp
```

- **标题行**：左起为头像图标 + 标题 + 小字版本号 + 弹性空白 + 刷新/设置/搜索三按钮
  - **头像图标** `ivIcon`（48dp）：`drawable/ic_head.png`（透明背景）。可聚焦，聚焦时黄框（`bg_icon_focus`：2dp #FFD700 12dp 圆角描边）；OK 点击进**关于页**（版本 / 开发者 / 已下载部数 / 占用空间 / 剩余空间 / 操作说明 / 检查更新按钮）
  - **版本号**：紧跟标题后小字体（14sp 灰），格式 `v x.y.z`（v 后带空格），动态读 PackageInfo
  - **三按钮等宽**：刷新 / 设置 / 搜索，统一 84dp 宽、文字居中、间距 12dp
  - **刷新**：进入刷新页（页内按钮触发拉取；片库页遥控器菜单键仍可直接刷新）
  - **设置**：进入设置页，双输入框预填 APK 更新地址与片源地址，保存后 `ConfigLoader.save()` 写 `files/config.json`
  - **搜索**：进入搜索页（界面壳）
- **已下载列**：该条目存在本地 `片名.ts` 时显示 ✔；已下载行的"先下后播"按钮文字变为 **"本地播放"**。
- 表头不可聚焦，纯装饰。**表头与表体列宽共用同一个 `VideoAdapter.buildColumnLayoutParams()` 函数**，天然对齐。
- 选中行有**橙色外框 + 深灰背景**（`rowRoot.isSelected=true` 触发 `bg_row_selector` 里的 `state_selected`）。
- **rvList 右缘垂直滚动条**：`scrollbars=vertical` + 自定义 `scrollbar_thumb`（6dp 半透明白圆角细条），滚动时显示、停止 1.5s 后淡出（框架默认 thumb 在深色界面几乎不可见）。
- **行根 rowRoot 可聚焦**：上下键落在行根 → 定向默认按钮（见 5.3）；行根/按钮任一聚焦黄框都会亮。

### 5.1.1 搜索页（界面壳，逻辑未实现）

```
┌────────────────────────────────────────────┐
│ [← 返回]                                    │ ← 左上角, 遥控器返回键等效
│                                             │
│ [输入片名关键词____________________] [搜索]  │ ← 搜索行
├────────────────────────────────────┬────────┤
│ 流浪地球3                           │ [想看] │ ← item_search 行
│ 2027 / 科幻 / 中国                  │        │
├────────────────────────────────────┼────────┤
│ ...                                │ [想看] │
└────────────────────────────────────┴────────┘
```

- 返回按钮 `btnBack` → finish；系统返回键同样生效
- `etKeyword` 搜索框 + `btnSearch` 搜索按钮（点击暂提示"搜索功能开发中"）
- `rvResults` 结果列表 + `tvEmpty` 空态提示；行布局 `item_search`：标题(粗体, weight=1) + 摘要 + 行尾 **想看** 按钮(84dp)
- 搜索数据源与"想看"行为待后续实现

### 5.1.2 刷新页

```
┌────────────────────────────────────────────┐
│ [← 返回]          刷新片库                  │ ← 左上角返回(遥控器返回键等效)
│                                            │
│   按下遥控器的【菜单】按键可以直接刷新片库    │ ← 说明文字
│              ┌────┐                        │
│              │ ▲  │                        │
│            ┌─┴────┴─┐                      │
│            │◀ OK  ▶ │  ← 遥控器示意图      │
│            └─┬────┬─┘    (菜单键黄框高亮)  │
│              │ ▼  │                       │
│              └────┘                       │
│            ┌──────────┐                    │
│            │  菜单     │ ← 菜单键(高亮)     │
│            └──────────┘                    │
│                [刷新]                       │ ← 刷新按钮(默认焦点)
│ ┌────────────────────────────────────────┐ │
│ │ 连接片源地址…                           │ │
│ │ 清单共 3 条                             │ │ ← log 文本框
│ │ 其中新增 1 条                           │ │   (多行等宽字体)
│ │ 刷新完毕                                │ │
│ └────────────────────────────────────────┘ │
└────────────────────────────────────────────┘
```

- 返回按钮 `btnBack` → finish；系统返回键同样生效
- **遥控器示意图**（仅 TV 版）：`bg_remote_body` 机身 + `bg_remote_key` 方向键(▲▼◀▶) + `bg_remote_ok` OK 键 + `bg_remote_menu` 菜单键（黄框高亮，突出"菜单键 = 刷新"入口）；手机版无示意图，说明文字为触屏措辞（"点击【刷新】按钮从片源地址拉取片库"）
- **刷新按钮默认焦点**：遥控器进页即可 OK 触发；刷新过程逐行写入 log 文本框（连接片源地址… / 清单共 N 条 / 其中新增 N 条 / 刷新完毕，失败显示原因），期间按钮禁用防连按
- 本页遥控器菜单键（`KEYCODE_MENU`）同样直接触发刷新（与片库页一致）
- 返回片库页后其 `onResume` 自动重载列表

### 5.1.3 设置页

- 双输入框：**APK 更新地址** + **片源地址**（默认值预填），手机版输入框沿用四周描边框背景 `bg_edit_box`
- 保存按钮默认焦点；非空校验（任一为空 Toast 提示）→ `ConfigLoader.save()` 写 `files/config.json` → Toast"设置已保存"并关闭页面
- 左上角返回按钮；返回片库页后配置改动即时生效（`onResume` 重载）

### 5.1.4 关于页

- 内容：版本信息（开发者 / `v x.y.z (code)`）+ 下载统计（已下载 N 部 / 占用空间 / 剩余空间，目录遍历异步计算）+ 操作说明
- **检查更新**按钮默认焦点，走共用 `AppUpdater` 手动检查
- 入口：TV 片库页头像图标 / 手机片库页左上角标题「松松看片」；左上角返回按钮

### 5.2 列宽策略

**单一数据源**：`VideoAdapter.buildColumnLayoutParams(key, density)` 定义每列宽度。表头 ListActivity 和表体 VideoAdapter.onCreateViewHolder **都调用这个函数**，保证每列宽度完全一致。

| 字段 | 宽度 | 说明 |
|---|---|---|
| title | weight=1 | 自适应占满剩余空间，超长省略号截断 |
| country | 120dp | 4 个中文字符 |
| type | 70dp | 2 个中文字符 |
| year | 50dp | 4 位数字 |
| director | 160dp | 8 个中文字符，超长截断 |

按钮区占位（表头末尾零宽 View）：在线播放 84dp + 先下后播/本地播放 84dp(+6dp margin) + 删除 56dp(+6dp margin)，精确匹配表体按钮 → 两边右边界一致。

### 5.3 遥控器焦点

| 操作 | 行为 |
|---|---|
| **上/下** | 行间移动（代码强制路由）：`ListActivity.dispatchKeyEvent` 拦截 → `moveRowFocus(±1)` 滚动到目标行并把焦点交给该行**默认按钮**：**已下载行 → 本地播放，未下载行 → 在线播放**；首行再向上/焦点不在列表内时不拦截走默认焦点 |
| **右** | 行根 → 默认按钮 → 删除键；删除键再右 → 下一行（RecyclerView 默认） |
| **左** | 行内：删除键 → 先下后播 → 在线播放；再左 → 退回行根（不重定向），再按左 → 上一行 |
| **OK** | 焦点在在线播放键 → 播放页(HLS); 先下后播/本地播放 → 下载弹窗或本地播放; 删除键 → 删除确认弹窗; 刷新/设置/搜索键 → 对应页面; 头像 → 关于页 |
| **菜单键** | 片库页与刷新页全局监听 `KeyEvent.KEYCODE_MENU`（=82），直接触发刷新（进刷新页看 log 或原地静默刷新） |
| **返回** | 播放页 2 秒内连按两次返回片库页（第一次弹 Toast「再按一次返回键返回列表」，进度照常落盘；手机版无此拦截） |

**为什么上下键要代码强制路由**：若依赖系统焦点搜索，DOWN 键会被焦点引擎抢先直接落到下一行按钮，行根的"定向默认按钮"逻辑根本来不及生效。因此上下键在 `dispatchKeyEvent` 层整体接管：算出目标行 → `scrollToPosition` → `rv.post` 把焦点交给该行默认按钮（`VideoAdapter.focusPreferred`，post 延后一拍避免与焦点分发竞态）。

**方向键强制路由**：代码中显式设置了 `btnPlay.nextFocusRightId = btnDownloadPlay`、`btnDownloadPlay.nextFocusLeftId = btnPlay`、`btnDownloadPlay.nextFocusRightId = btnDelete`、`btnDelete.nextFocusLeftId = btnDownloadPlay`（三按钮链），避免 Android 默认焦点引擎选错方向。

**行根定向默认按钮**：焦点进入行根时若来源不是本行按钮（`oldFocus.parent !== rowRoot`），经 `VideoAdapter.focusPreferred(rv, pos)` 把焦点交给已下载行的 btnDownloadPlay（"本地播放"）或未下载行的 btnPlay（"在线播放"）；来源是本行按钮（左右键退出按钮区）则留在行根不重定向，避免焦点死循环。

**选中态管理**：
- **行内/行根获得焦点**：`VideoAdapter.setHighlight(rv, pos)` 给该行 `isSelected=true`（黄框+背景），清其他行
- **跳出 RecyclerView**（按 ↑ 到刷新按钮）：ListActivity 的 `OnGlobalFocusChangeListener` 检测 `newFocus` 不在 rvList 内 → `setHighlight(rv, -1)` 清所有行

**弹窗按钮统一**：TV 主题提供 `AlertDlg` 主题——剩余 AlertDialog（下载进度、删除确认、更新确认）的按钮（确定/取消/删除）统一为 `DlgBtn` 样式（见 5.4）。

### 5.4 按钮样式

**弹窗按钮**（仅剩下载弹窗与删除确认弹窗用 AlertDialog）：AlertDialog 按钮样式必须经 **`alertDialogTheme`** 生效（主主题 `buttonBarButtonStyle` 对弹窗无效）——`AlertDlg` 主题（父 `Theme.DeviceDefault.Dialog.Alert`）内设 `buttonBarButtonStyle=@style/DlgBtn`。`DlgBtn` 默认态 `bg_dlg_btn`（深色填充 + 1dp 白色细描边，明确呈按钮而非文本），聚焦/按压态 `btn_focused`（蓝底）。

四个行内按钮（在线播放/先下后播/本地播放/删除）统一：`bg_btn_selector` 背景，`minWidth=0dp minHeight=0dp`（Android Button 默认有 ~48dp minWidth，必须显式设 0 才能缩小），`padding 10dp / 4dp`（横向/纵向），`textSize=14sp`。宽度固定：在线播放/先下后播(本地播放) 84dp（4 字文案），删除 56dp；刷新按钮 `wrap_content`。

标题栏三按钮：刷新 / 设置 / 搜索统一 **84dp 等宽 + gravity 居中**，间距 12dp，同样是 `bg_btn_selector` + `minWidth/minHeight=0dp`。头像图标聚焦态用独立的 `bg_icon_focus`（透明底 + 2dp 黄描边），与按钮的蓝色填充背景区分。

**刷新/设置/搜索/关于四页按钮**：与片库页同一套 `bg_btn_selector` 背景 + `minWidth/minHeight=0dp`（返回 / 刷新 / 保存 / 检查更新等），整应用按钮风格统一；页面默认焦点分别落刷新 / 保存 / 检查更新按钮（搜索页无默认焦点）。

### 5.5 先下后播 / 本地播放

**引擎**：`download/M3u8Downloader.kt`。

- 流程：获取 m3u8（master→子列表）→ **广告检测/去除（PTS 时间轴法，通用唯一算法，`download/AdDetector.kt`，详见 [remove-ad.md](remove-ad.md)）** → 8 线程下载分片（重试、断点续传、AES-128 解密）→ 二进制拼接 TS → 产物 `片名.ts`
- **产物命名**：`Android/data/com.tv.mailvod/files/movies/片名.ts`（片名去首尾空白、非法字符换下划线）；临时分片在 `片名_tmp/`，成功后清理
- **不重封装 MP4**：曾用 MediaExtractor/MediaMuxer 替代 ffmpeg，但慢（2GB 数分钟）且容错差易出半成品，ExoPlayer 原生支持 MPEG-TS，直接播 TS
- **去广告原理（PTS 时间轴法）**：清单以 `#EXT-X-DISCONTINUITY` 把正片切成组，广告以整组硬拼接，其 PTS（展示时间戳）脱离内容时间轴而正片各组严格连续。检测 = HTTP Range 抓每组首分片头部 64KB（8 线程并发、失败重试 3 次），解析首个 PES 的 PTS，沿清单累计时长推进预期起点，命中（容差 2 秒）判正片、脱轨判广告。量子 lz-cdn 与非凡 ffzy 两源实测零误判
- **去广告护栏**（任一超限视为误判，放弃剔除按原样下载）：广告块 ≤5；单块 ≤4 组且 ≤120 秒；广告总时长 ≤15%；扫描失败组按正片保留（部分剔除，宁多勿缺）
- **检测结果缓存**：按清单内容 SHA-256 指纹存 `movies/adcache/ad_指纹前16.json`（广告组号数组），同清单重下免扫描；清单内容变化指纹变，自动失效
- 下载弹窗进度：解析（流动条）→ 检测广告 x/y（命中缓存则瞬间跳过）→ 下载分片 成功x/总数（失败非零时附"失败 y"）→ 拼接 TS（流动条）；取消/返回即 cancel
- **下载失败快速反馈**：分片下载单次请求硬超时 30s、重试 3 次；开局零成功且连败 16 个 → 判定网络不可达自动中止并报错，不再无限重试空耗
- **本地播放失败自动切在线**（兜底）：PlayerActivity 收 `EXTRA_FALLBACK_URL`，本地源报错时自动改播 HLS 在线流
- **删除条目**：删除确认弹窗勾选"同时删除已下载内容"（默认勾选）→ 连带删除 `片名.ts`/残留 `片名.mp4`/`片名_tmp/`
- 教训：重封装失败分支必须删除半成品 MP4，否则下次被当有效文件播放报 source error

### 5.6 播放页控制条

**背景**：ExoPlayer 默认控制条在老电视上渲染不出来（旧系统矢量资源/主题兼容差），表现为"按键功能正常但看不到进度条和播放键"。

**方案**：`activity_player.xml` = FrameLayout = PlayerView（`use_controller=false`）+ 页眉小字 `tv_meta` + 底部自建控制条 `control_bar`（普通 Button/SeekBar/TextView，任何安卓版本可渲染）。

- 组成：播放/暂停按钮 + SeekBar（weight=1）+ 时间文本 `mm:ss / h:mm:ss`；半透明黑底 `#B3000000`
- 显示逻辑：进页先亮 5 秒；任意方向键/OK/暂停时弹出；**播放中 5 秒无按键自动隐藏，用户暂停时常驻**
- **隐藏判据用 `playWhenReady` 而非 `isPlaying`**：左右快进后缓冲期 `isPlaying=false`，若按它判断则此时不排隐藏定时器 → 控制条常驻不消失；`playWhenReady` 在缓冲期仍为 true，只有用户暂停才 false
- **页眉小字**：控制条显示时左上角同步显示「片名 (年份/国家)」（13sp 半透明黑底，随控制条一起隐藏）；元信息经 `EXTRA_META` 传入，`ListActivity.metaOf()` 构建，年份/国家缺项自动省略
- 按键：OK=播放/暂停（拦截于 `dispatchKeyEvent`，不受焦点影响）；左/右=±10s；上/下=仅弹控制条；返回=2 秒内双击防误触
- 所有控件 `focusable=false`，焦点永远不被控制条抢走
- 500ms tick 刷新按钮文字/进度/时间；时长无效（直播流）显示"直播/时长未知"

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
   4) LibraryStore.merge: title 匹配 → 覆盖 / 新增 → 落盘
   │
   5) 刷新列表页显示, Toast "拉取完成, 新增 X 条"
```

**幂等性**：远端清单是只读输入, 每次刷新全量拉取 → 本地 JSON 去重合并。重装 App 后刷新一次即可重建整个库。
**离线可用**：拉取失败仅 Toast 提示, 本地 library.json 不受影响。

---

## 7. 踩过的坑（留底）

| 坑 | 根因 | 解决 |
|---|---|---|
| 163 邮箱 `Unsafe Login`（邮件收片时代） | 163 风控要求 IMAP `ID` 命令，登录后 EXAMINE 前必须发 | 自写 SSLSocket 单连接，LOGIN 后立即发 ID |
| `No field protocol in class IMAPStore`（邮件收片时代） | android-mail 1.6.7 把 protocol 做成了 private，反射取 protocol 字段失败 | 完全抛弃 JavaMail Store/Folder，改用自写 SSLSocket 单连接 FETCH RFC822 |
| `B3 NO EXAMINE`（ID 发了还是失败，邮件收片时代） | JavaMail IMAPStore 有连接池：store 连接和 folder 连接是**两条独立物理 socket**。ID 发给了 store 那条，EXAMINE 走 folder 那条，服务器照样拒 | 自写单连接，全程一条 TCP+TLS |
| 邮件拉取 0 条，FETCH 卡死（邮件收片时代） | 自己解析 FETCH 响应时，`BODY[] {size}` 后面的 literal 是一次性发出的，但用 BufferedReader 逐行读时，literal 内部的 CRLF 被当成换行消费，字节流错位 | 完全抛弃 BufferedReader，改用 ByteArrayOutputStream 累积全部响应，遇到 tag OK 终止，再从原始字节里切出 literal |
| `msg.content` 返回 MIME raw 文本（邮件收片时代） | 某些 multipart 邮件（尤其是 163 网页端发的），JavaMail `msg.content` 返回 `String` 类型的原始 MIME 文本（`--boundary` + `Content-Type:` 头），而非 Multipart 对象 | 强制 `ByteArrayDataSource(msg.inputStream, ct)` → 构造 `MimeMultipart(ds)`，不依赖 msg.content 的类型判断 |
| 手机首装拉取 0 条（邮件收片时代，日志 `SKIP: prefix not match`） | SetupActivity 建的配置落盘了过时默认值主题前缀，过滤不到 `m3u8_view` 主题邮件 | 默认值改回正确前缀；已装设备就地修 config.json；应用内帮助文案同步修正 |
| 按钮 padding 改了尺寸没变化 | Android Button 类有默认 `minWidth=48dp minHeight=48dp`，padding 再小也被撑住 | 显式设 `android:minWidth="0dp" android:minHeight="0dp"` |
| 表头表体列错位 | 表头和表体各自定义列宽，互不相同；表头末尾没给按钮留占位 | 列宽统一到 `VideoAdapter.buildColumnLayoutParams()`；表头末尾加等宽占位 View，精确匹配表体按钮宽度 |
| 焦点跳到外部时旧行黄框不消失 | `setHighlight` 只在 RecyclerView 子 View 焦点变化时触发，焦点跳到刷新按钮时没人清 | ListActivity 挂 `addOnGlobalFocusChangeListener`，检测焦点不在 rvList 内时调 `setHighlight(rv, -1)` |
| 本地视频提示"续播"却从 0 开始 | ExoPlayer `setMediaItem/setMediaSource` 默认 resetPosition，先 seekTo 再设媒体源会丢起始位置 | 顺序改为：设媒体源 → seekTo(断点) → prepare() |
| 快进后播放页控制条常驻不消失 | 快进后缓冲期 `isPlaying=false`，按它判断是否排隐藏定时器，此时不排 → 常驻 | 隐藏判据改用 `playWhenReady`（缓冲期仍为 true，仅用户暂停才 false） |
| 弹窗按钮"看起来像文本" | 只设了主主题 `buttonBarButtonStyle`，但 AlertDialog 实际读取 `alertDialogTheme`，框架默认 dialog 主题将其覆盖为无边框文本样式 | 新增 `AlertDlg` 主题（父 `Theme.DeviceDefault.Dialog.Alert`）显式覆盖，默认态换深色填充+细描边背景 |
| 上下键选行后默认按钮不按预期聚焦 | 依赖系统焦点搜索时 DOWN 键被焦点引擎抢先落位，行根定向逻辑来不及生效 | 上下键在 `dispatchKeyEvent` 层强制接管，代码计算目标行并主动交焦点 |

**163 邮箱的两个已知限制**（邮件收片时代留底）：
1. `SEARCH` 命令对 UTF-8 charset 支持有坑（`SEARCH CHARSET UTF-8 SUBJECT "m3u8_view"` 返回 0）。解决：`SEARCH ALL` 全量拉取后客户端按 subject 过滤，不走 charset SEARCH。
2. IMAP 连接每次新 session 必须发 `ID`，老 session 重连也需要。

---

## 8. 工程结构

```
app/src/
├── main/                          共用(TV/phone 两版本都打包, 只存一份)
│   ├── java/com/tv/mailvod/
│   │   ├── net/LibrarySync.kt         片源地址 GET + 清单 JSON 解析
│   │   ├── download/M3u8Downloader.kt m3u8 解析/分片下载/AES-128/TS 拼接
│   │   ├── download/AdDetector.kt     去广告检测器(PTS 时间轴法, 详见 remove-ad.md)
│   │   ├── download/MovieFiles.kt     movies/ 本地文件管理(片名.ts 命名/旧编号迁移/删除/定位)
│   │   ├── playback/VodPlayer.kt      ExoPlayer 核心(HLS/headers/断点续播/本地兜底)
│   │   ├── store/                     LibraryStore / ProgressStore / VideoItem
│   │   ├── config/                    Config(library_url 默认值) / ConfigLoader
│   │   ├── net/                       TlsCompat / UpdateChecker(双通道版本清单) / AppUpdater(更新流程)
│   │   └── App.kt                     Application 单例
│   ├── res/                           mipmap 桌面图标 + colors + strings(公共)
│   ├── assets/certs/                  ISRG Root X1/X2(TLS 兼容) + config.example.json(仅参考)
│   └── AndroidManifest.xml            权限 + 公共 application + FileProvider
├── tv/                            TV 版专属
│   ├── java/com/tv/mailvod/ui/        ListActivity(遥控器) / VideoAdapter(焦点) /
│   │                                  PlayerActivity(按键壳) / SearchActivity /
│   │                                  RefreshActivity / SettingsActivity / AboutActivity
│   ├── res/                           TV 布局/焦点 drawable/Theme.Leanback 主题/ic_head/ic_banner
│   │                                  + 刷新页遥控器示意图(bg_remote_*)/log 框(bg_log)
│   └── AndroidManifest.xml            leanback + banner + LEANBACK_LAUNCHER + REQUEST_INSTALL_PACKAGES
└── phone/                         手机版专属
    ├── java/com/tv/mailvod/ui/        ListActivity(触屏) / VideoAdapter / PlayerActivity(触控条壳)
    │                                  + RefreshActivity / SettingsActivity / AboutActivity / SearchActivity
    ├── res/                           触屏布局 + Theme.AppCompat 主题 + 措辞覆盖 strings
    └── AndroidManifest.xml            仅 LAUNCHER, 触屏, 无 leanback
```

技术栈：Kotlin + RecyclerView + ExoPlayer 2.18.5 + OkHttp 4.9.3 + kotlinx.serialization。

minSdk 21 / targetSdk 34 / compileSdk 34。双 flavor 构建与产物（debug 签名）：
- `gradle assembleTvDebug` → `app/build/outputs/apk/tv/debug/app-tv-debug.apk`（com.tv.mailvod）
- `gradle assemblePhoneDebug` → `app/build/outputs/apk/phone/debug/app-phone-debug.apk`（com.mailvod.phone）
- leanback 依赖仅 `tvImplementation`（手机包不携带）；versionCode/Name 定义在 build.gradle.kts 的 productFlavors 内，各版本独立演进

---

## 9. 验收要点

| 场景 | 预期 |
|---|---|
| 片源清单 library.json 加一条新片（Gitee 网页端或 push_library.py） | 刷新后列表置顶出现新行，片名/国家/类型/年份/导演正确显示，播放 OK |
| 同 title 改 url 重传清单 | 覆盖 url，**保留原入库时间与位置**，列表不重复 |
| 清单里缺 title 或 url 的条目 | 跳过，日志输出原因，列表无该行 |
| 断网时刷新 | Toast 拉取失败，本地 library.json 与列表不变 |
| 重装 App 后刷新 | 从片源地址全量重建 library.json |
| 已下载条目 | 已下载列显示 ✔，"先下后播"按钮文字变为"本地播放" |
| 上下键在列表内选行 | 焦点落目标行默认按钮：已下载行 → 本地播放，未下载行 → 在线播放 |
| 选中行显示橙色外框 + 深灰背景 | OK |
| 按钮聚焦时行黄框保留 | OK（isSelected=true 不随 focus 丢失） |
| 焦点从第一行按 ↑ 跳到刷新按钮，旧行黄框消失 | OK（全局焦点监听 setHighlight(rv, -1)） |
| 遥控器在某行按右 → 焦点移到播放键；再右 → 删除键；左 → 回行 | OK（方向键强制路由） |
| 删除后再更新清单 | 该片按新片重新入库 |
| 按遥控器「菜单」键（片库页/刷新页） | 直接触发刷新 |
| 刷新页：进入默认焦点在刷新按钮，OK 后 log 逐行显示连接/条数/新增/完毕，左上角返回回片库页 | OK |
| 表头表体列对齐 | OK（共用 buildColumnLayoutParams + 按钮占位） |
| 按钮尺寸缩小 | OK（minWidth=0dp + padding 10/4） |
| 标题行显示 [头像] 松松看片 (共x) v x.y.z + 刷新/设置/搜索 三等宽按钮 | OK |
| 头像透明背景 PNG；遥控器焦点移上出现黄框 | OK（bg_icon_focus 2dp 黄描边） |
| OK 点头像 → 关于页（版本/开发者/已下载统计/操作说明/检查更新，默认焦点在检查更新） | OK |
| 快进后播放页控制条 5 秒内自动隐藏 | OK（playWhenReady 判据） |
| 控制条显示时页眉左上角出现「片名 (年份/国家)」小字 | OK |
| 设置 → 设置页预填 APK 更新地址与片源地址（默认内置，均可在设置页修改），保存后 config.json 更新 | OK（ConfigLoader.save） |
| 搜索 → 进入搜索页；返回按钮 / 遥控器返回键回片库页 | OK（搜索逻辑未实现，点搜索提示开发中） |

---

## 10. 后续可做

- [ ] 自定义按钮聚焦 selector（按钮选中时也有橙色边框，目前是蓝色背景）
- [ ] 空列表时给 btnRefresh 加下一行焦点，让遥控器首次按 OK 就能刷新
- [ ] 开机自启 + 首次启动自动刷新
- [ ] headers 白名单（仅允许 UA/Referer/Origin/Cookie）
- [ ] ConfigLoader 支持通过 adb 覆盖 config.json（目前需要卸载重装才能更新）

### 10.1 删除条目被片源清单刷新回刷（已定方案，暂不实施）

**问题**：删除某电影只操作本地 library.json；片源清单里对应条目仍在，下次刷新重新解析后，本地查无此片 → 按"新片"插回列表。

**已定方案（墓碑清单）**：
- library.json 增加 `deleted` 数组，存被删条目的 key（title，或含 url 增强唯一性）
- `LibraryStore.delete` 删除条目的同时写入墓碑
- `LibraryStore.merge` 在"新片入库"判断之前先查墓碑，命中则跳过（且不重复覆盖墓碑）
- 已知代价：重装 app 后 files 目录清空，墓碑丢失 → 重装全量重建时被删片会回来（家用场景可接受）
- UI 可选增强：设置页显示墓碑清单，支持"恢复"单个条目（移出墓碑）

---

## 11. 手机版（phone flavor）

与 TV 版同源派生，采用单模块 + productFlavors（`device` 维度）而非多模块：
共用代码沉到 `src/main`（片库同步、媒体库、下载、播放核心、配置），版本差异只在 `src/tv` / `src/phone` 的 UI 壳与 manifest。

### 11.1 与 TV 版的差异

| 维度 | TV 版 | 手机版 |
|---|---|---|
| 包名 | com.tv.mailvod | com.mailvod.phone（可共存/并行调试） |
| 版本 | 独立演进（build.gradle.kts productFlavors 内定义） | 独立演进 |
| 入口 | LEANBACK_LAUNCHER + LAUNCHER | 仅 LAUNCHER |
| 主题 | Theme.Leanback 系 | Theme.AppCompat.NoActionBar 系（同深色配色） |
| 播放交互 | 遥控器 OK=播放/暂停直接切换(不弹控制条, dispatchKeyEvent 拦截), 左右 ±10s, 返回=二次确认退出 | ExoPlayer 默认触控条, 默认横屏(sensorLandscape), 返回直接退出 |
| 列表交互 | D-pad 焦点高亮 + 表头表格 | 卡片行(片名大字 20sp 粗体 / 元信息 / 已下载标签) + 按钮行(在线播放/先下后播/删除, 最后一行右对齐) |
| 刷新页 | 遥控器示意图(菜单键黄框高亮) + 刷新按钮(默认焦点) + log 框 | 无示意图, 说明文字为触屏措辞, 其余同构 |
| 关于页 | 点头像图标进入 | 点左上角标题「松松看片」进入(版本/开发者/已下载统计/操作说明/检查更新) |
| 设置页 | 双输入框(activity_settings 布局: APK更新+片源地址) | 同构, 输入框四周描边框状背景 bg_edit_box |
| 自动更新 | 有(REQUEST_INSTALL_PACKAGES) | 有(同权限) |
| 搜索页 | 界面壳已实现 | 界面壳已实现 |

### 11.2 共用抽取（避免两份拷贝）

- `MovieFiles`：movies 目录/已下载集合/本地文件定位与删除
- `VodPlayer`：ExoPlayer 构建/HLS+headers/断点续播(10s 落盘)/本地损坏切在线兜底（TV 只留按键处理，phone 只留生命周期转发）
- `LibrarySync`：片源清单拉取与解析

### 11.3 自动更新（Gitee 双通道）

两版本共用同一个 Gitee 仓库 `unixsam/mailvod-release` 与同一份 `version.json`：
- 顶层 = tv 段（保持旧格式，兼容已装旧包）；`"phone": {...}` 子对象 = 手机版
- `UpdateChecker` 按 channel 取段；检查/下载/安装弹窗逻辑统一在共用 `AppUpdater`
- 发布：`py _tmp\publish_gitee.py --flavor tv|phone`（自动合并另一 flavor 的段；合并基准必须走 Gitee contents API——raw 直链有 CDN 缓存，曾把 phone 段覆盖丢失）
- **端到端实证**：手机 0.1.1→0.1.2、电视 0.7.3→0.7.4 均经 Gitee 自动更新完成（检查→下载→MD5 校验→弹窗→用户确认→系统安装器），双通道互不干扰

### 11.4 phone 版后续可做

- 竖屏海报式列表（当前为信息行式）
- 播放页返回键二次确认（TV 已加，手机暂无需求）
