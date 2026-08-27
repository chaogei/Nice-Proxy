# Nice-Proxy 设计文档

> Android 平台多协议代理服务器 —— "Every Proxy 增强版"

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.5 |
| 状态 | 功能完整，待真机回归 |
| 目标平台 | Android 7.0 (API 24) ~ Android 17 (API 37) |
| 代理内核 | sing-box v1.13.19 (stable) |
| UI 技术栈 | Kotlin 2.3 + Jetpack Compose (Material 3) |
| 构建工具链 | AGP 9.3.2 / Gradle 9.7.1 / JDK 17 / compileSdk 37 |
| 开源许可 | GPL-3.0-or-later |

---

## 1. 项目概述

### 1.1 一句话定位

Nice-Proxy 把 Android 设备变成一台**局域网多协议代理网关**：局域网内的电脑、游戏机、平板把它当作普通的 HTTP/SOCKS 代理使用，而 Nice-Proxy 在出站侧可以把这些流量转发到 VMess / VLESS / Trojan / Shadowsocks / Hysteria2 等任意上游节点，并按规则分流。

### 1.2 与 Every Proxy 的关系

Every Proxy 是本项目的功能基线。但它有一条官方明确承认的硬限制 —— 摘自其 FAQ：

> "Every Proxy is a **Server, not a Client**. It creates a proxy for others to connect to; it **cannot 'log in' to an upstream proxy**."

也就是说 Every Proxy 只能做**透明中继**：收到客户端请求后，用手机当前的网络出口直接连目标服务器。它无法接入上游代理，更不用说 VMess/Hysteria2 这类协议。

Nice-Proxy 的核心价值主张就是补上这一块：**入站对齐 Every Proxy，出站接入现代代理协议全家桶**。

### 1.3 设计边界（Non-Goals）

以下内容**明确不在本期范围**，写在这里是为了防止架构被无意义地复杂化：

| 不做 | 原因 |
| --- | --- |
| VpnService / TUN 全局代理 | 定位是"给别的设备用的网关"，不是"给本机用的 VPN"。不申请 VPN 权限可以避开系统 VPN 授权弹窗、per-app 路由、TUN 协议栈（gVisor）等一大堆复杂度，并显著减小包体。 |
| MITM / HTTPS 解密抓包 | 需要装用户 CA 证书，Android 7+ 后应用默认不信任用户 CA，收益低且触碰隐私红线。 |
| iOS / 桌面端 | 本期只做 Android。架构上通过纯 Kotlin 的领域层 + 配置生成层预留移植可能。 |
| 自建服务端 | 只做客户端侧网关，不提供节点。 |
| 多内核可切换 | 单内核（sing-box）已覆盖需求全集，多内核会让配置生成层复杂度翻倍。 |

> **关于 TUN 的补充**：不做 TUN 意味着**本机 App 的流量不会被代理**（除非该 App 自己支持配置 HTTP 代理）。这是定位决定的取舍，需要在 README 和应用内明确告知用户，避免用户预期错位。

---

## 2. 竞品与能力对标

### 2.1 能力矩阵

| 能力 | Every Proxy | v2rayNG | sing-box for Android | **Nice-Proxy** |
| --- | --- | --- | --- | --- |
| HTTP 代理服务器 | 支持 | 仅本机 | 仅本机 | **支持（可局域网）** |
| SOCKS4/4a 服务器 | 支持 | 不支持 | 支持 | **支持** |
| SOCKS5 服务器 | 支持 | 仅本机 | 仅本机 | **支持（可局域网）** |
| 单端口混合协议 | 不支持 | 不支持 | 支持 | **支持（mixed）** |
| PAC 自动配置服务 | 支持 | 不支持 | 不支持 | **支持** |
| 代理认证（用户/密码） | 支持 | — | 支持 | **支持** |
| 网卡/接口绑定 | 支持 | 不支持 | 不支持 | **支持** |
| 开机自启 | 支持 | 支持 | 支持 | **支持** |
| 省电（IP 消失自动停） | 支持 | 不支持 | 不支持 | **支持** |
| **上游多协议出站** | **不支持** | 支持 | 支持 | **支持** |
| **规则分流** | 付费/受限 | 支持 | 支持 | **支持** |
| **订阅管理** | 不支持 | 支持 | 支持 | **支持** |
| **节点延迟测速** | 不支持 | 支持 | 支持 | **支持** |
| 实时连接监控 | 付费 | 不支持 | 支持 | **支持（免费）** |
| 需要 VPN 权限 | 否 | 是 | 是 | **否** |
| 需要 Root | 否 | 否 | 否 | **否** |

### 2.2 差异化总结

Nice-Proxy = `Every Proxy 的入站能力` × `sing-box 的出站与分流能力`，且**不索取 VPN 权限**。这个组合在现有市场上是空白：做网关的不支持上游协议，支持上游协议的都是 VPN 客户端。

---

## 3. 需求分析

### 3.1 目标用户与核心场景

**场景 A：给游戏机 / 智能电视用代理**
Switch、PS5、Apple TV 这类设备无法安装代理客户端，但都支持在网络设置里填 HTTP/SOCKS 代理。用户把手机开热点或连同一 Wi-Fi，游戏机指向手机 IP:8080，即可让主机流量走 Hysteria2 节点。
→ 驱动需求：局域网监听、上游协议、PAC、二维码/地址展示。

**场景 B：给公司电脑做临时出口**
开发者笔记本受管控无法装代理软件，但浏览器/终端可以配 `http_proxy`。用手机做出口。
→ 驱动需求：代理认证（防止同网段他人蹭用）、连接监控。

**场景 C：分流网关**
希望国内流量走手机直连（快），国外流量走机场节点（通）。
→ 驱动需求：路由规则、规则集（geosite/geoip）、多节点与自动选优。

**场景 D：共享手机的蜂窝网络**
在没有 Wi-Fi 的地方，用手机热点 + 代理让电脑上网，同时省去热点流量被运营商识别的问题（部分场景）。
→ 驱动需求：接口绑定（强制出站走蜂窝）、热点接口识别。

### 3.2 功能需求

优先级：**P0** = MVP 必须；**P1** = 1.0 正式版必须；**P2** = 后续迭代。

#### FR-1 入站服务（Inbound）

| ID | 需求 | 优先级 |
| --- | --- | --- |
| FR-1.1 | 支持 `mixed` 类型入站：单端口同时接受 HTTP / SOCKS4 / SOCKS4a / SOCKS5 | P0 |
| FR-1.2 | 支持独立 `http` 入站 | P0 |
| FR-1.3 | 支持独立 `socks` 入站 | P0 |
| FR-1.4 | 支持同时运行多个入站实例（不同端口、不同配置） | P1 |
| FR-1.5 | 监听地址可配置：`0.0.0.0`（全部接口）/ `127.0.0.1`（仅本机）/ 指定 IP | P0 |
| FR-1.6 | 端口可配置，范围校验 1025–65535，冲突检测 | P0 |
| FR-1.7 | 可选用户名/密码认证（HTTP Basic + SOCKS5 user/pass） | P0 |
| FR-1.8 | SOCKS5 UDP ASSOCIATE 支持（Every Proxy 不支持，差异化点） | P1 |
| FR-1.9 | PAC 服务器：提供 `http://<ip>:<port>/proxy.pac`，内容可基于分流规则生成 | P1 |
| FR-1.10 | 展示所有可用监听地址（含热点接口），支持一键复制与二维码 | P0 |
| FR-1.11 | TCP Fast Open、UDP 超时等高级参数 | P2 |

#### FR-2 出站与节点管理（Outbound）

| ID | 需求 | 优先级 |
| --- | --- | --- |
| FR-2.1 | 支持出站协议：`direct`、`http`、`socks`、`shadowsocks`、`vmess`、`vless`、`trojan`、`hysteria2` | P0 |
| FR-2.2 | 扩展协议：`tuic`、`anytls`、`wireguard`、`ssh`、`shadowtls`、`hysteria`(v1) | P1 |
| FR-2.3 | 传输层：`tcp`、`ws`、`grpc`、`http`、`httpupgrade`、`quic` | P0 |
| FR-2.4 | TLS 能力：SNI、ALPN、`insecure`、自定义 CA、uTLS 指纹、**REALITY**、ECH | P0 |
| FR-2.5 | 多路复用（`multiplex`：yamux / smux / h2mux）与 Brutal 拥塞控制 | P1 |
| FR-2.6 | 节点分组，支持手动组与订阅组 | P0 |
| FR-2.7 | `selector`（手动选择）与 `urltest`（自动选优）策略组 | P0 |
| FR-2.8 | 节点延迟测速（单个 / 批量 / 组内） | P0 |
| FR-2.9 | Hysteria2 端口跳跃（`server_ports`）与 Salamander 混淆 | P1 |
| FR-2.10 | 节点链式代理（`detour`，节点 A 经由节点 B 出站） | P2 |

#### FR-3 导入与订阅

| ID | 需求 | 优先级 |
| --- | --- | --- |
| FR-3.1 | 解析分享链接：`ss://`(SIP002+legacy)、`vmess://`、`vless://`、`trojan://`、`hysteria2://`/`hy2://`、`tuic://`、`anytls://`、`socks://`、`http://` | P0 |
| FR-3.2 | 从剪贴板批量导入 | P0 |
| FR-3.3 | 二维码扫描导入（相机 + 相册图片） | P1 |
| FR-3.4 | 订阅 URL 导入，支持 Base64 链接列表、Clash YAML、sing-box JSON、SIP008 | P0 |
| FR-3.5 | 订阅自动更新（可配置间隔），失败重试与错误展示 | P1 |
| FR-3.6 | 解析订阅响应头 `subscription-userinfo`，展示已用/剩余流量与到期时间 | P1 |
| FR-3.7 | 手动表单添加/编辑节点（按协议动态渲染字段） | P0 |
| FR-3.8 | 导出节点为分享链接 / 二维码；配置整体备份与恢复 | P2 |

#### FR-4 路由分流

| ID | 需求 | 优先级 |
| --- | --- | --- |
| FR-4.1 | 规则匹配器：`domain`、`domain_suffix`、`domain_keyword`、`domain_regex`、`ip_cidr`、`source_ip_cidr`、`port`、`port_range`、`network`、`protocol`、`inbound` | P0 |
| FR-4.2 | 规则动作：转发到指定出站 / `reject` 拒绝 / `hijack-dns` | P0 |
| FR-4.3 | 远程规则集（`rule_set`，geosite / geoip，binary 格式），自动更新 | P1 |
| FR-4.4 | 规则列表拖拽排序、启用/禁用、分组 | P1 |
| FR-4.5 | 内置模板：全局代理 / 绕过大陆 / 全局直连 / 自定义 | P0 |
| FR-4.6 | 按**客户端来源 IP** 分流（不同设备走不同节点）—— 网关形态独有的能力 | P1 |
| FR-4.7 | 协议嗅探（`action: "sniff"`）以支持基于域名的规则 | P0 |

#### FR-5 服务生命周期与系统集成

