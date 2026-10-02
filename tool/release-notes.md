## BiliV3 {{TAG}}

`versionName = {{VERSION}}` / `versionCode = {{CODE}}`

第三方 B 站 Android 客户端（**个人自用**）。Kotlin + Jetpack Compose + Media3。

---

### 本次改动

> 由 `git log` 自动生成（上一个 tag 到本次），不需要手写。

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
