<#
.SYNOPSIS
    生成发布签名密钥，并输出需要填进 GitHub Secrets 的四项内容。

.DESCRIPTION
    签名私钥**不能**放进仓库或 workflow：本项目仓库是公开的，私钥一旦泄露，
    任何人都能签出冒充本项目的更新包，而 Android 只认签名不认来源。
    所以它只能以 Secret 的形式存在，且必须由你手动创建一次。这个脚本把
    「一次」的成本压到最低。

    口令是随机生成的 —— 密钥库只由 CI 使用，人不需要记忆，那就没有理由用弱口令。

    **务必离线备份 release.jks 和口令。** 丢了之后无法给已安装的用户推送更新，
    他们只能卸载重装，所有配置一并丢失。

.PARAMETER Output
    密钥库路径，默认为仓库根目录的 release.jks（已被 .gitignore 忽略）。

.PARAMETER Alias
    密钥别名，默认 niceproxy。

.EXAMPLE
    pwsh ./scripts/new-signing-key.ps1
#>
param(
    [string]$Output = 'release.jks',
    [string]$Alias = 'niceproxy'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Push-Location $repoRoot

try {
    $keytool = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME (Join-Path 'bin' 'keytool')
    } else {
        'keytool'
    }
    if (-not (Get-Command $keytool -ErrorAction SilentlyContinue)) {
        throw "找不到 keytool。请设置 JAVA_HOME 指向 JDK 17，或把 JDK 的 bin 加入 PATH。"
    }

    if (Test-Path $Output) {
        throw "$Output 已存在。覆盖它意味着旧密钥作废、已安装的用户再也收不到更新；确认要重来的话请先手动删除并备份。"
    }

    # 18 字节 → 24 个 base64 字符。剔掉 +/= 是因为这串要经过 shell 与
    # properties 文件两轮转义，省去转义出错的可能。
    $raw = New-Object byte[] 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($raw)
    $password = ([Convert]::ToBase64String($raw)) -replace '[+/=]', 'x'

    & $keytool -genkeypair -v `
        -keystore $Output `
        -alias $Alias `
        -keyalg RSA -keysize 4096 `
        -validity 10000 `
        -storepass $password -keypass $password `
        -dname "CN=Nice-Proxy, OU=Release, O=Nice-Proxy, C=CN" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool 失败，退出码 $LASTEXITCODE" }

    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Output))

    # 写文件而不是打印到终端：终端内容常被截图、粘进聊天记录或被 CI 日志收集，
    # 私钥不该出现在任何这类地方。
    $secretsFile = 'SIGNING-SECRETS.txt'
    @"
把下面四项逐个填到 GitHub：
  Settings -> Secrets and variables -> Actions -> New repository secret

填完后删除本文件（它含私钥明文）。
release.jks 请离线备份 —— 丢了就无法再给已安装的用户推送更新。

============================ KEYSTORE_PASSWORD ============================
$password

================================ KEY_ALIAS ================================
$Alias

============================== KEY_PASSWORD ===============================
$password

============================= KEYSTORE_BASE64 =============================
$base64
"@ | Set-Content $secretsFile -Encoding UTF8

    Write-Host "密钥库已生成：$Output" -ForegroundColor Green
    Write-Host "四项 Secret 已写入：$secretsFile" -ForegroundColor Green
    Write-Host ''
    Write-Host '下一步：把那四项填进 GitHub Secrets，然后删除 SIGNING-SECRETS.txt' -ForegroundColor Cyan
    Write-Host '并把 release.jks 离线备份好。' -ForegroundColor Cyan
}
finally {
    Pop-Location
}