| ID | 需求 | 优先级 |
| --- | --- | --- |
| FR-5.1 | 前台服务运行，常驻通知显示状态与实时速率，通知内可停止 | P0 |
| FR-5.2 | 开机自启（`BOOT_COMPLETED`） | P1 |
| FR-5.3 | 快捷设置磁贴（Quick Settings Tile）一键开关 | P1 |
| FR-5.4 | 省电模式：绑定 IP 消失时自动停止服务 | P1 |
| FR-5.5 | 网络切换自动重连（Wi-Fi ⇄ 蜂窝） | P0 |
| FR-5.6 | WifiLock + 部分 WakeLock，防止 Doze 断流 | P0 |
| FR-5.7 | 引导用户加入电池优化白名单 | P1 |
| FR-5.8 | 出站网络接口选择：自动 / Wi-Fi / 蜂窝 / 以太网 | P1 |

#### FR-6 监控与诊断

| ID | 需求 | 优先级 |
| --- | --- | --- |
| FR-6.1 | 实时上下行速率与累计流量 | P0 |
| FR-6.2 | 实时连接列表：客户端 IP、目标地址、命中规则、出站、上下行、时长；支持手动断开 | P1 |
| FR-6.3 | 实时日志查看，级别过滤，导出 | P0 |
| FR-6.4 | 按日/按节点的流量统计图表 | P2 |
| FR-6.5 | 内核内存占用监控 | P2 |

#### FR-7 设置

| ID | 需求 | 优先级 |
| --- | --- | --- |
| FR-7.1 | 外观：浅色/深色/跟随系统、Material You 动态取色 | P1 |
| FR-7.2 | 语言：简体中文 / English（`per-app language`） | P1 |
| FR-7.3 | DNS：远程/本地 DNS 服务器、解析策略（`prefer_ipv4` 等）、DNS 分流 | P1 |
| FR-7.4 | 日志级别、缓存清理、配置导入导出 | P1 |
| FR-7.5 | 高级：直接编辑生成的 sing-box JSON（专家模式，只读预览 + 覆盖注入） | P2 |

### 3.3 非功能需求

| ID | 类别 | 指标 |
| --- | --- | --- |
| NFR-1 | 吞吐 | 直连中继场景，中端设备（骁龙 7 系）单连接 ≥ 300 Mbps；Hysteria2 出站 ≥ 150 Mbps |
| NFR-2 | 延迟 | 代理引入的额外 RTT ≤ 5 ms（直连中继） |
| NFR-3 | 并发 | 稳定支撑 ≥ 512 并发 TCP 连接 |
| NFR-4 | 内存 | 空载常驻 ≤ 80 MB，512 并发下 ≤ 200 MB |
| NFR-5 | 功耗 | 空载 8 小时电量消耗 ≤ 3% |
| NFR-6 | 包体 | 单 ABI split APK ≤ 33 MB（下载体积）。**该指标经实测修正**，原定 25 MB 不可达，见 §11.5 |
| NFR-7 | 冷启动 | 应用冷启动到首帧 ≤ 800 ms；内核启动 ≤ 500 ms |
| NFR-8 | 稳定性 | 崩溃率 < 0.1%；连续运行 72 小时无内存泄漏 |
| NFR-9 | 安全 | 认证凭据加密存储；Clash API 仅监听 127.0.0.1 且带随机 secret；默认入站监听 `0.0.0.0` 但**自带随机凭据**，不存在免认证的开箱状态 |
| NFR-10 | 兼容 | minSdk 24 / targetSdk 36；armeabi-v7a、arm64-v8a、x86_64；支持 Android 15 的 16 KB 内存页 |
| NFR-11 | 可维护 | 领域层与 Android 框架解耦，配置生成器 100% 单元测试覆盖 |

---

## 4. 技术选型与论证

### 4.1 代理内核：sing-box

| 候选 | 协议覆盖 | Hysteria2 | 许可 | 结论 |
| --- | --- | --- | --- | --- |
| **sing-box** | 最全（含 TUIC / AnyTLS / ShadowTLS / Naive） | 原生一等公民，协议作者深度参与 | GPL-3.0 | **选定** |
| Xray-core | 全，VLESS+REALITY 生态最强 | 配置割裂成两个 block，无 TUIC | MPL-2.0 | 备选 |
| mihomo | 全，Clash 规则生态最完整 | 实验性，社区报告握手失败 | GPL-3.0 | 否 |

**决策理由**：

1. Hysteria2 是明确的需求项，sing-box 是三者中唯一提供成熟稳定实现的；
2. `mixed` 入站单端口同时支持 socks4/4a/5 + http，与 Every Proxy 的"自动协商"行为**天然对齐**，无需自己实现协议探测；
3. 官方提供 `gomobile` 构建链路（`cmd/internal/build_libbox`），Android 集成路径成熟；
4. 内置 Clash API，直接白送实时流量、连接列表、日志、节点切换、延迟测速五项能力（见 §6.9）；
5. 许可 GPL-3.0 与本项目开源计划一致。

**版本策略**：锁定 stable v1.13.19。1.14 处于 RC 阶段，待其转正后再评估升级。

> **1.13 迁移注意**：入站级的 `sniff` / `sniff_override_destination` / `domain_strategy` / `udp_disable_domain_unmapping` 字段在 1.13.0 中**已被移除**（1.11 起废弃）。必须改用 `route.rules` 中的 `action: "sniff"` 与 `action: "resolve"`。配置生成器必须按新写法生成，否则内核直接拒绝加载。

### 4.2 为什么不用 VpnService

| 维度 | 用 VpnService | 不用（本方案） |
| --- | --- | --- |
| 权限 | 需系统 VPN 授权弹窗，部分定制 ROM 会额外拦截 | 无需任何特殊权限 |
| 与其他 VPN 共存 | 互斥，同一时刻只能有一个 VPN | 可共存 |
| 包体 | 需 gVisor netstack，AAR 体积增加约 8–12 MB | 可裁剪掉 |
| 复杂度 | TUN 读写、per-app 路由、IP 栈、DNS 劫持 | 全部省去 |
| 代价 | — | 本机 App 流量不被代理 |

对"局域网网关"这个定位，代价是可接受的，收益是巨大的。

### 4.3 Go 内核封装：自建精简 AAR

不直接使用官方 `libbox.aar`，而是自建一个精简的 gomobile 绑定模块 `native/libnice`：

- **裁掉 TUN 相关**：不启用 `with_gvisor`，省下最大的一块体积；
- **裁掉用不到的**：`with_tailscale`、`with_naive_outbound`；
- **API 面收窄**：官方 `libbox` 的 `PlatformInterface` 有十余个 TUN/进程查询相关回调必须实现，我们一个都不需要。

构建标签以 sing-box v1.13.19 官方 Android 配置（`cmd/internal/build_libbox/main.go` 的 `sharedTags`）为基准裁剪而来：

| 标签 | 取舍 | 原因 |
| --- | --- | --- |
| `with_quic` | 启用 | Hysteria2 / TUIC / HTTP3 必需 |
| `with_utls` | 启用 | REALITY 与 TLS 指纹伪装必需 |
| `with_wireguard` | 启用 | WireGuard 出站 |
| `with_clash_api` | 启用 | Kotlin 侧的唯一数据通道（§6.9） |
| `badlinkname`、`tfogo_checklinkname0` | 启用 | sing-box 大量使用 `go:linkname` 访问运行时内部符号，缺失会链接失败；ldflags 还需 `-checklinkname=0` |
| `with_gvisor` | **禁用** | 仅 TUN 需要，体积削减最大的一项 |
| `with_naive_outbound` | 禁用 | 用不到，且要求 API 23+ |
| `with_tailscale` | 禁用 | 用不到 |
| `with_ech` | **禁用** | 1.13 起 ECH 已迁移到 Go 标准库，该标签被废弃，**显式传入会直接触发编译错误** |
| `with_grpc` | **禁用** | 不加反而使用 sing-box 自带的轻量 gRPC 实现，体积更小，更适合移动端 |
| `with_dhcp` | 禁用 | 官方仅在 Apple 平台启用 |

**实测产物**：arm64 单 ABI 约 10 MB，三 ABI 合计 31 MB，满足 NFR-6。

### 4.4 UI：Kotlin + Jetpack Compose

原生方案对 `ConnectivityManager`、前台服务、QS Tile、`NetworkInterface` 枚举等系统能力的对接最直接，且本项目只做 Android，跨平台框架带来的抽象层是纯粹的成本。Material 3 + 动态取色可以低成本做出现代观感。

---

## 5. 系统架构

### 5.1 分层架构

```mermaid
graph TB
    subgraph UI["表现层 · Jetpack Compose"]
        A1[首页/开关] --> A2[入站配置]
        A2 --> A3[节点管理]
        A3 --> A4[路由规则]
        A4 --> A5[监控/日志/设置]
    end

    subgraph VM["状态层 · ViewModel + StateFlow"]
        B1[HomeViewModel]
        B2[NodesViewModel]
        B3[RoutingViewModel]
        B4[MonitorViewModel]
    end

    subgraph DOMAIN["领域层 · 纯 Kotlin，无 Android 依赖"]
        C1[领域模型<br/>ServerProfile / Inbound / Rule]
        C2[配置生成器<br/>SingBoxConfigBuilder]
        C3[链接解析器<br/>ShareLinkParser / SubscriptionParser]
    end

    subgraph DATA["数据层"]
        D1[(Room<br/>节点/分组/入站/规则)]
        D2[DataStore<br/>设置项]
        D3[OkHttp<br/>订阅拉取/规则集下载]
        D4[ClashApiClient<br/>REST + WebSocket]
    end

    subgraph SERVICE["服务层 · Android"]
        E1[ProxyService<br/>前台服务]
        E2[NetworkBinder<br/>接口绑定/网络监听]
        E3[PacServer<br/>Kotlin ServerSocket]
        E4[NotificationController]
        E5[BootReceiver / QSTile]
    end

    subgraph NATIVE["内核层 · Go"]
        F1[libnice.aar<br/>gomobile 绑定]
        F2[sing-box v1.13.19]
    end

    UI --> VM --> DOMAIN
    VM --> DATA
    VM --> SERVICE
    DOMAIN --> DATA
    SERVICE --> NATIVE
    DATA -.Clash API<br/>127.0.0.1.-> NATIVE
    F1 --> F2
```

### 5.2 模块划分（Gradle）

```
Nice-Proxy/
├── app/                        :app                 Application、DI 图、导航、MainActivity
├── core/
│   ├── model/                  :core:model          纯 Kotlin 领域模型（无 Android 依赖）
│   ├── common/                 :core:common         工具、Result 封装、Dispatcher
│   ├── config/                 :core:config         sing-box 配置生成 + 链接/订阅解析
│   ├── database/               :core:database       Room 实体与 DAO
│   ├── datastore/              :core:datastore      设置持久化
│   ├── network/                :core:network        OkHttp、订阅拉取、Clash API 客户端
│   ├── data/                   :core:data           Repository（聚合 database/datastore/network）
│   ├── service/                :core:service        ProxyService、网络绑定、PAC、通知
│   └── designsystem/           :core:designsystem   主题、配色、通用 Compose 组件
├── feature/
│   ├── home/                   :feature:home
│   ├── inbound/                :feature:inbound
│   ├── nodes/                  :feature:nodes
│   ├── routing/                :feature:routing
│   ├── monitor/                :feature:monitor
│   ├── logs/                   :feature:logs
│   └── settings/               :feature:settings
├── native/
│   └── libnice/                Go 模块 → gomobile → libnice.aar
├── gradle/libs.versions.toml   版本目录
└── docs/DESIGN.md
```

