# QQNT-Patcher

主人好喵！欢迎来到 QQNT-Patcher~ 这里是一只专门给 Android QQNT 做静态修补、去签防护与动态扩展的小工具，不用 Root 也不依赖任何 Xposed / LSPosed 框架就能直接跑起来喵！

- Telegram 频道：[ZcraftMod](https://t.me/ZcraftMod)
- GitHub 仓库：[Cattafish/QQNT-Patcher](https://github.com/Cattafish/QQNT-Patcher)

---

## 项目简介

QQNT-Patcher 是一款针对 Android QQNT 架构的工业级自动化静态字节码修补、底层去签防护与运行时扩展工具。

工程采用 **5 阶段解耦流水线（Pipeline Architecture）** 调度，通过纯内存流式 AST 重构目标 Dex，联动 **NeoApk 纯 JVM 流式打包引擎** 实现原包内嵌与 MT 式数据复用，并深度集成 **Zcraft (ApkSignatureKillerEx) 底层去签 Provider** 与现代 BeanShell 3 动态脚本环境，提供毫秒级、零内存冗余、免 Root 的极致增强体验。

---

## 核心架构特性

### 1. NeoApk 工业级流式打包与数据复用 (零膨胀)
* **宿主与虚拟条目复用 (Host & Virtual Entry)**：将官方原包以 16KB 页面对齐 STORE 模式直接流式内嵌为 `assets/Zcraft/input.apk`；原包中未修改的 39,000+ 个资源条目直接以虚拟指针映射其内部数据段，**0 字节数据冗余复制，产物体积稳稳停在 ~380MB（彻底根除体积翻倍暴增）**。
* **原生 16KB / 4KB 对齐**：`ZipMaker` 写入时自动处理 `lib/arm64-v8a/*.so` 和原包的 16KB 对齐及普通条目的 4 字节对齐，**不依赖外部 `zipalign` 进程**。
* **原地合法 V2 签名**：通过 `V2V3SchemeSigner` 直接在 Central Directory 前切入标准 V2 签名块，**不碰条目数据，不破坏复用结构**，100% 消除签名结构畸变与安装验签失败。

### 2. 双重去签防护体系 (Zcraft Provider)
* **V1 原版证书壳欺骗**：原样保留腾讯官方原版 `META-INF/`（`MANIFEST.MF` + `*.SF` + `*.RSA`）三件套壳，系统层 V1 校验失败但被 V2 忽略，使底层 Native（如 `com.sina.syscall` 或 QQ 自身 Native 查签）读取 maps/整包证书时拿到的永远是原版官方证书。
* **动态系统调用拦截**：注入 `libzcraft.so`，利用 xhook 实施 GOT Hook，对底层 open/stat/maps 进行动态脱敏；Java 层动态代理 `IPackageManager` 与 `PackageInfo.CREATOR`，深度伪造签名信息。
* **VFS 路径规范化资产穿透 (`PatchAssetHelper`)**：针对 Killer 运行时对安装包的重定向机制，通过 VFS 路径规范化穿透拦截，在保持内嵌原包 100% 纯净的前提下，允许 App 原生无限制加载 `assets/` 下的任意新增图片、音效与扩展包。

### 3. 五阶段解耦流水线 (Pipeline Pattern)
主程序拆分为单一职责的调度流水线，拒绝巨石脚本：
* **Stage 1 (Prepare)**：基础环境自检、增量编译 `DexPatcher` 与扩展 Java 库；
* **Stage 2 (Rules)**：官方原包指纹提取、DEX 类拓扑分析与自注册规则插件推导；
* **Stage 3 (Transform)**：纯内存流式 AST 字节码重构与 Native SO 跳转修补；
* **Stage 4 (Pack)**：增量目录整理（支持多层子目录递归、按需 ABI 过滤）；
* **Stage 5 (Sign)**：签名提供器调度（支持 `Killer` 去签复用、`Debug` 纯净签名与 `None` 免签模式）。

### 4. 自注册插件式规则引擎 (Rule Engine)
* `rules/` 目录下每一个 `.py` 均为自闭环规则插件，自动发现、自动加载；
* 拥有基于各模块源码 `mtime` 的细粒度独立增量缓存池，**新增业务规则仅需在 `rules/` 增加文件，主构建器 0 代码修改**。

---

## 版本兼容性说明

- **当前主力测试版本**：QQ `9.3.55` ~ `9.3.70`
- **最低兼容测试版本**：QQ `9.2.90`
- **支持架构**：自动按原包实际 ABI 过滤注入（如 64 位纯净版仅注入 `arm64-v8a`）。

---

## 当前功能特性

### 1. 核心功能模块
- **消息防撤回**：拦截私聊与群聊实时撤回，树形递归过滤后台同步包，自身撤回正常放行；生成可点击资料卡、瞬移定位并高亮原消息的交互灰条。
- **闪照破解与画廊放行**：推送与历史闪照自动转为普通图片并支持保存，解除 AIO 沉浸式画廊对闪照资源的查看与下载限制。
- **静默 @全体 与群待办**：拦截群内 `@全体成员` 强提醒与 `0x135` / 天枢群待办弹窗，仅放行真正 `@我` 的消息。
- **群文件显示下载次数**：动态拦截并解析群文件列表回包，在文件列表与卡片上直观显示下载次数（自适应新旧渲染通道）。
- **发送 APK 自动重命名**：本地发送 `.apk` 时自动读取内部应用名与版本号，规范化重命名为 `应用名_版本号.APK`，并清洗回包污染后缀。
- **强制平板模式**：全动态穿透设备类型判定，强制开启 QQ 原厂双栏折叠屏/平板 UI 布局（需重启生效）。
- **伪装非多窗口模式**：动态拦截 `isInMultiWindow` 校验，解除分屏状态下扫码等功能的强制限制。
- **会话感知悬浮球**：仅在进入聊天会话（AIO）时自动显示悬浮球，支持拖动与贴边停靠，一键唤出脚本快捷动作面板。
- **自定义图片外显**：自定义发送普通图片与商城大表情的外显文本，支持固定文本或 HTTP(S) API 异步轮询更新。

### 2. 动态脚本生态扩展 (兼容 QFun)
- **免框架热插拔**：外部存储放入脚本即时生效，支持标准 **Java 语法**（完整支持 Lambda 表达式与流式 API）。
- **底层 SSO 协议发包**：内置 `PacketHelper` 与 `MsgSender`，打通底层 MSF 管道，支持构造并投递二进制 Protobuf / OIDB 协议报文。
- **全套消息与群管能力**：文本/图文混排/Silk语音/卡片/视频/文件发送、双击拍一拍、禁言/解禁、踢人、改名片、头衔、群打卡等。
- **凭证与高清密钥提取**：一键提取 `Skey`、`Pskey`、`Pt4Token`、`GTK`、`bkn`，自动嗅探聊天原图/高清图 `RKey` 鉴权密钥。

### 3. QQ 原生二级设置中心 (Zzz)
- **原生顶层挂载**：在 QQ 设置顶层自动挂载 **“Zzz”** 设置与 **“动态脚本”** 双卡片入口，图标秒开加载。
- **模块化控制台**：核心功能独立启闭，动态脚本支持独立启停、动作触发、一键重载与全局扫描。
- **实时运行日志监视器**：内置 300 条环形内存日志池，终端风格弹窗随时调阅运行状态，支持一键导出到本地 `latest.log`。

---

## 外部脚本存放路径

修补安装完成后，将兼容 QFun 的脚本文件夹放入手机内部存储对应的目录下：

```text
/sdcard/Android/media/com.tencent.mobileqq/zzz/plugins/
```

**脚本目录结构示例**：
```text
zzz/
├── latest.log           # 在设置中点击“导出日志”时生成的本地日志文件
└── plugins/
    └── 快捷栏/
        ├── info.prop        # 脚本元数据（ID、名称、作者、版本）
        ├── main.java        # 脚本入口源码（Java 语法）
        ├── desc.txt         # 脚本功能简介（可选）
        └── PacketHelper.java# 辅助类（可选）
```

---

## 环境准备

运行环境需具备 Python 3、JDK 17、Android SDK 构建工具（d8、zipalign、apksigner）、zip 与 curl：

### Linux / Ubuntu / WSL 环境
```bash
sudo apt update
sudo apt install python3 openjdk-17-jdk android-sdk-build-tools zipalign zip curl -y
```

### Termux (Android) 环境
```bash
pkg update
pkg install python openjdk-17 d8 apksigner android-tools zip curl -y
# 注: 本项目已全量采用 Java 原生 KeyStore 与纯 JVM 引擎，无需安装 openssl-tool
```

---

## 快速使用教程

### 步骤 1：下载基础构建组件至 `tools/`

在项目根目录下执行以下命令，下载构建所需的依赖 Jar 包至 `tools/` 目录：

```bash
mkdir -p tools

# 1. Smali / Dexlib2 核心字节码工具
curl -L -o tools/baksmali.jar https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
curl -L -o tools/smali.jar https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
curl -L -o tools/dexlib2.jar https://repo1.maven.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar
curl -L -o tools/guava.jar https://repo1.maven.org/maven2/com/google/guava/guava/18.0/guava-18.0.jar

# 2. 动态脚本引擎与协议依赖 (BeanShell 3 + Dx + Protobuf)
curl -f -L -o tools/bsh.aar https://repo1.maven.org/maven2/io/github/copylibs/beanshell-android-lambda/3.0.0.beta10/beanshell-android-lambda-3.0.0.beta10.aar
python3 -c "import zipfile, os; open('tools/bsh.jar', 'wb').write(zipfile.ZipFile('tools/bsh.aar').read('classes.jar')); os.remove('tools/bsh.aar')"

curl -L -o tools/dx.jar https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/9.0.0_r3/dalvik-dx-9.0.0_r3.jar
curl -L -o tools/protobuf.jar https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.25.3/protobuf-java-3.25.3.jar

# 3. Android SDK 核心编译期标准桩库 (API 30)
curl -L -o tools/android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
```

---

### 步骤 2：准备 Killer 去签载荷与 NeoApk 引擎

1. **准备 `tools/killer-release.aar`**：
   从 [ApkSignatureKillerEx Releases](https://github.com/Cattafish/ApkSignatureKillerEx/releases) 下载 `killer-release.aar` 放入 `tools/` 目录，然后执行一键解包提取：
   ```bash
   python3 sync_killer.py
   ```
2. **准备 `tools/neoapk.jar`**：
   在 `~/app/NeoApk` 中构建生成 `neoapk.jar`（或下载预编译包）并放置在 `tools/neoapk.jar`。

---

### 步骤 3：放置官方原版 APK 并启动构建

将官方原版 QQ 安装包命名为 `QQ.apk` 放置在项目根目录下：

#### 方式 A：标准去签生产构建 (推荐)
自动挂载 Killer 去签总线，执行 NeoApk 数据复用与 V2 原地重签，包体不膨胀且免 Root 直接安装：
```bash
python3 patcher.py QQ.apk QQ_Patched.apk
```

#### 方式 B：纯净 Debug 签名构建 (排查专用)
跳过 Killer 去签注入（不注入 SO、去签 Dex 和原包副本），使用标准 Debug 证书签名：
```bash
python3 patcher.py --signer=debug QQ.apk QQ_Patched.apk
# 或简写为:
python3 patcher.py --no-killer QQ.apk QQ_Patched.apk
```

#### 方式 C：免签构建 (配合核心破解使用)
```bash
python3 patcher.py --signer=none QQ.apk QQ_Patched.apk
# 或简写为:
python3 patcher.py -n QQ.apk QQ_Patched.apk
```

#### 方式 D：调试与缓存控制参数
```bash
# 一键彻底重置构建缓存 (更换 QQ 版本时推荐)
python3 patcher.py --clean

# 调试时跳过宿主 Dex AST 重构，仅编译注入业务扩展代码
python3 patcher.py --skip-dex-patch

# 仅执行指定关键字的单条规则
python3 patcher.py --only "闪照"

# 临时跳过指定关键字规则
python3 patcher.py --skip "群文件"
```

---

## 项目结构全景

```text
QQNT-Patcher/
├── pipeline/                             # 5 阶段流水线解耦架构
│   ├── __init__.py                       # 流水线总线调度器与工作空间生命周期管理
│   ├── context.py                        # 全局上下文 (路径、参数、缓存状态、工具链)
│   ├── stage1_prepare.py                 # 阶段 1: 基础工具链检查与扩展 Dex 增量构建
│   ├── stage2_rules.py                   # 阶段 2: 原包元数据提取与自注册规则插件推导
│   ├── stage3_transform.py               # 阶段 3: 纯内存 AST 字节码局部重构与 Native 修补
│   ├── stage4_pack.py                    # 阶段 4: 容器注入组装 (按需 ABI 过滤、资产收集)
│   ├── stage5_sign.py                    # 阶段 5: 签名流水线分发 (交由当前 Provider 执行)
│   └── providers/                        # 签名与去签提供者抽象层
│       ├── __init__.py                   # Provider 工厂注册中心
│       ├── base.py                       # BaseProvider 规范抽象基类
│       ├── killer.py                     # KillerProvider (Zcraft 去签核心调度器)
│       ├── debug.py                      # DebugSignerProvider (标准 Keystore 签名器)
│       └── none.py                       # NoneSignerProvider (跳过签名器)
├── rules/                                # 自发现插件化规则系统
│   ├── __init__.py                       # 规则系统入口与导出
│   ├── engine.py                         # 插件自动发现与生命周期引擎 (零侵入热插拔)
│   ├── base_rules.py                     # 核心基础总线规则 (MSF、AIO菜单、消息总线)
│   ├── security_rules.py                 # 全套动态防反外挂、防篡改、风控对齐规则
│   ├── setting_rules.py                  # 设置中心动态挂载规则插件
│   ├── tablet_rules.py                   # 平板模式动态穿透规则插件
│   ├── group_file_rules.py               # 群文件下载次数动态匹配插件
│   ├── troop_todo_rules.py               # 静默群待办强提醒动态插件
│   ├── multi_window_rules.py             # 伪装非多窗口模式动态插件
│   ├── killer_rules.py                   # Zcraft 去签入口字节码注入插件
│   ├── parser.py                         # FastDexParser DEX 内存流式语义扫描引擎
│   └── stubs.py                          # Smali 汇编空桩与重定向工厂
├── src/                                  # 扩展功能 Java 源码与环境桩
│   ├── com/tencent/qqnt/patch/
│   │   ├── modules/                      # 模块化业务功能解耦实现
│   │   ├── plugin/                       # 动态脚本引擎与 QFun 兼容生态
│   │   ├── PatchAssetHelper.java         # VFS 路径规范化穿透资产加载器 (全资产免Base64直读)
│   │   ├── SettingInjector.java          # QQ 设置中心顶层入口动态挂载
│   │   ├── ZzzSettingFragment.java       # Zzz 二级原生设置中心界面
│   │   ├── PLog.java                     # 内存环形日志监视器与应用内调试弹窗
│   │   └── ...
├── assets/                               # 静态图标与资源 (支持子目录递归打包)
│   ├── script_icon.png                   # 动态脚本设置入口图标
│   └── zzz_icon.png                      # 设置入口与悬浮球图标
├── tools/                                # 构建依赖工具链与打包装配引擎
│   ├── neoapk.jar                        # NeoApk 独立流式打包签名引擎库
│   ├── NeoPacker.java                    # 基于 NeoApk 的单遍流式装配与 V2 签名器
│   └── ...
├── DexPatcher.java                       # DEX 纯内存流式 AST 多线程批量编译修补引擎
├── native_patcher.py                     # arm64-v8a Native SO 二进制跳转原位修补器
├── sync_killer.py                        # 一键免 NDK 同步/提取 Killer 载荷脚本
├── patcher.py                            # 自动化构建顶层网关入口
└── README.md
```

---

## 鸣谢与致敬

- [NeoApk / NPatch](https://github.com/HSSkyBoy/NeoApk)：感谢 SkyBoy 与 NPatch 团队开源的高性能流式 ZIP 引擎与原地 V2 签名器，为本项目的数据复用与秒级打包提供了坚实基础。
- [ApkSignatureKillerEx](https://github.com/Cattafish/ApkSignatureKillerEx)：感谢 Cattafish 维护的底层免 Root 去签旁路方案与 xhook 重定向机制。
- [QFun](https://github.com/oneQAQone/QFun)：感谢项目提供的 QQNT 消息防撤回思路与 BeanShell 脚本生态 API 设计参考。
- [Smali / Baksmali / Dexlib2](https://github.com/JesusFreke/smali)：感谢 JesusFreke 提供的强大 Dex 字节码重构库。

---

## 免责声明

本项目仅供 Android 逆向工程、静态插桩技术与系统级安全机制的研究与交流使用。请勿将本项目用于任何商业牟利或侵犯他人合法权益的场景。使用修改版本产生的任何后果由使用者自行承担。
