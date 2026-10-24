# 本机 App 开发环境（AI 使用入口）

核实日期：**2026-10-24**。适用主机：Windows 10 专业版 64 位，版本 `10.0.19045`，用户目录 `C:\Users\admin`。本文由主代理与 DeepSeek 分工检查本机后整理；主要记录 App 开发及其后端、测试、构建所需环境。

本文位置：`D:\AI-USE\Android\chengyu\本机App开发环境.md`。当前目录的 `AGENTS.md` 引用本文，供后续 AI 发现。跨项目旧总表位于 `C:\Users\admin\.pi\environment\本机技术环境清单.md`，其检查日期为 2026-10-13；涉及本文已复核的条目时，以本文的较新检查结果为准。本文没有覆盖修改旧总表。

## 1. 给接手 AI 的结论

- **Android 原生开发已有主要命令行基础**：JDK 17、Gradle 8.13、Android SDK、ADB、模拟器及一个 API 35 AVD；本次没有构建 APK，也没有开机验收 AVD。
- **Web/H5/PWA 及 JavaScript 应用已有 Node.js/npm/pnpm 基础**。React、Vue、Vite、Electron、React Native、Expo、Capacitor 等应从目标项目的 `package.json` 和锁文件判断，不能因 Node 已安装就认为相关框架和打包链已经齐备。
- **C# 应用已有 .NET 10 SDK**，但工作负载列表为空。磁盘上存在 Android/iOS/MAUI 的清单，不能把清单目录或注册表安装项当成工作负载已可用的证据。
- **Python 后端已有可复用环境**：默认 3.13.5；另有 3.10 环境安装了 FastAPI/Uvicorn。优先为新项目建立独立环境，按目标版本安装依赖。
- **Windows C/C++ 已有 MSYS2 GCC、VS2017/MSVC、Windows SDK、CMake、vcpkg**；不等于 Android JNI 工具链齐全，两个 Android SDK 都未发现 NDK。
- **Linux 开发可使用两套 WSL2 Ubuntu，容器可使用正在响应的 Docker Desktop**。Docker 缓存中有 Maven/JDK 21 镜像，以及 PostgreSQL/Redis 等镜像；已有容器服务不应直接当作新项目数据库。
- 本次搜索范围内**未发现 Flutter/Dart、Android Studio、HBuilderX、DevEco Studio、Qt、Unity，以及 Windows 原生 Rust/Go/Maven 入口**。这是限定范围的搜索结果，不能推断整块硬盘绝对没有便携副本。

## 2. AI 操作约定与验证级别

1. Shell 是 **Windows PowerShell**。使用 Windows 路径；执行带空格的路径用调用运算符 `&`。不要直接套用 Bash 的环境变量赋值、`export` 或路径语法。
2. 先读取目标项目约定、依赖清单与锁文件。已有项目优先使用其 Wrapper 和依赖版本，不根据本文擅自升级或重装全局环境。
3. 本文的“版本通过”表示程序可启动并返回版本；“文件核实”表示存在相应入口或组件；“服务响应”表示检查时能访问服务端。三者均不替代项目构建、业务运行和设备验收。
4. Android 双 SDK、Python 多版本、Windows/WSL/容器是不同环境，必须明确实际执行入口。不要把 Windows 路径直接交给 Linux 编译器。
5. 本文不存储数据库密码、Token、签名密钥或设备口令。开始新项目前另行确认授权与配置来源。
6. 当前工作区在盘点前为空，没有 App 源码，因此本次没有执行应用构建、安装、签名、发布或业务测试。

## 3. 主机与空间

| 项目 | 核实结果 |
|---|---|
| 系统 | Windows 10 专业版 x64，10.0.19045 |
| 内存 | 约 31.9 GiB |
| C 盘 | 检查时约 83 GiB 可用；包含用户目录及多种工具缓存 |
| D 盘 | 检查时约 1.31 TiB 可用；当前工作区及 Android 工具链在此 |
| E 盘 | 检查时约 504 GiB 可用；有另一套 PyCharm |
| 当前工作目录 | `D:\AI-USE\Android\chengyu` |