**依赖方向**（严格单向，由 Gradle 依赖约束保证）：

```
feature:* ──> core:data ──> core:database
    │             │     └──> core:datastore
    │             │     └──> core:network
    │             └──> core:config ──> core:model
    └──> core:designsystem
app ──> feature:* + core:service
core:service ──> core:config + native:libnice
```

`core:model` 与 `core:config` 是纯 JVM 模块（`kotlin("jvm")`），不依赖 Android SDK。这样配置生成逻辑可以用普通 JUnit 跑单测，速度快且能保证跨平台移植性。

### 5.3 进程与线程模型

**单进程**。内核运行在应用主进程内（Go runtime 起自己的 goroutine 调度器与 OS 线程池）。

不拆独立进程的理由：拆进程需要 AIDL 跨进程通信、双份 Go runtime 初始化开销、配置传递序列化；而收益（内核崩溃不影响 UI）可以用 Go 侧 `recover` + Kotlin 侧异常边界覆盖大部分场景。

| 线程 | 职责 |
| --- | --- |
| Main | Compose UI 渲染 |
| `Dispatchers.IO` | 数据库、订阅拉取、文件 IO |
| `Dispatchers.Default` | 配置生成、链接解析 |
| Go runtime 线程池 | 全部代理流量收发（由 Go 调度，Kotlin 侧不感知） |
| ProxyService 的 `CoroutineScope` | 内核生命周期、Clash API WebSocket 订阅、网络状态监听 |

### 5.4 数据流

一次客户端请求的完整路径：

```
[笔记本浏览器]
   │ ① HTTP CONNECT / SOCKS5 握手（+ 可选认证）
   ▼
[Android :8080  mixed inbound]                  ← sing-box
   │ ② action:"sniff" 嗅探 TLS SNI / HTTP Host，还原真实域名
   ▼
[route.rules 顺序匹配]
   │  · source_ip_cidr 命中 → 指定出站（按设备分流）
   │  · rule_set geosite-cn 命中 → direct
   │  · 默认 → final: "proxy"
   ▼
[selector "proxy"] ──> [urltest "auto"] ──> [hysteria2 "node-1"]
   │ ③ 通过 ConnectivityManager 绑定的 Network 建立 UDP/QUIC 连接
   ▼
[上游服务器] ──> [目标网站]
```

**关键点 ②**：客户端通过 HTTP CONNECT / SOCKS5 传来的**通常已经是域名**，所以嗅探主要用于 SOCKS4（只传 IP）和透明场景。但 `action: "sniff"` 仍需显式声明，否则基于域名的规则集在部分路径上不生效。

**关键点 ③**：sing-box 的出站 `bind_interface` 在 Android 上依赖 `SO_BINDTODEVICE`，需要 `CAP_NET_RAW`，非 root 不可用。因此接口绑定必须在 **Android 侧**用 `ConnectivityManager` 完成（见 §6.7）。

---

## 6. 核心模块详细设计

### 6.1 Go 内核封装层（`native/libnice`）

#### 对外 API（gomobile 绑定）

```go
package libnice

// Version 返回内嵌的 sing-box 版本号，用于「关于」页与配置兼容性判断。
func Version() string

// CheckConfig 在不启动内核的前提下校验配置，用于保存前的即时反馈。
func CheckConfig(configJSON string) error

type Service struct{ /* 不导出 */ }

func NewService(configJSON string, workDir string) (*Service, error)
func (s *Service) Start() error
func (s *Service) Close() error
func (s *Service) IsRunning() bool
```

**整个绑定没有一个回调接口**，这是刻意的。核实官方 `libbox` 的实现后确认，`adapter.PlatformInterface` 有 `OpenTun` / `NetworkInterfaces` / `FindConnectionOwner` / `ReadWIFIState` / `SystemCertificates` 等十余个方法，且大量使用切片与自定义迭代器 —— 而这些**全部是为 TUN 服务的**。不做 TUN 就可以整个跳过。

流量统计、连接列表、日志流、节点切换、延迟测速一律走 Clash API（§6.9）。gomobile 的跨语言回调开销大、类型受限（不支持泛型/切片/map），高频结构化数据走 HTTP/WebSocket 反而更简单可靠。最终 Go 侧只剩「解析配置 → 起内核 → 停内核」三件事，是 gomobile 最不容易出问题的用法。

`Close()` 时调用 `debug.FreeOSMemory()`：代理停止后 Go 堆上会残留大量已释放的连接缓冲区，主动归还给操作系统才能让前台服务的常驻内存回落到 NFR-4 的目标。

#### 构建

```bash
# 依赖：Go 1.24+、Android NDK r28+、JDK 17+
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12

gomobile bind -v \
  -target=android/arm64,android/arm,android/amd64 \
  -androidapi 24 \
  -javapkg=com.niceproxy \
  -libname=nice \
  -trimpath -ldflags="-s -w -buildid=" \
  -tags="with_quic,with_utls,with_grpc,with_ech,with_clash_api,with_wireguard,with_dhcp" \
  -o ../../app/libs/libnice.aar \
  ./
```

> 必须使用 **SagerNet fork 的 gomobile**。上游 `golang.org/x/mobile` 对 sing-box 的依赖树处理有已知问题。

产物按 ABI 拆分，配合 Gradle 的 `splits { abi { ... } }` 与 `bundle`，保证单设备下载体积达到 NFR-6。

### 6.2 领域模型（`core:model`）

```kotlin
/** 节点。protocol 决定 params 的具体 schema。 */
data class ServerProfile(
    val id: String,                  // UUID
    val groupId: String,
    val name: String,
    val protocol: ProxyProtocol,
    val server: String,
    val serverPort: Int,
    val params: ProtocolParams,      // sealed interface，见下
    val transport: TransportConfig?, // ws / grpc / http / httpupgrade / quic
    val tls: TlsConfig?,             // 含 utls / reality / ech
    val multiplex: MultiplexConfig?,
    val sortOrder: Int,
    val latencyMs: Int?,             // 最近一次测速结果，-1 表示超时
    val lastTestedAt: Long?,
)

enum class ProxyProtocol {
    DIRECT, HTTP, SOCKS, SHADOWSOCKS, VMESS, VLESS, TROJAN,
    HYSTERIA, HYSTERIA2, TUIC, ANYTLS, SHADOWTLS, WIREGUARD, SSH,
}

sealed interface ProtocolParams {
    data class Shadowsocks(val method: String, val password: String, val plugin: String?, val pluginOpts: String?) : ProtocolParams
    data class VMess(val uuid: String, val security: String, val alterId: Int, val globalPadding: Boolean, val authenticatedLength: Boolean) : ProtocolParams
    data class VLess(val uuid: String, val flow: String?, val packetEncoding: String?) : ProtocolParams
    data class Trojan(val password: String) : ProtocolParams
    data class Hysteria2(
        val password: String,
        val upMbps: Int?, val downMbps: Int?,
        val obfsType: String?, val obfsPassword: String?,
        val serverPorts: List<String>?,   // 端口跳跃，如 ["20000:30000"]
        val hopInterval: String?,
    ) : ProtocolParams
    data class Tuic(val uuid: String, val password: String, val congestionControl: String, val udpRelayMode: String, val zeroRttHandshake: Boolean) : ProtocolParams
    data class HttpSocks(val username: String?, val password: String?, val version: String?) : ProtocolParams
    // ... 其余协议
}

/** 入站服务。 */
data class InboundService(
    val id: String,
    val type: InboundType,           // MIXED / HTTP / SOCKS / PAC
    val tag: String,                 // sing-box outbound/inbound tag，须全局唯一
    val listen: String,              // "0.0.0.0" | "127.0.0.1" | 具体 IP
    val listenPort: Int,
    val auth: InboundAuth?,          // null = 免认证
    val udpEnabled: Boolean,
    val tcpFastOpen: Boolean,
    val enabled: Boolean,
)

/** 路由规则。 */
data class RoutingRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val sortOrder: Int,
    val matcher: RuleMatcher,
    val action: RuleAction,          // Route(tag) / Reject / HijackDns / Sniff / Resolve
)

data class RuleMatcher(
    val domain: List<String> = emptyList(),
    val domainSuffix: List<String> = emptyList(),
    val domainKeyword: List<String> = emptyList(),
    val domainRegex: List<String> = emptyList(),
    val ipCidr: List<String> = emptyList(),
    val sourceIpCidr: List<String> = emptyList(),   // 按客户端设备分流
    val port: List<Int> = emptyList(),
    val portRange: List<String> = emptyList(),
    val network: List<String> = emptyList(),        // tcp / udp
    val protocol: List<String> = emptyList(),       // http / tls / quic / dns
    val inbound: List<String> = emptyList(),
    val ruleSet: List<String> = emptyList(),
    val ipIsPrivate: Boolean? = null,
    val invert: Boolean = false,
)
```

### 6.3 配置生成器（`core:config`）

整个应用的心脏。职责：把上述领域模型翻译成 sing-box v1.13 的 JSON。

```kotlin
class SingBoxConfigBuilder(private val json: Json) {
    fun build(input: ConfigInput): String
}

data class ConfigInput(
    val inbounds: List<InboundService>,
    val nodes: List<ServerProfile>,
    val groups: List<ServerGroup>,
    val rules: List<RoutingRule>,
    val ruleSets: List<RuleSetRef>,
    val dns: DnsSettings,
    val log: LogSettings,
    val clashApi: ClashApiSettings,
    val workDir: String,
)
```

生成结果的骨架：

```json
{
  "log": { "level": "info", "timestamp": true },

  "dns": {
    "servers": [
      { "tag": "dns-remote", "type": "https", "server": "1.1.1.1", "detour": "proxy" },
      { "tag": "dns-local",  "type": "udp",   "server": "223.5.5.5", "detour": "direct" }
    ],
    "rules": [
      { "rule_set": "geosite-cn", "server": "dns-local" }
    ],
    "final": "dns-remote",
    "strategy": "prefer_ipv4"
  },

  "inbounds": [
    {
      "type": "mixed", "tag": "mixed-in",
      "listen": "0.0.0.0", "listen_port": 8080,
      "users": [{ "username": "user", "password": "pass" }],
      "udp_timeout": "5m"
    },
    { "type": "socks", "tag": "socks-in", "listen": "0.0.0.0", "listen_port": 1080 }
  ],

  "outbounds": [
    { "type": "direct", "tag": "direct" },
    {
      "type": "selector", "tag": "proxy",
      "outbounds": ["auto", "node-1", "node-2", "direct"],
      "default": "auto", "interrupt_exist_connections": false
    },
    {
      "type": "urltest", "tag": "auto",
      "outbounds": ["node-1", "node-2"],
      "url": "https://www.gstatic.com/generate_204",
      "interval": "3m", "tolerance": 50
    },
    {
      "type": "hysteria2", "tag": "node-1",
      "server": "example.com", "server_port": 443,
      "password": "***", "up_mbps": 100, "down_mbps": 300,
      "obfs": { "type": "salamander", "password": "***" },
      "tls": { "enabled": true, "server_name": "example.com", "alpn": ["h3"] }
    }
  ],

  "route": {
    "rules": [
      { "action": "sniff", "timeout": "300ms" },
      { "protocol": "dns", "action": "hijack-dns" },
      { "ip_is_private": true, "outbound": "direct" },
      { "rule_set": ["geosite-cn", "geoip-cn"], "outbound": "direct" },
      { "rule_set": "geosite-ads", "action": "reject" }
    ],
    "rule_set": [
      {
        "type": "remote", "tag": "geosite-cn", "format": "binary",
        "url": "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs",
        "download_detour": "proxy", "update_interval": "7d"
      }
    ],
    "final": "proxy",
    "auto_detect_interface": false,
    "default_domain_resolver": "dns-local"
  },

  "experimental": {
    "clash_api": { "external_controller": "127.0.0.1:19090", "secret": "<每次安装随机生成>" },
    "cache_file": { "enabled": true, "path": "cache.db", "store_rdrc": true }
  }
}
```

