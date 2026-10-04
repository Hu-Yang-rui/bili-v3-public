<div align="center">

  <img src="assets/hero.svg" alt="BiliV3 — 第三方 B 站 Android 客户端。左侧为字标与四条主张；右侧为精炼播放器视图：16:10 视频面、播放键、细波形、黑胶唱片，以及 00:42 / 24:51 时间轴，状态为 PLAYING · AUDIO · 1080P · H.264" width="100%">

<br>

**A third-party Bilibili Android client — built around video playback, audio experience, content management and plugin extension.**

<p>
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/Hu-Yang-rui/bili-v3-public?style=flat-square&label=release&color=FF8FB0&labelColor=171B22" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-4FD1C5?style=flat-square&labelColor=171B22" alt="Kotlin 2.0.21">
  <img src="https://img.shields.io/badge/Compose-BOM%202024.09-4FD1C5?style=flat-square&labelColor=171B22" alt="Compose BOM 2024.09.02">
  <img src="https://img.shields.io/badge/Media3-1.4.1-4FD1C5?style=flat-square&labelColor=171B22" alt="Media3 1.4.1">
  <img src="https://img.shields.io/badge/minSdk-26-66707F?style=flat-square&labelColor=171B22" alt="minSdk 26">
  <img src="https://img.shields.io/badge/tests-379%20passing-4FD1C5?style=flat-square&labelColor=171B22" alt="379 tests passing">
</p>

<p>
  <a href="../../releases/latest"><b>⬇ Download APK</b></a> ·
  <a href="#-get-started">Get Started</a> ·
  <a href="#-architecture">Architecture</a> ·
  <a href="#-plugin-system">Plugins</a>
</p>

</div>

---

<div align="center">

### 01 ── WHY BILIV3

</div>

第三方 B 站客户端常落进两个极端：一是把官方接口硬套进 Material 默认样板，观感廉价；
二是过度装饰，粉色渐变堆满屏。

BiliV3 走第三条路 —— **冷调深色底 + 明度分层 + 发丝线 + B 站品牌粉 + 极少量技术感**。

|  |  |
|---|---|
| **无卡片** | 分组靠间距、发丝线与明度带，不靠容器套容器 |
| **只做深色** | 静态页背后没有可模糊的内容，玻璃在那里是假的。与其维护两套材质策略，不如把一套做透 |
| **动效只服务反馈** | 每个动画都要能解释它为什么存在 |
| **圆角只给交互元素** | 列表、封面、面板一律直角；只有能点的东西才有 4dp 圆角 |

> 目标不是复刻官方 App，而是把「看视频」这条主链路做到官方级精度。

---

<div align="center">

### 02 ── PLAYER

  <img src="assets/player.svg" alt="统一播放核心：VIDEO STREAM 与 AUDIO STREAM 两条 DASH 流汇聚进 Playback Core（MergingMediaSource · Media3），再输出到 MediaSession、队列与进度" width="100%">

</div>

**统一播放核心**是这一层的关键。播放器、队列、通知栏、锁屏控件读的是**同一个 `StateFlow`** ——
不存在「UI 显示在播、通知栏显示暂停」这类各自维护一份状态的分裂。

| 组件 | 职责 |
|---|---|
| `BiliApi` | WBI 签名、接口收敛（全应用唯一实例，CookieJar 持有登录态） |
| `MergingMediaSource` | B 站 DASH 是**音视频两条独立 URL**，Media3 官方支持合轨，不需要 ffmpeg |
| `PlaybackController` | **状态的唯一写入方**：模式、倍速、定时器、队列推进 |
| `PlaybackQueue` | 纯 Kotlin，不持有 Player —— 因此可单测（26 个用例） |
| `PlaybackProgressStore` | 按 `bvid:cid` 记录，多 P 各自独立 |
| `PlayerHolder` | Activity 级持有 `ExoPlayer`（画中画与切页续播的前提） |

<details>
<summary><b>为什么不需要第二套播放器</b></summary>

<br>

跨进程边界只能传可序列化数据。`MediaController` 是跨进程代理，只能传 `MediaItem`，
传不了 `MergingMediaSource`。所以音频 URL 被编码进 `MediaItem.extras`，
Service 侧用自定义 `MediaSource.Factory` 还原。

遇到「对象传不过去」时，先想「能不能编码成数据 + 在对面还原」，而不是改架构。

</details>

---

<div align="center">