可用空间和服务状态会变化，开始大型下载或构建前重新检查。

## 4. Android、Java、Gradle

### 4.1 主要入口

| 工具 | 版本/状态 | 绝对入口 |
|---|---|---|
| 默认 Java | Temurin OpenJDK 17.0.19+10；版本通过 | `D:\AI-USE\path\jdk-17\bin\java.exe` |
| 默认 javac | 17.0.19；版本通过 | `D:\AI-USE\path\jdk-17\bin\javac.exe` |
| 备用 Java 11 | Temurin 11.0.31+11；版本通过 | `D:\AI-USE\path\jdk-11\bin\java.exe` |
| 另一套 JDK 17 | 17.0.16+8；目录核实，未验证启动 | `D:\AI-USE\AndroidToolchain\jdk17\jdk-17.0.16+8` |
| Gradle | 8.13；`--version` 通过，使用默认 JDK 17 | `D:\AI-USE\path\gradle-8.13\bin\gradle.bat` |
| 默认 ADB | Android Debug Bridge 1.0.41，platform-tools 37.0.0-14910828；版本通过 | `D:\AI-USE\path\android-sdk\platform-tools\adb.exe` |
| 模拟器 SDK 内 ADB | 同为 platform-tools 37.0.0；版本通过 | `D:\AI-USE\AndroidEmulator\Sdk\platform-tools\adb.exe` |
| fastboot | 文件核实；未连接设备 | `D:\AI-USE\path\android-sdk\platform-tools\fastboot.exe` |
| Android Emulator | 36.6.11；版本、AVD 列表通过；WHPX 加速检查显示可用 | `D:\AI-USE\AndroidEmulator\Sdk\emulator\emulator.exe` |
| 主 SDK sdkmanager | cmdline-tools 5.0；读取组件元数据，未联网列包 | `D:\AI-USE\path\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat` |
| 模拟器 SDK sdkmanager | cmdline-tools 21.0；读取组件元数据，未联网列包 | `D:\AI-USE\AndroidEmulator\Sdk\cmdline-tools\latest\bin\sdkmanager.bat` |

`cmdline-tools\latest` 是目录名，不代表两套 SDK 的工具版本相同。主 SDK 内的命令行工具版本较旧，遇到 SDK 元数据兼容问题时先确认实际入口。

### 4.2 两套 Android SDK 的组件区别

| 组件 | 主 SDK：`D:\AI-USE\path\android-sdk` | 模拟器 SDK：`D:\AI-USE\AndroidEmulator\Sdk` |
|---|---|---|
| platforms | `android-29`、`android-36` | `android-35`、`android-36` |
| build-tools | `29.0.2`、`29.0.3`、`35.0.0`、`36.0.0` | `35.0.0` |
| platform-tools | `37.0.0` | `37.0.0` |
| cmdline-tools/latest | `5.0` | `21.0` |
| emulator / system-images | 未发现 | Emulator 36.6.11；API 30 google_apis x86、API 35 google_apis x86_64 |
| NDK / ndk-bundle | 未发现 | 未发现 |
| SDK 内 CMake | 未发现 | 未发现 |
| licenses | 有 android-sdk-license 等 7 个许可记录文件；未重新执行许可接受 | 本次未重新接受许可 |

**选 SDK 时先看项目的 `compileSdk`、build-tools 和 NDK 要求。** 主 SDK 缺少 API 35 平台；仅凭 build-tools 35.0.0 不能满足 `compileSdk=35`。使用主 SDK 的此类工程尚需补平台或明确切换到包含该平台的 SDK；Gradle 是否会联网自动补装取决于工程及许可配置。API 36 平台在两套 SDK 都存在，实际 AGP/Gradle/JDK 兼容性仍按工程核对，本次没有验证具体版本组合。

不要为统一路径而直接移动、合并或删除 SDK。使用模拟器 SDK 构建时，还要检查它缺少的 build-tools/NDK 等组件。

### 4.3 环境变量、缓存与 AVD

