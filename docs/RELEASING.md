# 发布

推送到 `main` 会自动构建并发布一个预发布（pre-release）；打 `v*` 标签才是正式发布。
GitHub 的「Latest release」徽章只认后者。

## 首次需要配置的 Secrets

到仓库的 **Settings → Secrets and variables → Actions** 里加这四个：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 签名密钥文件的 base64。生成方法见下 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码（多数情况下与密钥库密码相同） |

没有这四个的话 workflow 仍会跑完，但产出的 APK 未签名、**装不上**。

### 生成密钥

```powershell
# 只做一次。丢了这个文件，用户就无法覆盖安装后续版本，只能先卸载。
keytool -genkeypair -v `
  -keystore release.jks `
  -alias niceproxy `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <密钥库密码> -keypass <密钥密码>
```

然后编码成 Secret：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))
```

把输出整段贴进 `KEYSTORE_BASE64`。**原文件自己另存一份**，GitHub Secrets 只能覆写不能读出。

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
