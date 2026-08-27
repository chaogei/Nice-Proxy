<#
.SYNOPSIS
    构建 libnice.aar —— Nice-Proxy 的 sing-box 内核绑定。

.DESCRIPTION
    构建标签取自 sing-box v1.13.19 官方的 Android 构建配置
    （cmd/internal/build_libbox/main.go 中的 sharedTags），并按本项目需求做了裁剪：

      启用  with_quic              Hysteria2 / TUIC / HTTP3 必需
            with_utls              REALITY 与 TLS 指纹伪装必需
            with_wireguard         WireGuard 出站
            with_clash_api         Kotlin 侧获取流量/连接/日志与切换节点的唯一通道
            badlinkname            sing-box 依赖 go:linkname，缺失会链接失败
            tfogo_checklinkname0   同上，tfo-go 需要

      禁用  with_gvisor            仅 TUN 需要。本应用不做 TUN，这是体积削减最大的一项
            with_naive_outbound    用不到，且要求 API 23+
            with_tailscale         用不到
            with_ech               1.13 起 ECH 已迁移到标准库，该标签被废弃，
                                   显式传入会直接触发编译错误
            with_grpc              不加反而使用 sing-box 自带的轻量 gRPC 实现，
                                   体积更小，更适合移动端
            with_dhcp              官方仅在 Apple 平台启用

    实测产物：arm64 单 ABI 约 10 MB，全部 LOAD 段 16 KB 对齐。

.PARAMETER Abis
    目标 ABI，默认三个都构建。调试时可只构建 arm64 以加快速度。

.EXAMPLE
    .\build.ps1
    .\build.ps1 -Abis arm64
#>
param(
    [ValidateSet('arm64', 'arm', 'amd64')]
    [string[]]$Abis = @('arm64', 'arm', 'amd64'),

    [switch]$SkipToolInstall
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $scriptDir

# gomobile 会把 Go 的文档注释原样搬进生成的 Java 文件，再用 javac 编译。
# javac 的默认源码编码取自 file.encoding，在中文 Windows 上是 GBK，
# 遇到注释里的中文会报「编码 GBK 的不可映射字符」。
$savedJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

try {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (-not $sdkRoot) {
        throw 'ANDROID_HOME 或 ANDROID_SDK_ROOT 未设置，须指向 Android SDK 根目录（不是 platform-tools 子目录）'
    }
    if (-not $env:ANDROID_NDK_HOME) {
        throw 'ANDROID_NDK_HOME 未设置，需要 NDK r28 或更高版本（Android 15 的 16 KB 内存页要求）'
    }
    if (-not $env:JAVA_HOME) {
        throw 'JAVA_HOME 未设置，需要 JDK 17'
    }

    if (-not $SkipToolInstall) {
        Write-Host '==> 安装 SagerNet fork 的 gomobile' -ForegroundColor Cyan
        # 必须用 SagerNet 的 fork，上游 golang.org/x/mobile 处理 sing-box 依赖树时有已知问题
        go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
        go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12
    }

    $goBin = if ($env:GOBIN) { $env:GOBIN } else { Join-Path (go env GOPATH) 'bin' }
    $env:PATH = "$goBin;$env:PATH"

    Write-Host '==> 同步依赖' -ForegroundColor Cyan
    go mod tidy

    $version = '1.13.19'
    $target = ($Abis | ForEach-Object { "android/$_" }) -join ','
    $tags = 'with_quic,with_utls,with_wireguard,with_clash_api,badlinkname,tfogo_checklinkname0'
    $ldflags = @(
        "-X github.com/sagernet/sing-box/constant.Version=$version"
        '-X internal/godebug.defaultGODEBUG=multipathtcp=0'
        '-s -w -buildid='
        # sing-box 大量使用 go:linkname 访问运行时内部符号，必须关闭链接期检查
        '-checklinkname=0'
    ) -join ' '

    $outDir = Join-Path $scriptDir '..\..\libs'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $out = Join-Path (Resolve-Path $outDir) 'libnice.aar'

    Write-Host "==> gomobile bind [$target]" -ForegroundColor Cyan
    $gomobileArgs = @(
        'bind'
        "-target=$target"
        '-androidapi', '24'
        '-javapkg=com.niceproxy'
        '-libname=nice'
        '-trimpath'
        '-buildvcs=false'
        '-ldflags', $ldflags
        "-tags=$tags"
        '-o', $out
        '.'
    )
    & "$goBin\gomobile.exe" @gomobileArgs
    if ($LASTEXITCODE -ne 0) { throw "gomobile bind 失败，退出码 $LASTEXITCODE" }

    $size = [math]::Round((Get-Item $out).Length / 1MB, 1)
    Write-Host "==> 完成：$out ($size MB)" -ForegroundColor Green

    # 16 KB 内存页只存在于 64 位设备，armeabi-v7a 保持 4 KB 对齐是正确的
    Write-Host '==> 校验 64 位原生库的 16 KB 页对齐' -ForegroundColor Cyan
    $readelf = Join-Path $env:ANDROID_NDK_HOME 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
    $inspect = Join-Path $env:TEMP 'libnice-align-check'
    Remove-Item $inspect -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item $out "$inspect.zip" -Force
    Expand-Archive "$inspect.zip" -DestinationPath $inspect -Force

    $abi64 = @('arm64-v8a', 'x86_64')
    $bad = @()
    Get-ChildItem "$inspect\jni" -Recurse -Filter '*.so' |
        Where-Object { $abi64 -contains $_.Directory.Name } |
        ForEach-Object {
            $minAlign = & $readelf -l $_.FullName |
                Select-String -Pattern '^\s+LOAD' |
                ForEach-Object { [Convert]::ToInt64((($_.Line.Trim() -split '\s+')[-1]), 16) } |
                Measure-Object -Minimum |
                Select-Object -ExpandProperty Minimum
            if ($minAlign -lt 16384) {
                $bad += "$($_.Directory.Name)/$($_.Name) 最小 LOAD 对齐为 $minAlign 字节"
            } else {
                Write-Host "    $($_.Directory.Name) 对齐 $minAlign 字节" -ForegroundColor Green
            }
        }
    if ($bad) {
        $bad | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        throw '64 位原生库未满足 Android 15 的 16 KB 内存页要求'
    }
}
finally {
    $env:JAVA_TOOL_OPTIONS = $savedJavaToolOptions
    Pop-Location
}