### 03 ── LISTEN MODE

  <img src="assets/listen.svg" alt="听视频模式：VIDEO 转为 AUDIO —— 不装配视频轨，因此不创建视频解码器；右侧列出 VIDEO TRACK OFF / AUDIO TRACK ACTIVE / BACKGROUND ON / MEDIA SESSION ON" width="100%">

</div>

**听视频 ≠ 把画面藏起来。** 区别是实打实的：

| 做法 | 视频解码器 | GPU 合成 | 省电 |
|---|---|---|---|
| 画面透明 / 尺寸设为 0 | **仍在解码** | 仍在合成 | ❌ |
| **不装配视频轨**（本实现） | **完全不创建** | 不参与 | ✅ |

代价：切换模式必须重建 `MediaSource`，因此**必须先记位置、重建后 seek 回去** ——
否则会变成「切模式就从头播」。

配合 `MediaSessionService`，进入听视频后可退到后台继续播放，
锁屏控件与耳机按键通过系统媒体中心接管。

---

<div align="center">

### 04 ── PLAYBACK MEMORY

  <img src="assets/memory.svg" alt="播放记忆：12:34 / 24:51 时间轴与断点；下方为多 P 的独立进度条，以及 bvid·cid / independent / local+server / ≥90% 四项参数" width="100%">

</div>

进度按 **`bvid:cid`** 记录，所以多 P 视频的每一 P 各自独立，不会互相覆盖。

两个阈值是**刻意分开**的，不是同一个数字写了两遍：

| 阈值 | 值 | 用途 |
|---|---|---|
| `MAX_RATIO` | 0.95 | 接近结尾时**不再弹**「继续观看」——否则等于每次都要手动跳过 |
| `COMPLETE_RATIO` | 0.90 | 视为已看完，**不再**出现在「继续观看」列表 |

本地存储负责即时性，服务端 `history/report` 负责跨端同步 —— 两者都要有。

---

<div align="center">

### 05 ── FAVORITES / ORGANIZE

  <img src="assets/organize.svg" alt="收藏整理流程：FAVORITES 124 → FILTER 规则 → CANDIDATES 42 → REVIEW 由人决定；规则只缩小范围，未经确认不会删除" width="100%">

</div>

收藏夹用久了会有大量「当时想回头看、现在完全不记得为什么收藏」的视频。逐个点开判断太慢。

**批量整理**：多选、全选 / 取消全选 / 反选 / 本页全选、批量移动、批量取消收藏、批量加入稍后再看与播放队列。
破坏性操作走确认框，文案带数量。

**快速整理**：由**规则插件**筛出候选并给出命中原因，用户只对候选做决定。

> ⚠️ 这里的定位是「**规则缩小范围 + 人做决定**」，不是全自动整理。
> 收藏是不可撤销的（服务端没有回收站），而规则一定会有误判。

<details>
<summary><b>为什么批量操作不做乐观更新（与单条删除相反）</b></summary>

<br>

单条取消收藏是乐观的（先移除再回滚），因为失败概率低、且用户只关心一条。

批量不同：**部分失败时「哪些成功了」会变得无法判断** —— 乐观移除后失败的条目要放回原位，
而「原位」在并发修改下已经不准了。

所以批量等结果回来再按 `BatchResult` 过滤列表。宁可慢一点，也不能显示错。

</details>

---

<div align="center">

### 06 ── QUEUE

  <img src="assets/queue.svg" alt="播放队列：五行曲目，02 为当前项（粉色标记条 + 局部进度），底部标注 SHUFFLE OFF · REPEAT ALL · DRAG TO REORDER" width="100%">

</div>

队列是**独立模块**，不持有播放器 —— 所以它的逻辑可以脱离 Android 单测。

两个容易写错的边界，都用单测钉死：

- **单曲循环下**，「自然播完」重播本首，而「用户点下一首」**必须换歌** —— 用参数区分两种意图
- **开随机播放时**，当前项必须固定在洗牌序列首位 —— 否则点「随机」的瞬间歌就跳走了

---

<div align="center">

### 07 ── LYRICS

  <img src="assets/lyrics.svg" alt="歌词视图：当前行「此水几时休」居中并加亮，上下相邻行淡出；底部标注 SUBTITLE FIRST · PROVIDER CHAIN · LRC OFFSET 与 NO LYRICS ≠ FAILED" width="100%">

</div>

歌词是**可插拔的 `LyricsProvider` 链式回退**：字幕优先（复用已有的 `SubtitleRepository`），第三方兜底。
不把某个歌词站点写死在 UI 里。