| 变量/资源 | 当前值或路径 |
|---|---|
| `JAVA_HOME` | `D:\AI-USE\path\jdk-17` |
| `ANDROID_HOME` | `D:\AI-USE\path\android-sdk` |
| `ANDROID_SDK_ROOT` | `D:\AI-USE\path\android-sdk` |
| `ANDROID_AVD_HOME` | `D:\AI-USE\AndroidEmulator\avd` |
| `GRADLE_USER_HOME` | `D:\AI-USE\path\gradle-home` |
| `GRADLE_HOME` | 当前进程未设置；Gradle 通过 PATH 可调用 |
| Gradle Wrapper 缓存 | `D:\AI-USE\path\gradle-home\wrapper\dists`，发现 `gradle-8.13-bin` |
| Gradle Maven 依赖缓存 | `D:\AI-USE\path\gradle-home\caches\modules-2`，发现 AGP 8.12.2、8.13.2 及部分 Kotlin/AndroidX 组件 |
| AVD | `D:\AI-USE\AndroidEmulator\avd\AI_Discussion_API35.avd`；API 35、google_apis、x86_64、2 GiB RAM、无 Play Store |

缓存中存在组件不证明整个工程可离线构建，也不证明缓存的 AGP 与全局 Gradle 版本相互兼容。工程存在 `gradlew.bat` 时先用工程 Wrapper。

当前 PATH 中，Java/Gradle/ADB/sdkmanager 来自主工具目录，`emulator` 来自模拟器 SDK。启动 AVD 前明确系统镜像位于第二套 SDK；本次仅验证版本、AVD 枚举和加速条件，未验证完整启动流程。

Android 项目确需写 `local.properties` 时，可使用正斜杠避免 Java properties 的反斜杠转义问题，例如主 SDK 的 `sdk.dir=D:/AI-USE/path/android-sdk`。该配置应按当前项目选择，而非直接覆盖已有文件。

## 5. JavaScript、Web 与桌面封装基础

| 工具/资源 | 版本/状态 | 入口或路径 |
|---|---|---|
| Node.js | 24.14.1；版本通过 | `C:\Program Files\nodejs\node.exe` |
| npm | 11.11.0；版本通过 | `C:\Program Files\nodejs\npm.cmd` |
| pnpm | 11.24.0；版本通过 | `C:\Users\admin\AppData\Roaming\npm\pnpm.cmd` |
| npm 全局包目录 | 全局列表读取成功 | `C:\Users\admin\AppData\Roaming\npm\node_modules` |
| npm 缓存 | 查询得到的位置 | `C:\Users\admin\AppData\Local\npm-cache` |
| fnm | 1.39.0；版本通过，但未在当前 PATH 发现 | `C:\Users\admin\AppData\Local\Microsoft\WinGet\Packages\Schniz.fnm_Microsoft.Winget.Source_8wekyb3d8bbwe\fnm.exe` |
| fnm 版本目录 | 仅发现 `.downloads` 子目录，未发现可复用的已安装 Node 版本目录 | `C:\Users\admin\AppData\Roaming\fnm\node-versions` |

全局 npm 列表中发现 pnpm、PM2 7.0.3 及若干 AI/文档工具；没有把全局框架 CLI 当成开发前提。Yarn、Bun、Deno 在当前 PATH 未发现。

在 PowerShell 中优先调用 `npm.cmd`、`pnpm.cmd`，可避免同名 `.ps1` 入口受脚本策略影响。Electron/React Native/Expo/Capacitor 等项目的依赖、原生构建条件和打包能力本次未验收。Tauri 所需的 Windows 原生 Rust/Cargo 在本次搜索范围内未找到。

## 6. .NET、Windows 桌面与 C/C++

