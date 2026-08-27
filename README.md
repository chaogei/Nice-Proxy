# Nice-Proxy

把 Android 设备变成一台**局域网多协议代理网关**。

局域网内的电脑、游戏机、智能电视把它当作普通的 HTTP/SOCKS 代理使用，而 Nice-Proxy 在出站侧可以把这些流量转发到 VMess / VLESS / Trojan / Shadowsocks / Hysteria2 等上游节点，并按规则分流。

**不需要 Root，也不需要 VPN 权限。**

---

## 它解决什么问题

`Every Proxy` 这类应用能把手机变成代理服务器，但按其官方 FAQ 的说法，它是 "a Server, not a Client"，**无法接入上游代理**。而 v2rayNG、sing-box for Android 这类客户端支持全套现代协议，却是给本机用的 VPN，不能让局域网里的其他设备使用。

Nice-Proxy 把这两件事合到一起：

| | Every Proxy | v2rayNG | **Nice-Proxy** |
| --- | --- | --- | --- |
| 局域网 HTTP/SOCKS 服务器 | 支持 | 不支持 | **支持** |
| 上游 VMess/VLESS/Hysteria2 等 | **不支持** | 支持 | **支持** |
| 规则分流 / 订阅管理 | 不支持 | 支持 | **支持** |
| 需要 VPN 权限 | 否 | 是 | **否** |

典型用法：Switch / PS5 / Apple TV 这类无法安装代理软件的设备，在网络设置里把代理指向手机的 IP:8080 即可。

> **注意**：因为不使用 VpnService，**本机 App 的流量不会被代理**（除非该 App 自己支持配置 HTTP 代理）。这是产品定位决定的取舍，详见 [设计文档 §1.3](docs/DESIGN.md)。

---

## 当前状态

功能已基本完整（M0–M4），核心链路经过真机验证。

| 能力 | 状态 |
| --- | --- |
| 局域网 HTTP / SOCKS4 / SOCKS4a / SOCKS5 服务器 | 完成 |
| 单端口混合协议（`mixed`） | 完成 |
| 用户名密码认证 | 完成 |
| 监听地址自动发现（Wi-Fi / 热点 / 蜂窝 / USB 共享） | 完成 |
| 出站协议：SS / VMess / VLESS / Trojan / Hysteria2 / TUIC / AnyTLS / SOCKS / HTTP | 完成 |
| 传输层：ws / grpc / http / httpupgrade / quic | 完成 |
| REALITY / uTLS 指纹 / ECH / 多路复用 | 完成 |
| 分享链接导入（9 类协议） | 完成 |
| 订阅（Base64 / 明文 / Clash YAML / sing-box JSON / SIP008） | 完成 |
| 订阅流量与到期展示 | 完成 |
| 节点延迟测速、自动选优 | 完成 |
| 节点热切换（不重启内核、不断开连接） | 完成 |
| 路由分流 + 规则集 + 四种预设模板 | 完成 |
| **按客户端来源 IP 分流**（网关形态独有） | 完成 |
| 连接监控 / 实时日志 / 流量统计 | 完成 |
| 出站网卡绑定、DNS 分流 | 完成 |
| 毛玻璃 UI | 完成 |
| **TCPing 测速**（不需启动内核） | 完成 |
| 节点搜索、按延迟排序、删除重复、删除无效 | 完成 |
| 订阅备注正则过滤、自定义请求头、后台自动更新 | 完成 |
| 规则锁定（套用模板时保留自定义规则） | 完成 |
| 节点分享：导出链接 + 二维码 | 完成 |
| 二维码扫码导入（相机 + 相册选图） | 完成 |
| 快捷设置磁贴、开机自启、桌面长按快捷方式 | 完成 |
| **PAC 服务器**（按客户端来源地址动态生成脚本） | 完成 |
| 配置备份与恢复（AES-GCM 加密） | 完成 |
| 凭据落库加密（Android Keystore + AES-GCM），密钥失效时引导重新导入 | 完成 |
| 配置变更检测：只在真正影响内核的字段变了才提示应用 | 完成 |
| 省电模式（绑定的 IP 消失时自动停止）、切网后 QUIC 自愈 | 完成 |
| **后台保活四层防御**（详见下方） | 完成 |

### 关于保活

这个应用一旦停掉，全屋指着它上网的设备会一起断网，而用户往往很久才发现，所以保活按「从最快恢复到最兜底」分了四层：