支持 LRC 解析（`[offset:]`、多时间标签、1/2/3 位小数）、自动滚动、点击跳转、用户微调偏移。

<details>
<summary><b>「没有歌词」与「加载失败」是两种状态</b></summary>

<br>

- `NoLyrics`（这首歌确实没有歌词）→ **不给重试按钮**（重试也不会变出来）
- `Failed`（网络 / 解析错误）→ **必须给重试**

链式回退的规则：`Unavailable` 继续试下一个 Provider，`Error` **也继续试**
（字幕源挂了，本地源可能仍可用）；全部失败才报错，且**保留第一个错误**（更接近根因）。

</details>

---

<div align="center">

### 08 ── VINYL

  <img src="assets/vinyl.svg" alt="黑胶唱片模式：大尺寸唱片（细密沟槽与中心标签）配当前曲目与 00:42 / 04:18 时间轴，标注 ROTATION ON PLAY 与 DEVICE TIER AWARE" width="100%">

</div>

黑胶模式把封面放进唱片的中心标签位，盘体用径向渐变做沟槽观感。

**暂停时冻结角度，而不是归零** —— 归零会让人觉得「播放被重置了」。
低端设备（`DeviceTier.Low`）自动关闭旋转。

---

<div align="center">

### 09 ── PLUGIN SYSTEM

  <img src="assets/plugin.svg" alt="插件系统：BUILT-IN / JSON RULE / NATIVE / EXTERNAL 四类插件汇入单一 PluginContext，右侧为 PERMISSION / SANDBOX / PREVIEW / ISOLATION 四项约束" width="100%">

</div>

四种插件类型，能力与隔离级别不同：

| 类型 | 形态 | 约束 |
|---|---|---|
| **Built-in** | 编译进 App | 无额外权限 |
| **JSON Rule** | 固定枚举的沙箱解释器 | **不执行任意代码**；无循环、无函数调用；正则与输入都有长度上限（防灾难性回溯） |
| **Native** | Kotlin API | 能力经 `PluginContext` 收敛 |
| **External Package** | `.bvplugin` 包 | 只解析预览、**不解压落盘**（天然免疫 Zip Slip）；限制条目数与单文件大小（防 zip bomb） |

### 三条安全设计

**1. 权限是唯一入口。** `PluginContext` 的每个方法先查权限，编译期就挡住「偷偷拿 Repository / Cookie」，
而不是靠自觉。`SESSDATA` / `bili_jct` / keystore **永不进入插件**。

**2. 外部包先预览再安装。** 扫描 → 解析 → 展示权限清单与风险等级 → 兼容性检查 → **用户确认** → 安装。
包内文件**只解析不落盘**。

**3. 插件异常必须隔离。** 捕获 `Throwable`（因为可能是 `StackOverflowError`），
连续 3 次失败自动禁用。

<details>
<summary><b>弹幕 Hook 抛异常 ≠ 屏蔽弹幕</b></summary>

<br>

两者在返回值上都是「没有文本」，但语义相反：

| | 含义 | 正确行为 |
|---|---|---|
| Hook **返回 null** | 插件**明确**要屏蔽 | 丢弃该弹幕 |
| Hook **抛异常** | 插件**坏了** | **保留原弹幕** + 记录错误 |

混为一谈的后果：插件写错一个空指针 → **整个视频一条弹幕都没有**。

</details>

---

<div align="center">

### 10 ── ARCHITECTURE

  <img src="assets/arch.svg" alt="架构：主干 UI → ViewModel → Repository → BiliApi，经一条汇流线挂载 Media3 / MediaSession / PlaybackController / PlaybackQueue / PlaybackProgress / Lyrics / Plugin Runtime；右侧列出刻意的架构取舍" width="100%">

</div>

```
UI (Compose) → ViewModel (StateFlow) → Repository → ApiClient
                                                        ├ Wbi（签名）
                                                        ├ Endpoints
                                                        └ 容错 JSON 解析
```

**把易变的收敛到 3 个文件**：`Endpoints.kt` / `Wbi.kt` / `BiliApi.kt`。

### 刻意的架构取舍