| 工具 | 版本/状态 | 入口或路径 |
|---|---|---|
| .NET SDK | 10.0.303；SDK/运行时列表读取成功 | `C:\Program Files\dotnet\dotnet.exe`；SDK 在 `C:\Program Files\dotnet\sdk` |
| .NETCore 运行时 | 8.0.32、9.0.21、10.0.11 | `C:\Program Files\dotnet\shared\Microsoft.NETCore.App` |
| ASP.NET Core 运行时 | 8.0.32、10.0.11 | `C:\Program Files\dotnet\shared\Microsoft.AspNetCore.App` |
| WindowsDesktop 运行时 | 9.0.21、10.0.11 | `C:\Program Files\dotnet\shared\Microsoft.WindowsDesktop.App` |
| .NET 工作负载 | `dotnet workload list` 为空；存在 Android/iOS/MAUI 清单，不代表工作负载安装完成 | `C:\Program Files\dotnet\sdk-manifests\10.0.100` |
| Visual Studio Community 2017 | 安装记录 15.9.64；vswhere 确认完整且可启动；未编译 | `D:\wengFiles\softwares\vs2017\Common7\IDE\devenv.exe` |
| MSVC 工具目录 | 14.16.27023；目录核实，当前 `cl` 不在 PATH | `D:\wengFiles\softwares\vs2017\VC\Tools\MSVC\14.16.27023` |
| MSVC 环境脚本 | 文件核实；需在合适的开发者终端初始化 | `D:\wengFiles\softwares\vs2017\VC\Auxiliary\Build\vcvars64.bat` |
| Windows SDK | Include 下发现 10.0.17763.0；安装记录 10.0.17763.132 | `C:\Program Files (x86)\Windows Kits\10` |
| MSYS2 GCC/G++ | 15.1.0；本次 G++ 版本通过 | `C:\msys64\mingw64\bin\gcc.exe`、`C:\msys64\mingw64\bin\g++.exe` |
| 旧 MinGW | 旧总表记载 6.3.0，本次未重新验证其版本 | `C:\MinGW\bin` |
| CMake | 4.3.0-rc3；版本通过，属于候选版本 | `C:\Program Files\CMake\bin\cmake.exe` |
| vcpkg | 命令入口核实；版本沿用旧总表记录，未重新执行 | `D:\vcpkg\vcpkg\vcpkg.exe`；`VCPKG_ROOT=D:\vcpkg\vcpkg` |
| WebView2 Runtime | 155.0.4283.62；注册表及版本目录核实 | `C:\Program Files (x86)\Microsoft\EdgeWebView\Application\155.0.4283.62` |

vswhere 只返回 VS2017；本次未发现 VS2022 实例。不要默认 VS2017 能满足当前框架所需的 MSVC/Windows SDK 版本。全局 `ninja`、`clang`、`cl` 在当前 PATH 未发现；MSVC 安装在磁盘上，属于需要初始化环境的情况。

2026-10-13 旧总表记载：MSYS2 曾受 Git 内部 bin 同名 DLL 干扰，调整 PATH 后 C++20 编译/运行通过。本次只复核了当前 GCC/G++ 入口和版本，没有重复编译该示例；不要随意把 Git 内部 bin 提到 MSYS2 前面。

本次 `.NET workload list` 触发 CLI 首次初始化，输出自动安装 ASP.NET Core HTTPS 开发证书；没有执行 `--trust`，也没有安装工作负载。

## 7. Python 应用与后端

| 版本/环境 | 解释器绝对路径 | 本次核实 |
|---|---|---|
| 默认 Python 3.13.5 | `D:\wengFiles\softwares\pythonVersion\py3.13.5\python.exe` | 可启动；标准库 SQLite 3.49.1；包元数据发现 Uvicorn 0.53.0、Requests 2.34.2 |
| Python 3.12.3 | `D:\wengFiles\softwares\pythonVersion\py3.12.3\python.exe` | 启动器登记且可运行包元数据检查；选定的 Web/GUI 框架未发现 |
| Python 3.11.0 | `D:\wengFiles\softwares\pythonVersion\py3.11.0\python.exe` | 启动器登记且可运行包检查；选定框架未发现 |
| Python 3.10 | `D:\wengFiles\softwares\pythonVersion\py3.10\python.exe` | 包元数据发现 FastAPI 0.115.12、Uvicorn 0.34.0；本次未验证服务启动 |
| Python 3.9 | `D:\wengFiles\softwares\pythonVersion\py3.9\python.exe` | 启动器登记且可运行包检查；选定框架未发现 |
| Python 2.7 | `D:\wengFiles\softwares\pythonVersion\py2.7\python.exe` | 仅启动器登记；保留旧项目用途 |
| Python 启动器 | `C:\Windows\py.exe` | `py -0p` 成功，默认指向 3.13 |
| Excel 独立环境 | `D:\wengFiles\python-envs\excel\Scripts\python.exe` | 环境目录核实；旧总表记载基于 3.12.3、openpyxl 3.1.5，本次未重新运行 |