| 层 | 应对的死法 | 恢复延迟 |
| --- | --- | --- |
| 内核健康检查 | 内核自己 panic / OOM 退出，进程还活着——**界面上完全看不出异常**，这是最难察觉的一种 | ≤ 10 秒 |
| 退避重试 | 启动失败（地址未就绪、端口未释放），1→2→4→8→16→30 秒共 6 次 | 1 秒起 |
| START_STICKY + WorkManager 看门狗 | 进程被系统或国产 ROM 回收 | 秒级 ~ 15 分钟 |
| 冷启动补拉 | 看门狗也被冻结时，用户下次打开应用自动恢复 | 下次开应用 |

**请务必在设置里关闭本应用的电池优化。** 它是整条链路的枢纽，同时解决两件事：Doze 会挂起网络访问（前台服务挡不住）；Android 12+ 禁止后台启动前台服务，而「用户关闭了电池优化」是官方豁免项之一——不拿到它，看门狗每次都会被系统拦下。真被拦下时应用会推一条通知说明原因，而不是让你面对「所有设备莫名断网且再也不会好」。

国产 ROM（MIUI / EMUI / ColorOS / OriginOS / Flyme）另有一套独立的自启动白名单，不加进去前台服务照样被清理。设置页里的「厂商后台限制」会按机型直达对应设置页，覆盖 15 家厂商、每家多个候选组件依次尝试，全部失效时退到手机管家首页。

> 一个反直觉的坑：`WIFI_MODE_FULL_HIGH_PERF` 自 Android 14 起被系统自动替换为 `WIFI_MODE_FULL_LOW_LATENCY`，而后者**只在屏幕亮着时生效**。也就是说「保持 Wi-Fi 唤醒」在 Android 14+ 上息屏即失效，这不是本项目的实现问题，而是系统行为——那种情况下只能靠前台服务加电池优化白名单。

真机验证（雷电模拟器 14，Android 14 / API 34 / x86_64）：

| 验证项 | 结果 |
| --- | --- |
| `mixed` 入站监听 `0.0.0.0:8080` | 通过 |
| 经 HTTP CONNECT 访问 HTTPS | 204 |
| 经明文 HTTP 访问 | 200 / 204 |
| **同一端口上的 SOCKS5** | **204** —— 协议自动协商生效 |
| 前台服务类型 `specialUse` | 通过（`types=40000000`） |
| 常驻通知 | 通过 |

> 上述验证是在 M2 之前完成的。M2–M4 的功能（节点、订阅、分流、监控、毛玻璃 UI）**代码与单元测试完备，但尚未上真机回归**。

质量状况：

```
Kotlin 单元测试   255 通过 / 0 失败
Go 内核契约测试   全部通过
Android 全量构建  BUILD SUCCESSFUL（debug + release，R8 无缺失类）
Lint             无告警
源码规模         主源码 91 文件 / 13.7k 行，测试 25 文件 / 4.3k 行
```

## 与 v2rayNG 的差异

v2rayNG 是本机 VPN 客户端，我们是局域网网关，定位不同。有一条值得单独说：

v2rayNG 有个功能叫 **"Share VPN with LAN / tethered devices (root users)"** —— 把流量分享给局域网和 USB 共享的设备**需要 root**。而这正是 Nice-Proxy 的原生形态，不需要 root，也不需要 VPN 权限。