| 决策 | 理由 |
|---|---|
| **手写 `AppContainer`**，不引入 Hilt | 单用户项目，Hilt 会显著拖慢构建（已有 KSP） |
| **手工容错 JSON**，不用 Retrofit / Moshi | B 站字段类型极不稳定（同字段可能是 Int / String / 缺失），强类型反序列化会整页崩 |
| **手写 protobuf** | 省 2–3 MB，且只需要读几个固定结构 |
| **只做深色主题** | 见 [WHY BILIV3](#01--why-biliv3) |
| **无卡片** | 卡片 = 容器套容器 = 臃肿 |

### 技术栈

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 / UI | Kotlin + Jetpack Compose + Material 3 | 2.0.21 / BOM 2024.09.02 |
| 播放器 | AndroidX Media3（ExoPlayer + DASH + MediaSession） | 1.4.1 |
| 网络 / 图片 | OkHttp + 手工容错 JSON 解析 / Coil | 4.12.0 / 2.7.0 |
| 导航 / 存储 | navigation-compose / DataStore + EncryptedSharedPreferences | 2.8.4 / 1.1.1 · 1.1.0-alpha06 |
| 构建 / 测试 | AGP + Gradle + KSP / JUnit4 + Truth + coroutines-test | 8.7.3 / 8.9 / 2.0.21-1.0.28 |

**规模**：155 个 `.kt`（main）+ 20 个 `.kt` 测试（**379 个用例**）。

---

<div align="center">

### 11 ── DESIGN PHILOSOPHY

</div>

> **冷调深色底 + 明度分层 + 发丝线分隔 + B 站品牌粉 + 极少量技术感。**
> 像「一个认真做过的现代客户端」，不像「粉色少女 App」，
> 也不像「黑底亮绿字的终端模拟器」，**更不像「一屏十几个盒子的仪表盘」**。

### 深色下的分层只能靠这三样

深色下**投影不可见**，所以 `Modifier.shadow()` 是白费一次离屏合成。分层改用：

| 手段 | 用途 |
|---|---|
| `ruleTop` / `ruleBottom` / `ruleStart` | 发丝线（1dp，8% 白）—— 硬边界 |
| `band(BandLevel.*)` | 明度带 —— 替代卡片底的**主要手段** |
| `emboss()` | 压印（上暗下亮各 1px）—— 深色下唯一能做出「内凹」的手段 |

### 玻璃的边界

玻璃**不是默认材质**，只在「背后确实有画面可糊、且不需要精确点击」时使用。

| 场景 | 允许玻璃？ |
|---|---|
| 视频画面之上的展示浮层 | ✅ 全项目**仅 1 处** |
| 播放器工具层（齿轮 / 清晰度 / 倍速 / 字幕 / 弹幕） | ❌ 一律实体面板 |
| Dialog / 菜单 / 列表 / 顶栏 / 底栏 / Tab | ❌ 一律实体 |

> **高级 UI 不等于玻璃 UI。** 真正高级的设计，是知道什么地方应该展示材质、什么地方应该让材质消失。
> 工具层要的是「快速点中」，用玻璃是负优化。

### 极客元素的边界

等宽字体只用于**系统状态语境**：时间轴、时长、技术参数、状态行。总面积 ≤ 一屏 5%。

**明确不做**：代码雨 / 扫描线 / CRT / glitch / 大面积霓虹 / 廉价渐变 / 全屏氛围光斑 / emoji 当结构图标。

---

<div align="center">

### 12 ── SECURITY

</div>

| 项 | 做法 |
|---|---|
| **登录凭据** | `SESSDATA` 走 Android Keystore 加密（`EncryptedSharedPreferences`） |
| **第三方站点** | aicu.cc 使用**独立 OkHttpClient，不挂 CookieJar** —— 共享 client 等于把 B 站 Cookie 明文发给第三方 |
| **密钥** | `keystore/` 与 `keystore.properties` 永不入库；本仓库为 public |
| **发布包** | **只发 release APK**，永不发布 debug APK（见下） |
| **插件** | Cookie 永不进入插件；权限经 `PluginContext` 收敛 |

<details>
<summary><b>为什么绝不发布 debug APK</b></summary>

<br>

两个独立的实质风险：

1. **`debuggable=true`** —— 任何拿到该 APK 的人都能用 `adb run-as` 读取应用私有数据
   （包括登录凭据的加密存储）
2. **签名用的是公开密钥** —— `CN=Android Debug` 是 Android SDK 自带的**公开**调试密钥。
   任何人都能签一个「同包名 + 同证书」的 APK，设备会把它当作**合法升级**装上

第 2 条尤其危险：它不依赖设备被 root，也不依赖用户主动信任 ——
「同包名 + 同签名」就是 Android 的升级判据本身。

</details>

### 合规边界

- ❌ 不解析大会员 / 付费 / 充电专属内容，不绕过任何付费墙
- ❌ 不内置去广告，不做批量下载 / 全站爬取
- ❌ 不公开分发、不上架商店、不商业化
- ✅ 登录只用小号；第三方数据（[aicu.cc](https://www.aicu.cc/)）在页面明确标注来源，不冒充官方数据
- ✅ 不使用 B 站 Logo / 商标 / 专有图标

> B 站用户协议禁止未经许可的第三方客户端。本项目仅供个人学习自用。

---

<div align="center">

### 13 ── RELEASE

<p>
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/Hu-Yang-rui/bili-v3-public?style=for-the-badge&label=DOWNLOAD%20APK&color=FF8FB0&labelColor=171B22" alt="Download the latest release APK"></a>
</p>

**`bili-v3-v1.4.1-release.apk`** · 3.42 MB · `versionCode 32`

</div>

| 项 | 值 |
|---|---|
| 证书 | `CN=BiliV3 Release` / RSA 4096 |
| 签名方案 | v1 + v2 + v3 |
| 有效期 | 30 年 |
| SHA-256 | `FF88D3B71FBE9A8BD64A66B1F6DD821043CDD84037C830112A9424E366E6D98A` |

> ⚠️ **只提供 release 包。** debug 包不安全（原因见 [SECURITY](#12--security)），因此不发布。

---

<div align="center">

### 14 ── GET STARTED

</div>

### 安装

1. 从 [Releases](../../releases/latest) 下载 `bili-v3-<tag>-release.apk`
2. 手机上允许「安装未知来源应用」
3. 安装

若提示签名冲突，先卸载旧版：

```bash
adb uninstall com.example.biliv3
```

### 构建

```bash
# 环境：JDK 17+ / Android SDK 35 / Gradle 8.9
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk        # ~23 MB，debug 证书
app/build/outputs/apk/release/app-release.apk    # ~3.4 MB，R8 + 资源压缩
```

`local.properties` 需自行创建（已 gitignore）：

```properties
sdk.dir=/path/to/android-sdk
```

`keystore.properties`（release 签名凭据，**不入库**，需自行备份恢复）：

```properties
storeFile=keystore/biliv3-release.jks
storePassword=***
keyAlias=biliv3
keyPassword=***
```

缺失时自动回退到环境变量 `BILIV3_*`；都没有则 release 不打签名（不报错）。

### 验证签名

```powershell
powershell -ExecutionPolicy Bypass -File tool\verify-signature.ps1
```

---

<div align="center">

### 15 ── 功能总览

</div>

| 模块 | 能力 |
|---|---|
| **首页** | 推荐流（WBI 签名）、Banner 轮播、12 个分区入口、侧栏「正在直播」 |
| **视频详情** | DASH 音视频分离、分 P 切换、清晰度 / 倍速、续播提示 |
| **播放器** | 统一播放核心、播放队列、睡眠定时、画中画、后台播放、媒体会话 |
| **听视频 / 黑胶** | 不装配视频轨的纯音频模式；旋转唱片 |
| **歌词** | 可插拔 Provider 链式回退、LRC 解析、自动滚动、偏移微调 |
| **弹幕** | 自绘渲染层、protobuf 解析、本地屏蔽（类型 + 关键词） |
| **收藏 / 整理** | 批量多选操作、规则插件驱动的快速整理、本地标签 |
| **评论** | 游标分页、楼中楼、发送、举报、IP 属地 |
| **搜索 / 分区 / 榜单** | 热搜、结果高亮清洗；12 分区最新 / 热门两档 |
| **番剧** | 索引、详情、选集、追番 |
| **用户主页 / 动态** | 资料卡、投稿列表、关注取关；关注动态、空间动态 |
| **账号 / 个人** | 扫码登录、多账号切换、历史 / 稍后再看、离线缓存（断点续传）、私信 |
| **插件** | 内置 / JSON 规则 / 原生 / 外部包四类，权限收敛，异常隔离 |

---

<div align="center">

### 16 ── 更多

</div>

- **完整技术约定与踩坑记录**：见 [`AGENTS.md`](AGENTS.md)（本项目唯一的内部说明文档）
- **许可证**：[MIT](LICENSE)
- **免责声明**：本项目为个人学习自用，与哔哩哔哩官方无关，不使用其商标与专有资源。
  请自行承担使用风险，并遵守当地法律与平台协议。

<div align="center">
<br>
<sub>Built with Kotlin · Jetpack Compose · Media3</sub>
</div>
