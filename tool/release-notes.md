<!--
  changelog-exclude: 发版|版本漂移|AGENTS\.md|清理全部历史产物|versionCode|versionName
  changelog-empty: 本次以内部维护为主，无用户可见改动

  上面两行是给 tool/create-release.ps1 读的【改动清单规则】，GitHub 渲染时
  会隐藏 HTML 注释，所以不会出现在 Release 页面上。

  - changelog-exclude: 过滤正则。把【内部工具类提交】(发版脚本、文档维护、
    版本号同步) 从面向用户的改动清单里去掉 —— 用户关心的是 App 改了什么，
    不是我们怎么发版。脚本另有一套按 conventional-commit type/scope 的过滤
    (chore/docs/ci/build/test/style/revert 与 scope=release|docs|tool)。
  - changelog-empty: 清单被过滤空时的兜底文案。

  为什么规则放这里而不是脚本里: .ps1 必须纯 ASCII(PowerShell 5.1 按 GBK 读
  脚本，中文字面量会拆坏字符串终止符)，而这些规则需要中文。本文件是 UTF-8。
-->

## BiliV3 {{TAG}}

`versionName = {{VERSION}}` / `versionCode = {{CODE}}`

第三方 B 站 Android 客户端（**个人自用**）。Kotlin + Jetpack Compose + Media3。

---

### 本次改动

{{CHANGES}}

---

### 安装

1. 下载下方 `bili-v3-{{TAG}}-release.apk`
2. 手机上允许「安装未知来源应用」
3. 安装。若提示签名冲突，先卸载旧版：
   ```
   adb uninstall com.example.biliv3
   ```

> ⚠️ **只发布 release 包，不提供 debug 包。**
> debug 构建 `debuggable=true`，且签名用的是 Android SDK 自带的**公开**调试密钥 ——
> 任何人都能签一个「同包名 + 同证书」的 APK 被设备当作合法升级装上。
> 详见仓库 `AGENTS.md` §6.4.1。

### 签名

| 项 | 值 |
|---|---|
| 证书 | `CN=BiliV3 Release` / RSA 4096 |
| 方案 | v1 + v2 + v3 |
| 有效期 | 30 年 |

### 合规

- 不公开分发、不上架商店、不商业化
- 不解析大会员 / 付费 / 充电专属内容，不绕过任何付费墙
- 不内置去广告、不做批量下载 / 全站爬取
- 登录只用小号；凭据走 Android Keystore 加密

> B 站用户协议禁止未经许可的第三方客户端。本项目仅供个人学习自用。

### 技术说明

架构、设计规范、已实测的技术事实、踩过的坑，全部记录在仓库的
`AGENTS.md`（**本项目唯一的说明文档**）。