#### 生成器的硬性约束（写成单元测试）

| 约束 | 说明 |
| --- | --- |
| C-1 | 绝不生成入站级 `sniff` / `domain_strategy`（1.13 已移除，会导致内核拒绝加载） |
| C-2 | 绝不生成 `block` / `dns` 类型的 outbound（1.11+ 已废弃，改用 `action: "reject"` / `action: "hijack-dns"`） |
| C-3 | `auto_detect_interface` 恒为 `false`（无 TUN，且 Android 上接口绑定由宿主负责） |
| C-4 | 出站 server 使用域名时，必须存在 `default_domain_resolver`，否则 1.12+ 启动失败 |
| C-5 | 所有 tag 全局唯一；节点 tag 用 `node-{短哈希}` 而非用户可编辑的名称，避免重名与特殊字符 |
| C-6 | `urltest` 组为空时不生成该 outbound，且从 `selector` 的候选中剔除，避免空组导致启动失败 |
| C-7 | 用户未配置任何节点时，`route.final` 回退到 `direct`，保证纯中继模式（Every Proxy 等价行为）可用 |
| C-8 | 密码/UUID 等敏感字段在日志与配置预览中脱敏 |
| C-9 | Clash API 恒定绑定 `127.0.0.1`，端口与密钥随机生成 |
| C-10 | DNS 服务器不得 `detour` 到 `direct`。1.12+ 的类型化 DNS 服务器默认就用空 direct 出站作拨号器，显式指定会让内核在**启动阶段**报 `detour to an empty direct outbound makes no sense` 并拒绝启动。该错误在装配阶段看不出来，内核契约测试抓不到，只能在生成侧守住 |

#### 热更新 vs 重启

sing-box 无配置热重载 API。生成器输出配置后计算内容哈希，与运行中配置比对：

| 变更类型 | 处理 |
| --- | --- |
| 仅切换 selector 选中项 | Clash API `PUT /proxies/proxy` 热切换，**不重启**，现有连接不中断 |
| 入站端口/认证、路由规则、节点增删、DNS 变更 | 重建配置 → 停止内核 → 启动内核（约 300–500 ms） |
| 订阅更新后节点集合变化 | 同上，但保留用户当前选中的节点（按 tag 匹配，失效则回落到 urltest） |

### 6.4 分享链接与订阅解析

```kotlin
interface ShareLinkParser {
    fun canParse(uri: String): Boolean
    fun parse(uri: String): Result<ServerProfile>
}
// 实现：SsLinkParser / VmessLinkParser / VlessLinkParser / TrojanLinkParser
//      Hysteria2LinkParser / TuicLinkParser / AnyTlsLinkParser / HttpSocksLinkParser

interface SubscriptionFormatParser {
    fun detect(content: String): SubscriptionFormat?
    fun parse(content: String): Result<List<ServerProfile>>
}
// 格式探测顺序：sing-box JSON → Clash YAML → SIP008 JSON → Base64 链接列表 → 明文链接列表
```

解析器全部放在纯 JVM 模块，用真实的链接样本做参数化测试。这是最容易出隐蔽 bug 的地方（Base64 变体、URL 编码、字段别名、厂商私有扩展），必须有高覆盖率的测试。

订阅拉取时读取响应头：

```
subscription-userinfo: upload=1234; download=5678; total=107374182400; expire=1735689600
```

用于展示流量与到期时间（FR-3.6）。

### 6.5 入站服务与 PAC

HTTP / SOCKS / Mixed 三种入站由 sing-box 直接提供，配置生成器负责翻译。

**PAC 服务器需要自己实现** —— sing-box 不提供该能力。设计为 `core:service` 中一个独立的轻量组件：

```kotlin
class PacServer(private val scope: CoroutineScope) {
    fun start(port: Int, content: () -> String)
    fun stop()
}
```

基于 `ServerSocket` + 协程，仅处理 `GET /proxy.pac`，返回 `application/x-ns-proxy-autoconfig`。PAC 内容由当前入站配置与"直连域名列表"动态生成：

```javascript
function FindProxyForURL(url, host) {
  if (isPlainHostName(host) || shExpMatch(host, "*.local")) return "DIRECT";
  if (isInNet(dnsResolve(host), "192.168.0.0", "255.255.0.0")) return "DIRECT";
  return "PROXY 192.168.43.1:8080; SOCKS5 192.168.43.1:1080; DIRECT";
}
```

### 6.6 监听地址发现

用户最高频的操作是"我该在电脑上填什么 IP"。设计一个 `NetworkAddressDiscovery`，枚举 `NetworkInterface.getNetworkInterfaces()`，按接口名归类并给出人类可读标签：

| 接口名模式 | 标签 |
| --- | --- |
| `wlan0` | Wi-Fi |
| `ap0`、`swlan0`、`wlan1`、`softap0` | 热点 |
| `rmnet*`、`ccmni*` | 蜂窝数据 |
| `eth0`、`usb0`、`rndis0` | 以太网 / USB 网络共享 |
| `lo` | 本机（默认隐藏） |

**刻意不使用 `WifiManager.getConnectionInfo()`** —— Android 10+ 需要定位权限才能拿到有效信息。用 `NetworkInterface` 枚举可以零权限拿到全部 IPv4/IPv6 地址，且能识别热点接口（`WifiManager` 反而拿不到热点 IP）。

首页对每个地址渲染一张卡片：标签、IP、端口、复制按钮、二维码（内容为 `http://ip:port` 或自定义 schema）。

### 6.7 网络接口绑定与网络切换

sing-box 出站的 `bind_interface` 在非 root Android 上不可用（需 `CAP_NET_RAW`）。方案是在 Android 侧控制：

```kotlin
class NetworkBinder(private val cm: ConnectivityManager) {
    /** 自动：跟随系统默认网络；指定：强制出站走某类网络。 */
    fun bind(preference: NetworkPreference)
}

enum class NetworkPreference { AUTO, WIFI, CELLULAR, ETHERNET }
```

实现要点：

1. 用 `NetworkRequest.Builder().addTransportType(...)` 请求目标网络，拿到 `Network` 对象；
2. 调用 `ConnectivityManager.bindProcessToNetwork(network)` —— 这会把**整个进程**的新建 socket 绑定到该网络。因为 Go 内核与 Kotlin 在同一进程，sing-box 的所有出站连接自动生效，无需任何 Go 侧改动；
3. **监听端不受影响**：`0.0.0.0` 上的 listening socket 依然接受所有接口的连接，热点客户端可以正常接入；
4. 注册 `NetworkCallback`，在 `onAvailable` / `onLost` / `onCapabilitiesChanged` 时重新绑定，并按需重启内核（QUIC 连接在网络切换后必须重建）；
5. 副作用：订阅拉取等应用自身的 HTTP 请求也会走绑定网络。这通常是期望行为；如需例外，可给对应 OkHttp Client 设置 `socketFactory = otherNetwork.socketFactory`。

> **已知限制**：若设备上有其他 VPN 应用处于活动状态，本应用的出站流量会被该 VPN 捕获，非 root 无法绕过。这也正是 Every Proxy 需要单独发布一个 "Network Bridge" 伴侣应用的原因。本项目在文档与应用内做提示，不做规避。

### 6.8 前台服务与生命周期

```kotlin
class ProxyService : Service() {
    // 状态机：IDLE → STARTING → RUNNING → STOPPING → IDLE
    //                    └────────> ERROR
}
```

**Android 14 (API 34) 前台服务类型**：代理服务器不属于任何预定义类型，必须使用 `specialUse`：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<service
    android:name=".service.ProxyService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Local HTTP/SOCKS proxy server for LAN clients" />