抽查的框架名包括 FastAPI、Uvicorn、Django、Flask、PySide6、PyQt5/6、Kivy、Briefcase、PyInstaller、Buildozer、Flet、Playwright。在上述 Windows Python 3.9～3.13 的选定包检查中，仅发现表内列出的相应组件；未扫描所有项目虚拟环境，也未完整导出依赖。包存在不表示其业务 API 或打包流程已验收。

安装依赖时用目标解释器的 `-m pip`；新项目优先在项目目录建 `.venv`，不要把项目依赖混装到多个全局解释器。`py -3.10` 可选择已有 3.10，解释器实际小版本应在使用时再查询。

## 8. Docker、WSL2 与数据库

### 8.1 Docker

| 项目 | 核实结果 |
|---|---|
| Docker CLI | `C:\Users\admin\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe` |
| Docker Desktop | 4.90.0，安装根目录 `C:\Users\admin\AppData\Local\Programs\DockerDesktop` |
| Engine | 客户端/服务端均 29.7.2，服务端本次响应成功 |
| Compose | v5.5.1；版本通过 |
| 当前 context | `desktop-linux`，Linux/amd64 |
| WSL 后端数据根 | `C:\Users\admin\AppData\Local\Docker\wsl\main`，来源为 WSL 注册表，勿手工修改其内部磁盘文件 |
| 相关本地目录 | `D:\AI-USE\docker` 下有 `ai-goofish`、`CLIProxyAPI`、`cloud`、`redis-docker`；仅核实目录，未读取业务配置 |

本地镜像列表发现 `maven:3.9.11-eclipse-temurin-21`、`postgres:18-alpine`、`redis:8-alpine`、Alpine、curl 等。Maven 镜像仅核实存在，未启动容器验证版本。另有 `mysql:<none>` 等无标签镜像，不能把它们写成已有稳定版本的服务。

检查时有 PostgreSQL 18 Alpine 和 Redis 8 Alpine 容器运行且 Docker 健康状态为 healthy，也有其他业务容器运行。**本次没有读取其账号、数据库内容、挂载或端口，也没有验证 SQL/Redis 业务请求；新项目不得直接假定可以复用这些实例。** Docker 健康状态不等于新项目的连接验证。

### 8.2 WSL2

| 发行版 | 已验证工具 | Windows 存储根 / Linux 入口 |
|---|---|---|
| `Ubuntu-22.04`（默认） | Ubuntu 22.04.2 LTS；Python 3.10.12、GCC 11.4.0、CMake 3.22.1、Git 2.34.1；另找到 G++ 入口 | `C:\Users\admin\AppData\Local\Packages\CanonicalGroupLimited.Ubuntu22.04LTS_79rhkp1fndgsc\LocalState`；工具 `/usr/bin/python3`、`/usr/bin/gcc`、`/usr/bin/g++`、`/usr/bin/cmake`、`/usr/bin/git` |
| `Ubuntu` | Ubuntu 24.04.1 LTS；Python 3.12.3、Git 2.43.0 | `C:\Users\admin\AppData\Local\Packages\CanonicalGroupLimited.Ubuntu_79rhkp1fndgsc\LocalState`；工具 `/usr/bin/python3`、`/usr/bin/git` |
| `docker-desktop` | WSL2，检查结束时运行中；由 Docker Desktop 管理 | `C:\Users\admin\AppData\Local\Docker\wsl\main` |