反过来，以下 v2rayNG 的功能我们**不会做**，因为它们都依赖 `VpnService`，与「不索取 VPN 权限」的定位直接冲突：分应用代理、VPN 接口地址/MTU、FakeDNS、透明代理。Xray 专属的 Fragment 分片 sing-box 也没有对应能力。

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| 代理内核 | [sing-box](https://github.com/SagerNet/sing-box) v1.13.19（GPL-3.0） |
| 内核绑定 | gomobile（SagerNet fork）→ `libnice.aar` |
| UI | Kotlin 2.3 + Jetpack Compose + Material 3 |
| 构建 | AGP 9.3.2 / Gradle 9.7.1 / JDK 17 / compileSdk 37 |
| 最低系统 | Android 7.0（API 24） |

选型论证见 [设计文档 §4](docs/DESIGN.md)。

> **AGP 9 注意**：AGP 9 内置了 Kotlin 支持，Android 模块**不再应用 `kotlin-android` 插件**，也不写 `kotlin { jvmToolchain() }`，JVM 目标由 `compileOptions` 指定。纯 JVM 模块（`core:model` / `common` / `config`）仍使用 `org.jetbrains.kotlin.jvm`，不受影响。

---

## 工程结构

```
Nice-Proxy/
├── app/                    Android 应用：UI、导航、DI
├── core/
│   ├── model/              纯 Kotlin 领域模型（无 Android 依赖）
│   ├── common/             工具与协程调度
│   ├── config/             sing-box 配置生成 + 分享链接/订阅解析（纯 Kotlin）
│   ├── database/           Room
│   ├── datastore/          设置持久化
│   ├── network/            OkHttp、订阅拉取、Clash API 客户端
│   ├── data/               Repository
│   ├── service/            前台服务、网络绑定、PAC 服务器
│   └── designsystem/       主题与通用组件
├── native/libnice/         Go 模块 → gomobile → libnice.aar
└── docs/DESIGN.md          设计文档
```

`core:model` 与 `core:config` 是纯 JVM 模块，不依赖 Android SDK，可以用普通 JUnit 秒级验证 —— 配置生成的正确性是整个项目最关键也最容易出错的部分，这样安排让它能被快速、密集地测试。

---

## 开发环境搭建

### 1. JDK 17

AGP 8.13 要求 JDK 17。免安装做法是下载解压版：

```powershell
# 清华镜像，约 182 MB
curl.exe -sSL -o "$env:TEMP\jdk17.zip" `
  "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20_8.zip"
Expand-Archive "$env:TEMP\jdk17.zip" -DestinationPath "$env:USERPROFILE\.jdks" -Force
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-17.0.20+8"
```

> 同一个文件从 GitHub Releases 下载在国内实测约 1.5 MB/min，从清华镜像 9 秒完成。

### 1.1 Gradle 发行版

`gradlew` 首次运行会从 `services.gradle.org` 下载发行版，国内同样很慢。可以先从腾讯镜像下好再填进 wrapper 缓存：

```powershell
$ver = "9.7.1"
curl.exe -sSL -o "$env:TEMP\gradle.zip" "https://mirrors.cloud.tencent.com/gradle/gradle-$ver-bin.zip"
.\gradlew --version          # 让它创建缓存目录后按 Ctrl+C 中断
$d = (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-$ver-bin" | Select-Object -First 1).FullName
Remove-Item "$d\*.part","$d\*.lck" -Force -ErrorAction SilentlyContinue
Copy-Item "$env:TEMP\gradle.zip" "$d\gradle-$ver-bin.zip" -Force
Expand-Archive "$d\gradle-$ver-bin.zip" -DestinationPath $d -Force
New-Item -ItemType File -Path "$d\gradle-$ver-bin.zip.ok" -Force
```

仓库里的 `distributionUrl` 保持官方地址不变，以免影响 CI 与其他贡献者。

### 2. Android SDK / NDK

需要 Platform 37、Build-Tools 37 和 **NDK r28 或更高**（Android 15 的 16 KB 内存页要求）。

下载 [command-line tools](https://developer.android.com/studio#command-tools) 解压到 `<sdk>/cmdline-tools/latest/`，然后：

```powershell
# cmdline-tools 23.0 起 sdkmanager 已弃用，改用 android CLI，包 ID 分隔符是 / 不是 ;
# 它的进度条输出量极大，务必重定向到文件，否则会把 PowerShell 进程撑崩
$cli = "<sdk>\cmdline-tools\latest\bin\android.exe"
Start-Process $cli -ArgumentList 'sdk','install','platform-tools','platforms/android-37.0','build-tools/37.0.0','ndk/28.2.13676358' `
  -NoNewWindow -Wait -RedirectStandardOutput "$env:TEMP\sdk.log" -RedirectStandardError "$env:TEMP\sdk.err"
```

`dl.google.com` 在国内速度很好（147 MB 用时约 5 秒），不需要镜像。

安装后在项目根目录创建 `local.properties`：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

并设置 `ANDROID_NDK_HOME` 指向 NDK 目录。

> `ANDROID_HOME` 应指向 SDK **根目录**，不是 `platform-tools` 子目录。

### 3. Go 1.24+

用于构建内核绑定。国内建议配置代理：

```powershell
go env -w GOPROXY=https://goproxy.cn,direct
```

### 4. Maven 依赖加速（可选）

在 `%USERPROFILE%\.gradle\init.d\` 下放一个 `mirror.gradle`：

```groovy
beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        gradlePluginPortal(); google(); mavenCentral()
    }
    settings.dependencyResolutionManagement.repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google(); mavenCentral()
    }
}
```

---

## 发布

推送到 `main` 会自动构建并发布一个预发布；打 `v*` 标签才是正式版。
按手机架构下载对应 APK（不确定就选 `arm64-v8a`）。

首次需要在仓库 Secrets 里配置签名密钥，否则产出的包未签名、装不上。详见 [docs/RELEASING.md](docs/RELEASING.md)。

---

## 构建

### 运行配置生成器的测试

不需要 Android SDK，几秒完成：

```powershell
.\gradlew :core:config:test
```

生成器的输出与 `core/config/src/test/resources/golden/full-config.json` 做逐字符比对。
确需变更输出时：

```powershell
.\gradlew :core:config:test -Dgolden.update=true
```

然后**人工 review golden 文件的 diff** 再提交。

### 构建内核 AAR

先设置三个环境变量：

```powershell
$env:JAVA_HOME        = "C:\Users\<你>\.jdks\jdk-17.0.20+8"
$env:ANDROID_HOME     = "F:\Android-build\Sdk"
$env:ANDROID_NDK_HOME = "F:\Android-build\Sdk\ndk\28.2.13676358"
```

然后：

```powershell
cd native\libnice
.\build.ps1              # 三个 ABI，约 80 秒
.\build.ps1 -Abis arm64  # 仅 arm64，约 15 秒，调试时更快
```

产物输出到 `app/libs/libnice.aar`，脚本会自动校验 64 位原生库的 16 KB 页对齐。

构建标签的取舍见脚本内注释与 [设计文档 §4.3](docs/DESIGN.md)。有三个坑值得单独提一句：

- **`with_ech` 必须禁用**。sing-box 1.13 起 ECH 已迁移到 Go 标准库，该标签被废弃，显式传入会直接触发编译错误。
- **`badlinkname` 和 `tfogo_checklinkname0` 必须启用**，ldflags 还要加 `-checklinkname=0`。sing-box 大量使用 `go:linkname` 访问运行时内部符号，缺了就链接失败。
- **`with_grpc` 不加反而更好**。不加时用的是 sing-box 自带的轻量 gRPC 实现，体积更小。

另外脚本会设置 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`：gomobile 把 Go 的文档注释原样搬进生成的 Java 文件再交给 javac，而 javac 的默认源码编码在中文 Windows 上是 GBK，注释里有中文就会编译失败。