</service>
```

> 特意**不用 `dataSync`**：Android 15 (API 35) 对 `dataSync` 类型施加了 24 小时内累计 6 小时的运行时长上限，代理网关必须长期运行。

**通知**：显示运行状态、监听端口、实时上下行速率，含"停止"操作按钮。速率更新做 1 秒节流，避免高频刷新耗电。

### 6.8.1 保活：分层防御

「服务用着用着就没了」在这个产品上不是体验问题而是功能失效——它一停，全屋那些指着它上网的设备一起断网，而用户往往要等很久才发现。单一手段挡不住所有杀法，所以按「从最快恢复到最兜底」分四层：

| 层 | 应对的死法 | 机制 | 恢复延迟 |
| --- | --- | --- | --- |
| 1 | 内核自己退出（panic / OOM / 内部错误），进程还活着 | `ProxyService` 内 10 秒一次轮询 `NiceCore.isRunning`，发现就地重启 | ≤ 10 秒 |
| 2 | 启动/重启失败（地址未就绪、端口未释放） | 指数退避重试，1→2→4→8→16→30 秒，共 6 次，见 `RetryPolicy` | 1 秒起 |
| 3 | 进程被系统或 ROM 回收 | `START_STICKY` 重建 + `WorkManager` 每 15 分钟的看门狗 | 秒级 ~ 15 分钟 |
| 4 | 看门狗也被冻结（force-stop 级别） | 用户下次打开应用时由 `Application.onCreate` 补拉 | 用户下次开应用 |

第 3 层被系统拦下时（未获电池优化豁免，`ForegroundServiceStartNotAllowedException`），看门狗会推一条独立渠道的通知。不推的话，用户那边的表现是「所有设备莫名断网、而且再也不会自己好」，且没有任何线索指向原因——而这恰恰是最需要他去做一次授权的时刻。通知用固定 ID + `setOnlyAlertOnce`，每 15 分钟失败一次也不会反复响；服务成功起来时撤销。

**第 1 层为什么必须存在。** 内核在原生层跑，它退出时宿主收不到任何通知：状态仍是 Running、通知栏一切正常、只有客户端连不上。这是所有失败模式里最难察觉的一种，因为界面上看不出任何异常。

**落盘的运行意图。** 上述 3、4 两层都要回答同一个问题：「它本来是不是开着的？」进程死后内存里什么都不剩，所以这一位必须落盘（`should_be_running`）。只有三种情况会清掉它：用户显式停止、省电模式按配置停机、开机时未启用自启。内核崩溃、进程被杀这些「非自愿」的停止**绝不能**清，否则恢复链路直接断掉。

反过来也要防：清不掉的话，用户按了停止、15 分钟后看门狗又给拉起来，就成了「关都关不掉」。所以这条写入走应用级作用域而不是 `lifecycleScope`——后者在 `stopSelf()` 后就被取消了，很可能来不及落盘。

**退避重试期间保持前台。** 最长要等 30 秒，中途掉出前台状态的话，再想启动就会撞上 Android 12+ 的后台启动限制，反而把一个可恢复的失败变成不可恢复的。

#### 电池优化白名单是整条链路的枢纽

它同时解决两件事，缺了任何一件保活都不成立：

1. **Doze 会挂起网络访问**。前台服务能防止进程被杀，但防不住 Doze 对网络的限制。
2. **Android 12+ 禁止后台启动前台服务**，而「用户关闭了本应用的电池优化」是官方豁免项之一。不拿到它，第 3 层的看门狗每次都会被 `ForegroundServiceStartNotAllowedException` 拦下——看门狗形同虚设。

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 受 Google Play 政策限制，但本项目主发布渠道是 GitHub Release 与 F-Droid（见 R-4），且代理类应用属于该政策列举的可接受用例。仍然保留回退路径：直接授权对话框被 ROM 屏蔽时退到系统的电池优化列表页。

#### WifiLock 在 Android 14+ 上已经指望不上

这一点很反直觉，写在这里免得后人再踩：`WIFI_MODE_FULL_HIGH_PERF` 自 API 34 起被系统**自动替换**成 `WIFI_MODE_FULL_LOW_LATENCY`，而后者「只在屏幕亮着且获取方在前台时生效」——恰好把息屏这个唯一需要它的场景排除在外。改用 LOW_LATENCY 是一样的结果，它本来就是给游戏和 VR 设计的。

因此代码里**刻意继续使用已废弃的 HIGH_PERF**：API ≤ 33 上它是真有用的（息屏与后台均生效），34+ 上退化成屏幕亮时有效，不比任何替代方案更差。Android 14+ 想在息屏时保住 Wi-Fi，真正的手段只有前台服务加电池优化白名单，设置项的文案不应对此过度承诺。

#### 国产 ROM 的自启动白名单

MIUI / EMUI / ColorOS / OriginOS / Flyme 各有一套独立于 AOSP 的自启动管理，不加进去的话前台服务照样被清理，`START_STICKY` 也会被忽略。这部分只能引导用户手动开——应用内提供按 `Build.MANUFACTURER` 分派的深链，各家组件名随 ROM 版本变动，因此每个厂商准备多个候选依次尝试，全部失败时退到应用详情页。

### 6.9 监控层：Clash API

内核启动时开启 Clash API，监听 `127.0.0.1:<随机端口>`，`secret` 为安装时生成的随机串（存入 DataStore）。Kotlin 侧用 OkHttp 消费：

| 端点 | 协议 | 用途 |
| --- | --- | --- |
| `/traffic` | WebSocket | 实时上下行速率（FR-6.1） |
| `/connections` | WebSocket | 连接列表快照（FR-6.2） |
| `/connections/{id}` | DELETE | 手动断开连接 |
| `/logs?level=info` | WebSocket | 实时日志流（FR-6.3） |
| `/proxies` | GET | 策略组与节点状态 |
| `/proxies/{group}` | PUT | **热切换选中节点，不重启内核**（§6.3） |
| `/proxies/{name}/delay` | GET | 节点延迟测速（FR-2.8） |
| `/memory` | WebSocket | 内核内存占用（FR-6.5） |

这一个决策同时满足了 FR-2.8、FR-6.1、FR-6.2、FR-6.3、FR-6.5 五条需求，且避免了在 gomobile 边界上传输高频结构化数据。

**安全**：`external_controller` 硬编码只允许 `127.0.0.1`，绝不允许用户改成 `0.0.0.0`；secret 随机且不展示在 UI 上。

---

## 7. 数据模型

### 7.1 Room 表结构

```sql
-- 节点
CREATE TABLE servers (
    id            TEXT PRIMARY KEY,
    group_id      TEXT NOT NULL REFERENCES server_groups(id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    protocol      TEXT NOT NULL,
    server        TEXT NOT NULL,
    server_port   INTEGER NOT NULL,
    params_json   TEXT NOT NULL,     -- ProtocolParams 序列化
    transport_json TEXT,
    tls_json      TEXT,
    multiplex_json TEXT,
    sort_order    INTEGER NOT NULL DEFAULT 0,
    latency_ms    INTEGER,           -- NULL 未测；-1 超时
    last_tested_at INTEGER,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);
CREATE INDEX idx_servers_group ON servers(group_id, sort_order);

-- 分组 / 订阅
CREATE TABLE server_groups (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    type          TEXT NOT NULL,     -- MANUAL | SUBSCRIPTION
    url           TEXT,
    user_agent    TEXT,
    auto_update   INTEGER NOT NULL DEFAULT 0,
    update_interval_min INTEGER NOT NULL DEFAULT 1440,
    last_update_at INTEGER,
    last_error    TEXT,
    traffic_upload   INTEGER,        -- subscription-userinfo
    traffic_download INTEGER,
    traffic_total    INTEGER,
    expire_at        INTEGER,
    sort_order    INTEGER NOT NULL DEFAULT 0
);

-- 入站服务
CREATE TABLE inbounds (
    id            TEXT PRIMARY KEY,
    type          TEXT NOT NULL,     -- MIXED | HTTP | SOCKS | PAC
    tag           TEXT NOT NULL UNIQUE,
    listen        TEXT NOT NULL DEFAULT '0.0.0.0',
    listen_port   INTEGER NOT NULL,
    auth_enabled  INTEGER NOT NULL DEFAULT 0,
    username      TEXT,
    password      TEXT,              -- EncryptedFile / Keystore 加密
    udp_enabled   INTEGER NOT NULL DEFAULT 1,
    tcp_fast_open INTEGER NOT NULL DEFAULT 0,
    enabled       INTEGER NOT NULL DEFAULT 1,
    sort_order    INTEGER NOT NULL DEFAULT 0
);

-- 路由规则
CREATE TABLE routing_rules (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    enabled       INTEGER NOT NULL DEFAULT 1,
    sort_order    INTEGER NOT NULL,
    matcher_json  TEXT NOT NULL,
    action        TEXT NOT NULL,     -- ROUTE | REJECT | HIJACK_DNS
    outbound_tag  TEXT               -- action=ROUTE 时有效
);

-- 规则集
CREATE TABLE rule_sets (
    id            TEXT PRIMARY KEY,
    tag           TEXT NOT NULL UNIQUE,
    type          TEXT NOT NULL,     -- REMOTE | LOCAL
    format        TEXT NOT NULL,     -- BINARY | SOURCE
    url           TEXT,
    update_interval TEXT,
    enabled       INTEGER NOT NULL DEFAULT 1
);

-- 流量统计（按日聚合）
CREATE TABLE traffic_daily (
    day           INTEGER PRIMARY KEY,   -- yyyyMMdd
    upload        INTEGER NOT NULL DEFAULT 0,
    download      INTEGER NOT NULL DEFAULT 0
);
```

### 7.2 DataStore 设置项

| 分组 | 键 | 默认值 |
| --- | --- | --- |
| 外观 | `theme_mode` / `dynamic_color` / `language` | 跟随系统 / 开 / 跟随系统 |
| 服务 | `auto_start_on_boot` / `start_on_launch` / `power_save` | 关 / 关 / 关 |
| 网络 | `network_preference` / `ipv6_enabled` | AUTO / 开 |
| DNS | `dns_remote` / `dns_local` / `dns_strategy` | `https://1.1.1.1/dns-query` / `223.5.5.5` / `prefer_ipv4` |
| 出站 | `selected_outbound_tag` / `urltest_url` / `urltest_interval` | `auto` / `generate_204` / `3m` |
| 日志 | `log_level` / `log_persist` | info / 关 |
| 内核 | `clash_api_port` / `clash_api_secret` | 随机 / 随机 |

#### 敏感字段的落盘加密

**不使用 `androidx.security:security-crypto`** —— 该库自 1.1.0 起整体废弃，`MasterKey` 的官方替代建议就是直接用 `javax.crypto.KeyGenerator` + `AndroidKeyStore`，而且它是面向**文件**的，没有加密单个数据库列的入口。实现改用 AndroidKeyStore + AES-GCM。

加密范围按「价值 ÷ 恢复成本」逐项判断，不做一刀切：

| 字段 | 加密 | 理由 |
| --- | --- | --- |
| `servers.params_json` | 是 | 所有密码、UUID、SS 密钥、SSH 私钥都在这一个 JSON 里 |
| `server_groups.url` | 是 | 订阅 URL 里的 token 一把梭出整个机场账号，价值高于任何单个节点密码；而恢复成本只是重贴一次链接 |
| `server_groups.extra_headers` | 是 | 可能直接躺着一个 `Authorization` |
| `inbounds.auth_password` | 是 | 局域网代理密码 |
| `inbounds.auth_username` | 否 | 不是秘密，UI 要显示，加密只换来一个额外的失败点 |
| `clash_api_secret` | 否 | 只对本机**正在运行**的内核实例有效，离线拿到毫无用处；能读 DataStore 的人必然也能读同目录的数据库 |

**存储格式**：`"nsec1:" + hex(iv‖密文‖tag)`。自描述前缀用于区分密文与升级前遗留的明文，因此**不需要任何数据库迁移** —— 老数据读出来原样返回，下次写入才变成密文。这不是图省事：改列定义会改变 Room 的 identity hash，而 `fallbackToDestructiveMigration` 开着，等于把所有用户配置清空一次。

用 hex 而非 Base64 是因为 `java.util.Base64` 需要 API 26（minSdk 是 24），而 `android.util.Base64` 在纯 JVM 单元测试里是抛异常的空壳；hex 多占三分之一空间，换来真机与测试走完全相同的代码路径。

**加解密位置**：Room 的 `@ProvidedTypeConverter`，而不是 `toDomain()`/`toEntity()`。Room 在读游标、绑语句时调用转换器，那些都发生在 Room 自己的执行器上；而 Repository 的 `.map { it.toDomain() }` 跑在**收集方**上下文里，Compose 用 `collectAsStateWithLifecycle` 收集就是主线程。

**降级策略**（密钥失效是全局的，所有密文会同时解不开，所以「过滤掉解不开的行」等价于「整个节点列表消失」）：

- **节点**：仍留在列表里并标记失效，参数换成一个**一定构建失败**的占位符。绝不能换成 `direct` —— 那是唯一不做校验的出站类型，会被生成成可用的直连，用户以为在走代理、流量却全部裸奔。
- **入站**：密码解不开时**连带停用**。只把认证置空的话，sing-box 会把它当免认证入站监听在 `0.0.0.0` 上，同网段谁都能白嫖，正是本节第一行要防的事。
- **拿不到密钥时**（设备没有可用 Keystore）退化为明文落盘并置降级标志。宁可明文也不能让用户存不了节点 —— 字段级加密在这里是纵深防御，真正兜底的是应用沙箱与系统的文件级加密。

---

## 8. UI / UX 设计

### 8.1 导航结构

底部导航 4 个一级页：

```
┌─────────────────────────────────────────────┐
│  首页 Home  │  节点 Nodes  │  路由 Routing  │  更多 More  │
└─────────────────────────────────────────────┘
```

- **首页**：主开关（大号 Switch/FAB）、运行状态与运行时长、实时上下行速率、入站服务卡片列表（点击进入配置）、可用监听地址卡片（标签 + IP:Port + 复制 + 二维码）、当前出站节点（点击弹出选择器）
- **节点**：分组 Tab（手动组 / 各订阅），节点列表（名称、协议徽标、延迟条），批量测速、排序筛选，FAB 添加（扫码 / 剪贴板 / 手动 / 订阅）
- **路由**：模式快选（全局代理 / 绕过大陆 / 全局直连 / 自定义），规则列表（拖拽排序、滑动删除、开关），规则集管理
- **更多**：连接监控、日志、设置、关于

### 8.2 关键交互设计

| 交互 | 设计 |
| --- | --- |
| 首次启动 | 引导页说明"本应用代理的是**其他设备**的流量，不代理本机 App"，避免预期错位 |
| 开启 `0.0.0.0` 且无认证 | 首页展示可点击警告，点击直达该入站的编辑页。**刻意不做开局弹窗** —— 默认入站已带随机凭据，走到这一步的只有手动关掉认证的用户，他知道自己在做什么 |
| 端口冲突 | 保存时即时校验，红色提示并推荐可用端口 |
| 配置变更需重启内核 | 顶部 Snackbar 提示"配置已变更，点击应用"，避免每次改动都无声重启导致断流 |
| 节点切换 | 通过 Clash API 热切换，Toast 提示"已切换，现有连接不受影响" |
| 内核启动失败 | 展示 sing-box 的原始错误信息 + 人类可读的翻译（如"端口被占用""节点配置缺少必填字段"） |
| 二维码 | 首页地址卡片可生成二维码，方便平板/其他手机扫码后自动填代理（配合自定义 schema） |

### 8.3 设计系统：毛玻璃

整体采用半透明毛玻璃（glassmorphism）：一层缓慢流动的极光渐变背景，内容面板悬浮其上做背景模糊。

**实现**：[Haze](https://github.com/chrisbanes/haze) 1.7。API 31+ 走 `RenderEffect` 真实背景模糊，更低版本自动降级为半透明着色 —— 观感弱一些但不破版，不需要写两套布局。

**几个不显然的取舍**：

| 决策 | 原因 |
| --- | --- |
| 背景必须是有内容的渐变，不能是纯色 | 毛玻璃模糊的是背后的东西。纯色背景糊出来还是纯色，等于白做 |
| 极光动画周期取 38 秒 | 慢到几乎察觉不到。这是个需要长期常驻的应用，持续重绘要计入功耗预算（NFR-5） |
| 面板必须有浅色描边 | 没有描边时，毛玻璃在浅色背景上会和底色糊成一片，失去「悬浮玻璃」的边界感 |
| 亮/暗色用两套完全不同的玻璃参数 | 暗色下要用浅色高光叠加才有玻璃感；亮色下要白色底 + 更低透明度，否则糊成一片灰 |
| `Scaffold` 的 `containerColor` 必须透明 | 否则它的 surface 会挡住背景，面板再怎么模糊也只能糊出纯色 |
| `HazeState` 通过 CompositionLocal 传递 | 背景与所有面板必须共享同一个 state。走参数会让每个页面签名都挂一个 `hazeState` |

配色沿用 Material 3 动态取色（Android 12+），回退为青蓝色系。状态色：运行中=绿、连接中=琥珀、已停止=灰、错误=红。协议徽标用短标签色块（VM / VL / TR / SS / HY2 / TUIC），同一协议在任何页面都是同一个颜色，用户扫一眼颜色就能定位。

---

## 9. 安全设计

| 风险 | 缓解措施 |
| --- | --- |
| 局域网内被他人蹭用代理 | **默认入站自带随机凭据**（见下）；用户手动改成免认证时，首页展示可点击的"未认证"警告，点击直达该入站 |
| 凭据泄露 | 节点参数、**订阅 URL（含 token）**、附加请求头、入站密码用 AndroidKeyStore + AES-GCM 加密落盘，详见 §7.2；日志与配置预览中脱敏；不写入 logcat |
| Clash API 被本机其他应用访问 | 仅监听 `127.0.0.1`，随机端口 + 随机 secret，不在 UI 暴露 |
| 配置备份文件泄露 | 导出时强制要求设置密码，用 AES-GCM 加密 |
| 中间人攻击 | 节点 TLS 默认 `insecure: false`；用户开启"跳过证书验证"时展示明确警告 |
| 剪贴板嗅探 | 复制敏感内容后 60 秒自动清空剪贴板（Android 13+ 用 `EXTRA_IS_SENSITIVE` 标记） |
| 依赖供应链 | Gradle 依赖锁定 + `dependencyVerification`；Go 侧 `go.sum` 校验 |

---

## 10. Android 平台约束清单

设计阶段必须内化的平台限制，避免实现期返工：

| 编号 | 约束 | 对策 |
| --- | --- | --- |
| P-1 | API 34+ 前台服务必须声明类型 | 用 `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`（§6.8） |
| P-2 | API 35 对 `dataSync` 限制 6h/24h | 不使用 `dataSync` |
| P-3 | 1024 以下端口需 root | 端口输入限制在 1025–65535，默认 8080 / 1080 |
| P-4 | Doze 模式会挂起网络 | 前台服务 + WakeLock + WifiLock + 电池优化白名单 |
| P-5 | 出站 `bind_interface` 需 `CAP_NET_RAW` | 改用 `ConnectivityManager.bindProcessToNetwork`（§6.7） |
| P-6 | Android 10+ 获取 Wi-Fi 信息需定位权限 | 改用 `NetworkInterface` 枚举（§6.6） |
| P-7 | Android 15 要求 16 KB 内存页对齐 | Go 1.24+ 与 NDK r28+ 构建。**该要求仅适用于 64 位 ABI**（16 KB 页设备都是 64 位），`armeabi-v7a` 保持 4 KB 对齐是正确的。构建脚本用 `llvm-readelf` 只校验 arm64-v8a 与 x86_64 |
| P-8 | Android 13+ 通知需运行时权限 | 首次启动服务前请求 `POST_NOTIFICATIONS`，拒绝时降级为无通知（并提示保活风险） |
| P-9 | 移动网络运营商普遍屏蔽入站连接 | 文档与 UI 提示："蜂窝网络下请使用热点模式" |
| P-10 | 其他 VPN 活动时会捕获本应用出站 | UI 检测并提示（`ConnectivityManager` 查询 `TRANSPORT_VPN`） |
| P-11 | 后台启动 Activity 受限 | 通知与磁贴的点击动作用 `PendingIntent` 直达，不做后台跳转 |
| P-12 | 部分国产 ROM 杀后台激进 | 提供"保活指引"页，按厂商给出设置路径 |
| P-13 | 工程路径含非 ASCII 字符会破坏构建 | 见 §10.1 |
| P-14 | targetSdk 28+ 默认禁止明文 HTTP，**回环地址也不例外** | Clash API 只能是 `127.0.0.1` 上的明文 HTTP，必须提供 `network_security_config.xml` 单独为回环开口。**不要用 `usesCleartextTraffic="true"`**，那会解除整个应用的明文限制。见 §10.2 |

### 10.1 关于工程路径中的中文字符

本仓库当前位于 `F:\Trace\GitHub源码\Nice-Proxy`，路径含中文。这在实测中已经引发过一次故障，值得单独记录。

**现象**：`:core:config:test` 编译成功、`.class` 文件确实产出，但所有测试类在运行时抛 `ClassNotFoundException`。

**原因**：Windows 中文环境下 JVM 的 `sun.jnu.encoding` 固定为 `GBK`（由系统区域设置决定，无法通过 `-D` 覆盖）。若在 `gradle.properties` 里单独把 `file.encoding` 设成 `UTF-8`，两者就不一致了 —— Gradle 把测试 worker 的 classpath 写进临时 JAR 的 manifest 时按 `file.encoding` 编码，worker 解析路径时按 `sun.jnu.encoding` 解码，含中文的路径段因此错位，类加载器找不到目录。

**处置**：`gradle.properties` 中**不设置** `-Dfile.encoding`，让两者保持一致；源码编码由各编译任务显式指定，不依赖 JVM 默认值。

**AGP 的硬性拦截**：接入 Android 模块后，AGP 会**直接拒绝构建**并报错：

> Your project path contains non-ASCII characters. This will most likely cause the build to fail on Windows.

目前通过 `gradle.properties` 中的 `android.overridePathCheck=true` 绕过，实测 AAPT2、R8、gomobile 均能正常工作。但这只是关掉了那道检查，**仍建议迁移到纯 ASCII 路径**（例如 `F:\Trace\GitHub\Nice-Proxy`），成本远低于后续逐个排查工具链故障。

### 10.2 明文 HTTP 与 Clash API

Clash API 只能是 `127.0.0.1` 上的明文 HTTP，而 targetSdk 28 起 Android 默认禁止一切明文流量，**回环地址不在豁免之列**。OkHttp 会抛 `UnknownServiceException: CLEARTEXT communication to 127.0.0.1 not permitted by network security policy`。

对策是提供只为回环开口的 `res/xml/network_security_config.xml`：

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">::1</domain>
    </domain-config>
</network-security-config>
```

**绝不要用 `android:usesCleartextTraffic="true"`** —— 那会解除整个应用的明文限制，对一个代理应用来说是不可接受的安全退化。

> 这个问题在实测中的表现极具迷惑性：服务正常启动、代理正常工作，只有首页速率恒为 `0 B/s`。因为订阅流的 `catch` 是个空实现，异常被静默吞掉，日志里什么都看不到。**空 catch 是这类隐性故障的温床**，所有异常路径至少要留一条日志。

### 9.1 默认入站为什么带随机凭据

初版的默认入站是 `0.0.0.0:8080` 且免认证，理由是「开箱即用」。安全审计指出这个取舍的代价被低估了，实际有三层，一层比一层重：

1. **蹭流量**：同网段任何人扫到 8080 就能用，跑的是用户付费的机场节点。
2. **归因**：攻击者的行为在上游看来全是用户干的。
3. **跳板**（最容易被忽略）：路由表里有一条无条件前置的「私有 IP 走直连」（`ip_is_private → direct`），于是未认证的人可以 `CONNECT 192.168.x.x:任意端口` 打进整个局域网——NAS、路由器管理页、打印机——以及 `CONNECT 127.0.0.1:任意端口` 打进**手机自己的回环接口**。

改成默认生成随机凭据。这是唯一不牺牲开箱即用的选项：用户本来就要把 IP 和端口抄到 Switch 上，顺带多抄两行的边际成本很小，而首页把账号密码和地址放在一起显示。

凭据用 `SecureRandom`（可预测的伪随机等于没有认证），字母表剔掉了 `0O1lI`——这串要靠用户对着电视机或游戏机的软键盘一个个敲，认错一个字符的排查成本远高于多两位熵。剩余 32 字符、12 位长度约 60 bit，对局域网爆破足够。

**没有采用的方案**：默认改绑 `127.0.0.1` 会直接摧毁产品前提；开局弹一个风险确认框会伤害首次体验，而且默认已经安全之后也没有可确认的东西了。

### 10.3 纯 JVM 模块里的 API level 陷阱

`core:model`、`core:config` 是 `java-library` 模块，不套 Android Gradle Plugin。好处是编译快、单测直接在桌面 JVM 上跑；代价是**这两个模块完全脱离 Android 的 API level 检查**：

- AGP 的 lint 与 desugaring 都不作用于它们；
- `compileSdk` / `minSdk` 对它们没有任何约束力；
- 单元测试跑在桌面 JVM 上，`java.*` 下的东西一律可用。

于是一次错误的 API 选择会一路绿灯地走到发版：编译过、244 个单测全过、`assembleRelease` 也过，只有装到 Android 7.x 的真机上、用户点「导入分享链接」的那一刻才 `NoClassDefFoundError`。

实际踩到的就是 `java.util.Base64`（API 26+，而 minSdk 是 24），出现在 `ShareLink.decodeBase64` 与 `ShareLinkExporter`。改用 `kotlin.io.encoding.Base64`：它是 kotlin-stdlib 里的纯 Kotlin 实现，不依赖任何平台 API，且自 Kotlin 2.2 起为稳定 API。

**规则：纯 JVM 模块里只用 kotlin-stdlib 与 Java 8 以内的 API。** 需要 Base64、时间、Stream 这类能力时，优先找 Kotlin 标准库的对应物，而不是 `java.util.*` 里同名的那个。同理，这些模块里也不能出现 `android.util.Base64` —— 它在桌面 JVM 上是个空壳，单测会报 "not mocked"。

---

## 11. 工程化

### 11.1 环境要求

| 工具 | 版本 | 本机现状 |
| --- | --- | --- |
| JDK | 17 | 已就绪：`C:\Users\Administrator\.jdks\jdk-17.0.20+8` |
| Gradle | 9.7.1 | 已就绪，Wrapper 已生成 |
| AGP | 9.3.2 | — |
| Go | 1.26.2 | 已就绪 |
| Android SDK | Platform 37.0 + Build-Tools 37.0.0 | 已就绪：`F:\Android-build\Sdk` |
| Android NDK | r28c（28.2.13676358） | 已就绪 |
| gomobile / gobind | SagerNet fork v0.1.12 | 已就绪 |

**版本选型上踩过的坑**（三者互相牵制，不能单独降级）：

| 现象 | 根因 |
| --- | --- |
| Hilt 报 "only compatible with AGP 9.0.0 or higher" | Hilt 2.54 起要求 AGP 9 |
| 31 个 AndroidX 依赖的 AAR 元数据校验失败 | Compose BOM 2026.08、activity 1.13、lifecycle 2.11 均要求 AGP 9 |
| "Failed to apply plugin 'org.jetbrains.kotlin.android'" | **AGP 9 内置 Kotlin 支持**，必须移除 `kotlin-android` 插件与 `kotlin { jvmToolchain() }` 块，JVM 目标改由 `compileOptions` 指定 |
| 16 个依赖要求 "compile against version 37 or later" | Compose 1.12 / core-ktx 1.19 要求 compileSdk 37 |

结论是整条链必须一起升到 AGP 9 + Gradle 9 + compileSdk 37。留在 AGP 8 意味着把整个 AndroidX 栈回退约八个月。

> **cmdline-tools 23.0 的变化**：`sdkmanager` 已弃用，替代品是 `android` CLI，包 ID 分隔符从 `;` 改为 `/`：
> `android sdk install "platforms/android-37.0" "build-tools/37.0.0" "ndk/28.2.13676358"`
> 它的下载进度条输出量极大，直接接管道会把 PowerShell 进程撑崩（`0xC0000409`），须重定向到文件。

需要设置的环境变量：

```
JAVA_HOME        = C:\Users\Administrator\.jdks\jdk-17.0.20+8
ANDROID_HOME     = F:\Android-build\Sdk
ANDROID_NDK_HOME = F:\Android-build\Sdk\ndk\28.2.13676358
```

> 系统里原有的 `ANDROID_HOME` 指向 `F:\Android-build\platform-tools`，这是错的 —— 应指向 SDK **根目录**。

**镜像**：GitHub Releases 在本机实测约 1.5 MB/min（182 MB 的 JDK 要两小时），清华/腾讯镜像同一文件 9~12 秒完成。JDK、Gradle 发行版、Go 模块都应走国内镜像，具体地址见 README。Android SDK 从 `dl.google.com` 下载很快（147 MB 用时 5 秒），不需要镜像。

### 11.2 构建产物

- `assembleDebug` / `assembleRelease`，按 ABI split：`armeabi-v7a`、`arm64-v8a`、`x86_64`
- Release 开启 R8（`minifyEnabled` + `shrinkResources`），保留 gomobile 生成类的 keep 规则
- AAB 用于 Play 分发，APK 用于 GitHub Release 与 F-Droid

### 11.3 CI（GitHub Actions）

| Job | 内容 |
| --- | --- |
| `lint` | ktlint / detekt / Android Lint |
| `test` | `core:config`、`core:model` 单元测试（纯 JVM，秒级） |
| `build-native` | 缓存 Go module，构建三 ABI 的 `libnice.aar`，校验 16 KB 对齐 |
| `build-app` | 组装 Release APK/AAB，签名，上传 artifact |
| `release` | 打 tag 时自动发布 GitHub Release |

### 11.4 测试策略

| 层级 | 范围 | 工具 |
| --- | --- | --- |
| 单元测试 | 配置生成器（对每个协议 × 传输 × TLS 组合断言输出 JSON）、全部链接解析器、订阅格式探测 | JUnit5 + Truth |
| 快照测试 | 一份「典型完整配置」的生成结果与 golden 文件逐字符比对，兜住单项断言想不到的意外改动。需要变更时用 `-Dgolden.update=true` 重新生成并人工 review diff | JUnit5 + golden 文件 |
| 内核契约测试 | `core:config` 的 golden 快照直接喂给 `libnice.ValidateConfig()`，断言零错误。配置生成器是照文档手写的，与内核之间没有编译期约束 —— 内核废弃一个字段，生成器毫无感知，问题要到用户点「启动」时才暴露。这是唯一能提前拦住它的防线 | Go test（`native/libnice/contract_test.go`），可在桌面运行，无需真机 |
| 集成测试 | 起本地 sing-box 服务端，跑通"客户端 → 入站 → 出站 → 目标"全链路 | Instrumented Test + 本地测试服务器 |
| UI 测试 | 关键路径：添加节点、开启服务、切换节点 | Compose UI Test |
| 手工测试 | 真机 × Android 8/11/13/15，Wi-Fi/热点/蜂窝三种网络 | 测试用例清单 |

### 11.5 包体实测与 NFR-6 的修正

Release 构建（R8 + 资源压缩 + ABI split）实测：

| ABI | APK |
| --- | --- |
| arm64-v8a | 31.5 MB |
| armeabi-v7a | 30.0 MB |
| x86_64 | 33.6 MB |
| universal | 89.5 MB |

**体积几乎全在 Go 内核上**。arm64 包的构成：

| 内容 | 体积 | 占比 |
| --- | --- | --- |
| `lib/arm64-v8a/libnice.so` | 28.57 MB | 91% |
| `classes.dex`（R8 后） | 2.39 MB | 8% |
| 资源 + 其余 | 约 0.5 MB | 1% |

R8 的效果是好的（dex 从 4.93 MB 压到 2.39 MB），应用侧已经没有可观的优化空间。

#### 构建标签对 `.so` 体积的实际影响

| 标签集 | `.so` |
| --- | --- |
| 当前全量 | 28.57 MB |
| 去掉 `with_wireguard` | 28.03 MB（省 0.54） |
| 去掉 `with_quic` | 25.42 MB（省 3.15） |

QUIC 是最大的单项，但 Hysteria2 是 P0 需求，不能去；WireGuard 只省 0.54 MB，不值得砍掉一个协议。**Go 内核的体积基本是不可压缩的下限**——sing-box 加 Go 运行时加全套密码学实现就是这么大。原定的 25 MB 指标在制定时没有这个数据支撑，即使应用代码为零也达不到，因此修正为 33 MB。

作为参照，v2rayNG 的单 ABI 包也在同一量级。

#### 一个可选的取舍：压缩原生库

`.so` 目前在 APK 里**不压缩存储**（AGP 对 minSdk 23+ 的默认行为），这样安装时不必解压、运行时直接从 APK 映射。若改为 `packaging.jniLibs.useLegacyPackaging = true`：

| | 下载体积 | 安装后磁盘 |
| --- | --- | --- |
| 当前（不压缩） | 31.5 MB | 31.5 MB |
| 压缩存储 | **约 13 MB** | 约 42 MB（APK + 解压副本） |

`.so` 的压缩率是 34.8%，下载体积能降 59%。代价是安装后多占约 10 MB 磁盘，且这是 Google 已不推荐的旧行为。

**当前保持不压缩**，理由是运行时特性更好（无解压副本、启动更快）。但本项目主要经 GitHub Release 与 F-Droid 直接分发 APK，下载体积对用户是直接可感的成本——如果后续收到体积方面的反馈，这是一个一行配置即可切换的杠杆。经 Play 以 AAB 分发时，Play 自身会重新压缩投递包，该设置的影响较小。

#### R8 踩到的坑

首次 release 构建在 `minifyReleaseWithR8` 阶段失败：snakeyaml（用于解析 Clash YAML 订阅）引用了 Android 上不存在的 `java.beans.*`。我们只用它解析 Map/List，不涉及 JavaBean 绑定，加 `-dontwarn java.beans.**` 即可。

---

## 12. 里程碑

| 阶段 | 目标 | 交付物 |
| --- | --- | --- |
| **M0 · 地基** | 环境搭建、工程骨架、`libnice.aar` 跑通 | 已完成 |
| **M1 · MVP** | Every Proxy 平替 | 已完成，真机验证通过 |
| **M2 · 核心差异化** | 上游协议接入 | 已完成 |
| **M3 · 分流** | 路由能力 | 已完成 |
| **M4 · 观测** | 监控诊断 | 已完成 |
| **M5 · 打磨** | 发布就绪 | 已完成（PAC、二维码、磁贴、开机自启、加密备份） |
| **M6 · 发布** | 1.0 | 待办：CI、签名、国际化、保活指引、真机全量回归 |

---

## 13. 风险登记册

| ID | 风险 | 影响 | 概率 | 应对 |
| --- | --- | --- | --- | --- |
| ~~R-1~~ | ~~gomobile 构建 sing-box 失败（依赖树/NDK 兼容）~~ | — | — | **已解除**：三 ABI 的 `libnice.aar` 已成功构建并通过产物校验 |
| R-2 | sing-box 版本升级导致配置 schema 破坏性变更 | 中 | 高 | 锁定版本；用「内核契约测试」在 CI 中拦截；升级走独立分支 |
| R-3 | 国产 ROM 后台杀进程导致服务中断 | 高 | 高 | 四层防御，见 §6.8.1：内核健康检查（≤10 秒）、退避重试、`START_STICKY` + WorkManager 看门狗、冷启动补拉。配合电池优化白名单与厂商自启动引导 |
| R-4 | Google Play 以「代理/VPN 类应用」政策拒审 | 中 | 中 | 主发布渠道定为 GitHub + F-Droid；Play 作为可选 |
| R-5 | 包体超出 25 MB 目标 | 低 | 中 | ABI split、裁剪 Go build tag、R8 |
| R-6 | Hysteria2 等 QUIC 协议在网络切换时断流 | 中 | 高 | `NetworkCallback` 触发内核重启；UI 提示重连中 |
| R-7 | 链接解析器覆盖不全导致导入失败 | 中 | 高 | 高覆盖率参数化测试；解析失败时保留原文并允许手动修正 |
| R-8 | GPL-3.0 与 F-Droid 构建要求（可复现构建） | 低 | 中 | 提供完整构建脚本，Go 依赖锁定 |

---

## 14. 附录

### 14.1 协议参数矩阵

| 协议 | 必填 | 可选关键项 | 传输层 | TLS |
| --- | --- | --- | --- | --- |
| `http` | server, port | username, password, path, headers | — | 支持 |
| `socks` | server, port | version(4/4a/5), username, password, udp_over_tcp | — | — |
| `shadowsocks` | server, port, method, password | plugin, plugin_opts, udp_over_tcp, multiplex | — | — |
| `vmess` | server, port, uuid | security, alter_id, global_padding, authenticated_length, packet_encoding | 全部 | 支持 |
| `vless` | server, port, uuid | flow(`xtls-rprx-vision`), packet_encoding(`xudp`) | 全部 | 支持 + REALITY |
| `trojan` | server, port, password | multiplex | 全部 | 支持 |
| `hysteria2` | server, port, password | up_mbps, down_mbps, obfs, server_ports(端口跳跃), hop_interval, brutal_debug | — | 必需 |
| `tuic` | server, port, uuid, password | congestion_control, udp_relay_mode, zero_rtt_handshake, heartbeat | — | 必需 |
| `anytls` | server, port, password | idle_session_check_interval, min_idle_session | — | 必需 |
| `wireguard` | 1.11+ 迁移为 `endpoints` | — | — | — |
| `ssh` | server, port, user | password, private_key, host_key | — | — |

传输层可选值：`ws`（path/headers/max_early_data）、`grpc`（service_name）、`http`（host/path/method）、`httpupgrade`（host/path）、`quic`。

TLS 可选值：`server_name`、`alpn`、`insecure`、`min_version`/`max_version`、`certificate`、`utls`（enabled/fingerprint）、`reality`（public_key/short_id）、`ech`。

### 14.2 参考资料

- sing-box 官方文档：<https://sing-box.sagernet.org/>
- sing-box 1.11 / 1.12 / 1.13 迁移指南：<https://sing-box.sagernet.org/migration/>
- Every Proxy 官网与 FAQ：<https://www.everyproxy.co.uk/>
- Android 前台服务类型：<https://developer.android.com/develop/background-work/services/fgs/service-types>
- Android 16 KB 页面支持：<https://developer.android.com/guide/practices/page-sizes>

### 14.3 进度

**M0 · 地基 —— 已完成**

| 项 | 状态 |
| --- | --- |
| JDK 17 + Gradle 9.7.1 + AGP 9.3.2 | 完成 |
| Android SDK 37 + Build-Tools 37.0.0 + NDK r28c | 完成 |
| gomobile / gobind（SagerNet fork v0.1.12） | 完成 |
| 多模块工程骨架与版本目录 | 完成 |
| `core:model` 领域模型 | 完成 |
| `core:config` 配置生成器 | 完成，31 项单测 + 快照测试全绿 |
| `libnice.aar` 三 ABI 构建 | 完成，31 MB，64 位库 16 KB 对齐 |
| 内核契约测试 | 通过 |

**M1 · MVP —— 主干已跑通**

已实现：`core:common` / `database` / `datastore` / `network` / `data` / `designsystem` / `service` 七个模块 + `:app`（Compose 首页与入站配置页）。

真机验证结果（雷电模拟器 14，Android 14 / API 34 / x86_64）：

| 验证项 | 结果 |
| --- | --- |
| 应用启动、内核加载 | 通过，无崩溃 |
| `mixed` 入站监听 `0.0.0.0:8080` | 通过 |
| Clash API 监听 `127.0.0.1:19462`（随机端口 + 随机密钥） | 通过，符合 NFR-9 |
| 经 HTTP CONNECT 访问 HTTPS | 通过，返回 204 |
| 经明文 HTTP 访问 | 通过，返回 200/204 |
| **同一端口上的 SOCKS5 协商** | **通过，返回 204** —— `mixed` 入站的协议自动协商生效，对齐 Every Proxy |
| 前台服务类型 | 通过，`types=40000000` 即 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` |
| 常驻通知 | 通过，`importance=2`（LOW）、`category=service`、含停止按钮 |
| 内核错误向用户透出 | 通过，DNS 配置错误以可读文案呈现在首页 |
| 首页实时速率 | **未验证** —— 明文 HTTP 拦截（P-14）已修复但未回归 |

M1 剩余：实时速率回归、PAC 服务器、省电模式、开机自启、快捷设置磁贴。

**M2 · 节点与订阅 —— 已完成**

| 项 | 状态 |
| --- | --- |
| 分享链接解析（9 类协议） | 完成，`ss` / `vmess` / `vless` / `trojan` / `hysteria2` / `tuic` / `anytls` / `socks` / `http` |
| 订阅解析（5 种格式） | 完成，Base64 链接列表 / 明文列表 / Clash YAML / sing-box JSON / SIP008 |
| `subscription-userinfo` 流量与到期解析 | 完成 |
| 数据层：servers / groups / rules / rule_sets | 完成 |
| 节点管理页（分组、测速、导入、切换） | 完成 |
| 节点热切换（Clash API，不重启内核） | 完成 |

解析器共 **36 项单测**，样本贴近各家客户端实际导出的形态。刻意覆盖的边界：Base64 的四种变体、SIP002 与传统 ss 编码、密码含未编码 `@`、IPv6 字面量、`allowInsecure` 的三种别名、端口跳跃的连字符转冒号。

**M3 · 分流 —— 已完成**

规则引擎、规则集、四种预设模板（全局代理 / 绕过大陆 / 全局直连 / 自定义）、按客户端来源 IP 分流、规则拖拽排序。

**M4 · 观测 —— 已完成**

连接监控（客户端 IP、目标、命中规则、出站、上下行、手动断开）、实时日志、流量统计，全部经由 Clash API。

**M5 · UI 与打磨 —— 已完成**

四个一级页（首页 / 节点 / 分流 / 更多）+ 八个二级页，统一毛玻璃视觉，见 §8.3。

从 v2rayNG 移植并适配的功能：

| 功能 | 为什么值得抄 |
| --- | --- |
| **TCPing 测速** | 原来的测速走 Clash API，必须先启动内核。用户刚导入订阅想挑个能用的节点，却被要求先把代理跑起来 —— 顺序是反的。两种测速并存：TCPing 随时可测但只证明入口可达，真连接延迟准确但要内核在跑 |
| **规则锁定** | 解决了 `applyTemplate` 会清空一切的问题。没有它，用户切一次模板就丢掉自己写的规则，从此不敢碰模板。保留的规则要排在模板规则**之前** —— 手写规则通常是要覆盖默认行为的 |
| **订阅备注正则过滤** | 机场普遍在订阅里塞「剩余流量」「官网地址」这类伪装成节点的公告。正则非法时视为不过滤而非全部丢弃：把节点全滤掉会让订阅看起来「空了」，用户完全不知道发生了什么 |
| 节点搜索 / 按延迟排序 / 删除重复 / 删除无效 | 导入几百节点的订阅时是刚需。判重用「协议+地址+端口+参数」而非节点名 —— 同一节点在不同订阅里往往叫不同名字 |
| 订阅后台自动更新 | 单个 15 分钟周期的 Worker 自行判断哪些到点了，而不是每个订阅各排一个任务（后者任务数随订阅数线性增长，也更费电） |
| 二维码扫码与生成 | 扫码同时支持相机与相册选图：分享场景里二维码常以截图形式发来 |
| 快捷设置磁贴 / 开机自启 / 桌面快捷方式 | 快捷方式用动态而非 `shortcuts.xml`：静态方式的 `targetPackage` 只能写死，debug 包带 `.debug` 后缀会失效 |

**明确不采纳**的 v2rayNG 功能（均依赖 `VpnService`，与本项目定位冲突）：分应用代理、VPN 接口地址/MTU、FakeDNS、Hev TUN、root 透明代理。Xray 专属的 Fragment 分片 sing-box 无对应能力。

值得记录的一点：v2rayNG 有个功能叫 "Share VPN with LAN / tethered devices (root users)" —— 把流量分享给局域网与 USB 共享设备**需要 root**。这恰好是本项目的原生形态，不需要 root 也不需要 VPN 权限。

**M6 · PAC 与备份 —— 已完成**

- **PAC 服务器**：sing-box 不提供该能力，由应用自身的 `ServerSocket` 实现。脚本按请求方的 `Host` 头动态生成 —— 设备可能同时挂在 Wi-Fi 和热点上，客户端从哪个网段进来就该给哪个地址，给错了会指向一个它路由不到的 IP。局域网与本机地址默认绕行，否则客户端访问网关自己会绕一圈回来形成回环。
- **加密备份**：备份含节点密码与入站凭据，一份明文备份泄露等于把所有节点拱手让人，所以加密是强制的，不提供「不加密导出」选项。AES-GCM + PBKDF2（210k 次迭代），GCM 自带完整性校验，密码错误或文件被篡改都会直接失败而不是解出乱码。文件经 SAF 落到用户自选位置，应用不持有任何外部存储权限。

### 14.4 修订记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1.7 | 2026-08-27 | 保活体系：新增 §6.8.1「分层防御」——内核健康检查、退避重试（`RetryPolicy`）、WorkManager 看门狗、冷启动补拉、落盘的运行意图；澄清电池优化白名单同时是 Doze 与后台启动限制的枢纽；记录 `WIFI_MODE_FULL_HIGH_PERF` 在 API 34+ 被替换为仅屏幕亮时生效的 LOW_LATENCY 这一陷阱 |
| v1.6 | 2026-08-27 | 优化与加固：凭据落库加密（Keystore + AES-GCM）、`CredentialState` 取代节点名前缀标记、配置指纹热更新与省电模式、QUIC 切网自愈、Compose 渲染优化（毛玻璃节点合并、动画量化、稳定性配置）、仓储层 `flowOn`；新增 §10.3 纯 JVM 模块的 API level 陷阱 |
| v1.5 | 2026-08-26 | 竞品功能移植（TCPing 双测速、规则锁定、订阅正则过滤、节点批量操作、后台自动更新、扫码与分享、磁贴与开机自启）；PAC 服务器与加密备份完成 |
| v1.4 | 2026-08-26 | M2/M3/M4 完成：分享链接与订阅解析、节点与分流数据层、四个一级页与七个二级页；§8.3 改写为毛玻璃设计系统 |
| v1.3 | 2026-08-26 | M1 实测修订：工具链整体升级到 AGP 9 + Gradle 9 + compileSdk 37（并记录三者互相牵制的原因）、新增 §10.2 明文 HTTP 与 Clash API、新增约束 C-10（DNS 不得 detour 到 direct）、补充 M1 真机验证结果 |
| v1.2 | 2026-08-26 | M0 构建阶段的实测修订：确定最终构建标签集（`with_ech`/`with_grpc` 需禁用，`badlinkname` 系列需启用）、16 KB 对齐仅适用 64 位、cmdline-tools 23.0 的 CLI 变更、javac 编码问题、R-1 风险解除 |
| v1.1 | 2026-08-26 | 依据实现阶段的验证结果修订：收窄 Go 绑定 API（去掉全部回调接口）、新增 §10.1 非 ASCII 路径约束、更新环境现状与测试策略、补充 M0 进度 |
| v1.0 | 2026-08-26 | 初版：需求分析、技术选型、架构与详细设计 |
