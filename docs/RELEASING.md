# 发布

推送到 `main` 会自动构建并发布一个预发布（pre-release）；打 `v*` 标签才是正式发布。
GitHub 的「Latest release」徽章只认后者。

## 首次需要配置的 Secrets

**这是发布前的必做项。** 缺了它们，workflow 会照常构建并把 APK 传成 artifact，但**不创建 Release**，并以红叉结束——未签名的 APK 根本装不上，发出去只会让人白下载几十 MB。

### 为什么签名密钥不能写进 workflow

本仓库是公开的，`.github/` 下的一切所有人都能读。而 Android 的更新机制只认签名不认来源：拿到私钥的人可以签出一个与你签名一致的包，系统会把它当成合法更新装上去。所以私钥只能作为 Secret 存在，且必须由仓库所有者手动创建一次。

workflow 里的签名**流程**是完整的——还原密钥 → Gradle 签名 → `apksigner verify` 断言真的签上了——缺的只是这份密钥材料本身。

### 1. 生成密钥

```powershell
pwsh ./scripts/new-signing-key.ps1
```

生成 RSA 4096、有效期 10000 天的 `release.jks` 和一个随机口令，并把要填的四项写进 `SIGNING-SECRETS.txt`。两个文件都已被 `.gitignore` 忽略。

口令是随机的而不是让你自己想：这个密钥库只由 CI 使用，没有人需要记住它，那就没有理由用弱口令。

> **密钥库丢失无法补救。** 丢了之后已安装的用户再也收不到更新，只能卸载重装、配置全部丢失。请把 `release.jks` 和口令离线备份好。GitHub Secrets 只能覆写不能读出，不要指望从那里找回。

### 2. 填进 Secrets

到 **Settings → Secrets and variables → Actions**，按 `SIGNING-SECRETS.txt` 里的四段分别创建：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库的 base64 |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | 密钥别名，默认 `niceproxy` |
| `KEY_PASSWORD` | 密钥口令，与库口令相同 |

### 3. 删掉中间文件

填完后删除 `SIGNING-SECRETS.txt`——它里面是私钥明文。`release.jks` 留作备份，但别提交。

本地调试签名时，在仓库根目录放一个 `keystore.properties`（已在 `.gitignore` 中）：

```
storeFile=release.jks
storePassword=...
keyAlias=niceproxy
keyPassword=...
```

`storeFile` 相对于仓库根目录。

## 版本号怎么走

单一来源是 `gradle.properties` 里的 `niceproxy.versionName`（现在是 `0.1.0`）。

| 场景 | versionName | versionCode |
| --- | --- | --- |
| 本地 `assembleRelease` | `0.1.0-dev` | 1 |
| 推送到 `main` | `0.1.0.<构建号>`，例如 `0.1.0.42` | GitHub Actions 的运行序号 |
| 打 `v1.0.0` 标签 | `1.0.0` | 同上 |

`versionCode` 必须单调递增，否则用户装不上新版。用运行序号而不是提交数：后者会因 rebase 而减少。

发新的主/次版本时改 `gradle.properties`，不要去动 `app/build.gradle.kts`。想跳过某次构建，在提交信息里写 `[skip ci]`。

## 为什么 CI 要自己编 libnice.aar

这个文件约 31 MB，是 gomobile 把整个 sing-box 编成三个 ABI 的产物，因此没有入库（见 `.gitignore`）。CI 用与开发机同一份 `native/libnice/build.ps1` 构建，并按 Go 源码哈希缓存：绝大多数只动 Kotlin 的提交会命中缓存，跳过十几到几十分钟的 gomobile。

换 Go 版本、NDK 版本或 `native/libnice/` 下任何文件都会让缓存失效并重编。