通过 `wsl -d Ubuntu-22.04 -- <命令>` 或 `wsl -d Ubuntu -- <命令>` 进入对应环境。两套 Ubuntu 本次均为执行只读检查而启动过；最终运行列表仅列出 docker-desktop，未执行强制终止发行版操作。

两套 Ubuntu 的非交互默认 PATH 探测未发现 Linux 原生 node、Java、Maven、Gradle、Rust、Go、Flutter、Dart。`which npm` 命中的是 `/mnt/c/Program Files/nodejs//npm`，`which gradle` 命中的是 `/mnt/d/AI-USE/path/gradle-8.13/bin/gradle`；它们来自 Windows 路径互通，不能据此宣称已安装独立的 Linux Node/npm/Gradle，Gradle 的 Linux JVM 条件也未验证。未遍历 Linux 用户目录或其语言版本管理器，未完整盘点 WSL 中的 Python 包。

### 8.3 本机存储与消息工具

| 工具 | 入口 | 核实结果 |
|---|---|---|
| SQLite CLI | `C:\msys64\mingw64\bin\sqlite3.exe` | 3.49.2；版本通过 |
| Python SQLite | 随默认 Python 标准库 | SQLite 3.49.1；查询通过 |
| Mosquitto | `C:\Program Files\Mosquitto\mosquitto.exe` | 文件及 Windows 服务核实，检查时服务 Running；旧总表版本 2.1.2，本次未复核消息收发 |

Windows PATH 未发现 `psql`、`mysql`、`redis-server`、`mongod`；注册表与服务名检查未确认对应独立数据库服务。Docker 中存在数据库不能当成 Windows 已装同名 CLI。`Microsoft SQL Server` 目录和 CLR 类型组件不证明已安装 SQL Server 数据库引擎。

## 9. 编辑器、浏览器、测试与辅助工具

| 工具 | 版本/用途 | 入口 |
|---|---|---|
| VS Code | 1.111.0；文件版本核实 | `C:\Users\admin\AppData\Local\Programs\Microsoft VS Code\Code.exe` |
| Cursor | 3.12.17；文件版本核实 | `D:\AI-USE\cursor\Cursor.exe` |
| 当前 `code` 命令 | **指向 Cursor 内的脚本**，并非上面的 VS Code | `D:\AI-USE\cursor\resources\app\codeBin\code.cmd` |
| 当前 `cursor` 命令 | Cursor CLI 入口 | `D:\AI-USE\cursor\resources\app\bin\cursor.cmd` |
| PyCharm 2025.1.2 | 文件及安装记录核实 | `D:\wengFiles\softwares\PyCharm 2025.1.2\bin\pycharm64.exe` |
| PyCharm 2025.2.0.1 | 文件及安装记录核实 | `E:\software\PyCharm 2025.2.0.1\bin\pycharm64.exe` |
| Git | 2.46.0.windows.1；版本通过 | `C:\Program Files\Git\cmd\git.exe` |
| GitHub CLI | 2.96.0；版本通过，未检查认证 | `C:\Users\admin\AppData\Local\Microsoft\WinGet\Links\gh.exe` |
| Chrome | 155.0.8059.40；文件版本核实 | `C:\Program Files\Google\Chrome\Application\chrome.exe` |
| Edge | 155.0.4283.62；文件版本核实 | `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe` |
| Playwright 浏览器缓存 | 发现 `chromium-1234`、FFmpeg 等目录；未验证浏览器能启动或对应项目依赖 | `C:\Users\admin\AppData\Local\ms-playwright` |
| FFmpeg / ffprobe | 当前命令入口核实；旧总表记载 FFmpeg 7.1.1，本次未复核转码 | `C:\msys64\mingw64\bin\ffmpeg.exe`、`C:\msys64\mingw64\bin\ffprobe.exe` |
| OpenSSH | 当前命令入口核实，未连接远程 | `C:\Windows\System32\OpenSSH\ssh.exe` |