### 内核契约测试

配置生成器是照着 sing-box 文档手写的，与内核之间没有编译期约束——内核废弃一个字段，生成器毫无感知，问题要到用户点「启动」时才暴露。这个测试把 golden 快照直接喂给内核解析：

```powershell
cd native\libnice
go test -tags="with_quic,with_utls,with_wireguard,with_clash_api,badlinkname,tfogo_checklinkname0" `
        -ldflags="-X github.com/sagernet/sing-box/constant.Version=1.13.19 -checklinkname=0" `
        -v ./...
```

**升级 sing-box 版本后务必先跑这个。**

需要注意它的边界：`ValidateConfig` 走的是内核的组件装配，能抓到「REALITY 公钥格式非法」「Shadowsocks 加密方式不存在」这类问题，但抓不到只在 `Start()` 阶段才暴露的错误（比如 DNS `detour` 到空 direct 出站）。后者由 `core:config` 的生成器约束测试守护。

### 构建 APK

```powershell
.\gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/`，按 ABI 拆分。单 ABI 约 48–51 MB（debug 未混淆），universal 约 107 MB。

---

## 已知环境陷阱

### 工程路径不要包含中文

本仓库位于 `F:\Trace\GitHub源码\Nice-Proxy`，为此踩了两次：

1. **测试类全部 `ClassNotFoundException`**（编译成功、`.class` 正常产出）。Windows 中文环境下 `sun.jnu.encoding` 固定为 GBK 且无法用 `-D` 覆盖，一旦单独把 `file.encoding` 设成 UTF-8，Gradle 写测试 worker classpath 与 worker 解析路径就用了不同编码。处置：`gradle.properties` 中不设置 `file.encoding`。
2. **AGP 直接拒绝构建**："Your project path contains non-ASCII characters"。处置：`android.overridePathCheck=true`。

实测两个绕过之后 AAPT2、R8、gomobile 都能正常工作，但这只是关掉了检查。**仍建议迁到纯 ASCII 路径。**

### PowerShell 的 `-D` 参数要加引号

`.\gradlew :core:config:test -Dgolden.update=true` 会被 PowerShell 拆坏，报 "Selection failed"。写成 `"-Dgolden.update=true"`。

### 明文 HTTP 与 Clash API

Clash API 只能是 `127.0.0.1` 上的明文 HTTP，而 targetSdk 28+ 默认禁止明文流量、**回环也不例外**。项目通过 `res/xml/network_security_config.xml` 只为回环开口，绝不要改用 `usesCleartextTraffic="true"`。

这个问题的表现很有迷惑性：服务正常启动、代理正常工作，只有首页速率恒为 0。因为异常被订阅流里的空 `catch` 吞掉了，日志里什么都没有。教训是**任何异常路径至少要留一条日志**。

---

## 许可

GPL-3.0-or-later。

本项目静态嵌入 sing-box（GPL-3.0），因此整体必须以 GPL-3.0 兼容许可发布。