VS Code 扩展目录抽查发现 Java、Gradle、Java 调试/测试/Maven 等扩展；未发现 Android/Kotlin/Flutter 扩展。扩展不是编译器或对应 SDK 的替代。Apipost、Qoder、Antigravity 等应用有安装目录，本次未启动验证，未把它们列为构建前提。

## 10. 未发现、未验收与下一步

| 方向 | 本次结论 / 接手时应检查的事项 |
|---|---|
| Android Studio | PATH、卸载记录及常见目录未发现；现有 Android 命令行工具可以单独核实，不应因未装 IDE 就重复装整个 SDK |
| Flutter / Dart | PATH、卸载记录、常见目录未发现；不能把 Flutter 工程视为可立即构建 |
| HBuilderX / uni-app | 未发现 HBuilderX；uni-app 的项目依赖/云打包账号未检查 |
| DevEco / HarmonyOS | 常见入口及安装目录未发现；没有验收鸿蒙打包链 |
| Qt / Unity | 常见目录及安装记录未发现；未验收相关运行/许可证 |
| Rust / Go | Windows PATH 和常见用户工具目录未发现；WSL 非交互 PATH 也未发现；不代表所有工程目录无工具链 |
| Maven | Windows/WSL 默认 PATH 未发现；Docker 缓存有 Maven/JDK 21 镜像，但未运行验收 |
| Android JNI / C++ | 两套 SDK 都缺 NDK/SDK CMake；Windows GCC/MSVC 不能替代这一检查 |
| .NET MAUI / Android / iOS | 工作负载列表为空，虽有清单；未验证 SDK packs、目标平台编译和签名条件 |
| iOS / Xcode | 本次仅盘点 Windows 本机及 WSL/Docker；未检查任何 Mac、远程构建机或云构建服务，不宣称具备 iOS 本地打包能力 |
| APK、桌面安装包、应用签名 | 本次没有应用工程，未构建或签名；未检查真机授权、证书、商店账号 |

**盘点边界**：检查了当前进程 PATH、选定环境变量、Windows 卸载注册表、已知工具位置、常见 Program Files/用户应用目录、D 盘工具目录、E 盘已发现的软件位置、已注册 Python、两套 WSL 默认命令环境、Docker 镜像/服务状态。没有递归扫描全盘、所有项目、所有 `.venv`/`node_modules`，也没有连接远程机器。本文覆盖已发现且与 App 开发相关的环境；未发现项保留搜索范围，避免给出“全机绝对未安装”的误导结论。

## 11. 接手 AI 的快速复核命令

以下命令用于查看信息；首次执行某些 CLI 仍可能触发其自身初始化。按任务选择执行，避免无意义地重复整套检查。

```powershell
Get-Command node, npm, pnpm, java, javac, gradle, adb, emulator, python, dotnet, git |
    Select-Object Name, Source

& 'C:\Program Files\nodejs\node.exe' --version
& 'C:\Program Files\nodejs\npm.cmd' --version
& 'C:\Users\admin\AppData\Roaming\npm\pnpm.cmd' --version
& 'D:\AI-USE\path\jdk-17\bin\java.exe' -version
& 'D:\AI-USE\path\gradle-8.13\bin\gradle.bat' --version
& 'D:\AI-USE\path\android-sdk\platform-tools\adb.exe' version
& 'D:\AI-USE\AndroidEmulator\Sdk\emulator\emulator.exe' -list-avds
Get-ChildItem 'D:\AI-USE\path\android-sdk\platforms' -Directory
Get-ChildItem 'D:\AI-USE\AndroidEmulator\Sdk\platforms' -Directory

py -0p
& 'D:\wengFiles\softwares\pythonVersion\py3.13.5\python.exe' -c "import sys; print(sys.executable)"
dotnet --list-sdks
dotnet workload list
wsl --list --verbose
wsl -d Ubuntu-22.04 -- /usr/bin/python3 --version

docker version
docker compose version
docker ps --format '{{.Image}} | {{.Status}}'
```

确认 App 的目标平台和工程依赖后，再执行对应的最小构建与运行验收。本文记录的是环境证据，不替代目标 App 的验收结果。
