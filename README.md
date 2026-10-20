# QQNT-Patcher

主人好喵！欢迎来到 QQNT-Patcher~ 这里是一只专门给 Android QQNT 做静态修补、去签防护与动态扩展的小工具，不用 Root 也不依赖任何 Xposed / LSPosed 框架就能直接跑起来喵！

- Telegram 频道：[ZcraftMod](https://t.me/ZcraftMod)
- GitHub 仓库：[Cattafish/QQNT-Patcher](https://github.com/Cattafish/QQNT-Patcher)

---

## 项目简介

QQNT-Patcher 是一套针对 Android QQNT 架构的自动化静态字节码修补、签名保护与运行时扩展工具。

项目采用分阶段流水线调度：通过内存 AST 重构目标 DEX，结合 NeoApk 流式打包引擎实现原包内嵌与虚拟条目数据复用；同时集成底层去签方案与 BeanShell 3 动态脚本环境，提供免 Root 的功能增强支持。

---

## 核心架构特性

### 1. NeoApk 流式打包与数据复用
* **宿主与虚拟条目复用**：将官方原包以内嵌形式写入 `assets/Zcraft/meow.apk`。原包中未修改的数万个资源条目直接通过虚拟指针映射其内部数据偏移，大幅降低双重打包带来的体积冗余。
* **原生 16KB 与 4KB 页面对齐**：打包时自动处理 Native 动态库及内嵌包的 16KB 内存对齐和普通文件的 4 字节对齐，不依赖外部 `zipalign` 进程，兼容高版本 Android 系统的页面对齐要求。
* **原地 V2 签名**：通过 `V2V3SchemeSigner` 直接在 Central Directory 前写入合法的 V2 签名块，不触碰条目数据区，避免重构签名导致的结构畸变。

### 2. 底层签名防护体系
* **V1 证书壳保留**：完整保留原包官方 `META-INF/` 签名信息，使依赖底层 maps 读取或校验安装包证书的 Native 逻辑获取到官方原版证书。
* **系统调用动态拦截**：注入 `libzcraft.so` 并基于 xhook 对底层的 `open`、`stat`、`maps` 进行脱敏重定向；Java 层动态代理系统包管理接口，伪造签名哈希。
* **路径穿透资产加载**：针对安装包重定向机制，通过 `PatchAssetHelper` 进行路径规范化穿透拦截，允许 App 正常加载外层 `assets/` 目录新增的脚本、图片与配置。

### 3. 五阶段流水线调度
构建总线拆分为职责清晰的阶段调度器：
* **Stage 1 Prepare**：检查基础构建工具链，增量编译 `DexPatcher`、扩展 Java 库与脚本依赖；
* **Stage 2 Rules**：提取原包签名指纹与 DEX 拓扑关系，动态加载并推导插桩规则；
* **Stage 3 Transform**：基于内存 AST 批量重构目标 DEX 字节码；
* **Stage 4 Pack**：收集修改后的 DEX、Native 库与静态资源，整理按需注入目录；
* **Stage 5 Sign**：根据选定模式执行签名与最终装配。

### 4. 插件式规则引擎
* `rules/` 目录下的规则文件各自独立，引擎自动发现并加载；
* 配套基于 DEX 语义特征的快速流式解析器，自动适应混淆类名与方法名的变动，添加新功能仅需新增规则文件。

---

## 版本兼容性说明

- **测试版本**：QQ `9.3.55` ~ `9.3.70`
- **基础兼容版本**：QQ `9.2.90`+
- **适配架构**：根据原包实际包含的 ABI 进行注入（如 64 位纯净版仅注入 `arm64-v8a`）。

---

## 当前功能特性

### 1. 聊天增强与防撤回
- **消息防撤回**：实时拦截私聊与群聊撤回指令，递归过滤后台漫游同步包，自身主动撤回正常放行；生成支持点击跳转并高亮原消息的交互灰条。
- **闪照破解与放行**：接收与历史闪照自动转为普通图片，解除沉浸式大图画廊对闪照资源的查看与保存限制。
- **发送者 QQ 版本与架构识别**：在好友设置与群成员名片页直观展示对方的客户端版本、操作系统、CPU 架构、SubID 及渠道信息，内置云端增量静默 OTA 字典同步。
- **查看本地聊天记录**：在设置中输入目标 QQ 号或群号，直接调用官方聊天记录组件调阅本地已保存的历史消息（即使被单删好友或已退群亦可查看）。
- **自定义图片外显**：支持修改发送普通图片与商城大表情的外显文字，支持从本地词库抽取或配置 HTTP 接口轮询更新。
- **Telegram 外部表情包**：支持加载本地外部表情包，提供 3 到 8 列布局切换，并支持按需净化隐藏官方商城大表情。

### 2. 净化与系统限制解除
- **群文件下载次数显示**：解析群文件列表回包并在文件项直接展示下载次数，自适应新旧两代渲染通道。
- **静默全体与群待办提醒**：过滤群聊 `@全体成员` 强提醒与群待办弹窗，仅真正 `@我` 的消息触发通知。
- **发送 APK 自动规范命名**：发送本地 `.apk` 时自动读取内部应用名与版本号命名为 `应用名_版本号.APK`，并清洗回包污染后缀。
- **强制平板双栏模式**：动态拦截设备类型判定，开启原厂平板与折叠屏双栏布局。
- **伪装非多窗口模式**：动态拦截多窗口检测，解除分屏状态下扫码等功能的系统限制。
- **内置浏览器拦截解除**：拦截并放行安全检测回调，允许在内置浏览器直接打开第三方网页。

### 3. 动态脚本生态
- **免框架热插拔**：外部存储放入脚本即时生效，支持标准 Java 语法及现代 Lambda 表达式。
- **协议收发与群管支持**：打通 MSF 传输管道，支持自定义构造发包、图文语音发送、禁言踢人、修改名片、头衔管理等操作。
- **凭证与密钥获取**：支持提取当前账号 Skey、Pskey、Pt4Token 等鉴权凭证，自动捕获高清图片所需的 RKey 密钥。
- **会话感知悬浮球**：进入聊天界面（AIO）时自动展现悬浮球，支持拖动停靠与一键调出脚本菜单。

### 4. 原生二级设置中心
- **设置顶层无缝集成**：在 QQ 设置顶层挂载设置卡片与动态脚本双入口，图标与夜间模式自适应。
- **搜索索引支持**：全面接入 QQ 原生功能搜索，可在设置搜索栏直接检索功能并跳转定位。
- **内存日志监视器**：内置环形内存日志池，支持在应用内弹窗调阅实时运行日志或一键导出本地文件。

---

## 外部数据与脚本存放路径

安装修改后的 APK 后，相关资源放置于手机存储对应目录下：

```text
/sdcard/Android/media/com.tencent.mobileqq/zzz/
├── latest.log           # 在设置中点击“导出”生成的本地运行日志
├── summary.txt          # 本地图片外显词库（每行一条文本，可选）
├── stickers/            # Telegram 外部表情包目录（每个子文件夹对应一个表情包）
│   └── 示例表情包/
│       ├── __cover__.png
│       ├── 01.png
│       └── 02.webp
└── plugins/             # 动态脚本目录
    └── 示例脚本/
        ├── info.prop    # 脚本元数据（ID、名称、作者）
        └── main.java    # 脚本入口源码
```

---

## 环境准备

运行构建环境需具备 Python 3、JDK 17、Android SDK 构建工具（d8、zipalign、apksigner）、zip 与 curl。

### Linux 与 WSL 环境
```bash
sudo apt update
sudo apt install python3 openjdk-17-jdk android-sdk-build-tools zipalign zip curl -y
```

### Termux 环境
```bash
pkg update
pkg install python openjdk-17 d8 apksigner android-tools zip curl -y
```

---

## 快速使用说明

### 步骤 1：下载基础依赖库

在项目根目录下执行以下命令，下载构建所需的依赖库至 `tools/` 目录：

```bash
mkdir -p tools

# 1. 字节码解析工具
curl -L -o tools/baksmali.jar https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
curl -L -o tools/smali.jar https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
curl -L -o tools/dexlib2.jar https://repo1.maven.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar
curl -L -o tools/guava.jar https://repo1.maven.org/maven2/com/google/guava/guava/18.0/guava-18.0.jar

# 2. 脚本引擎与协议依赖
curl -f -L -o tools/bsh.aar https://repo1.maven.org/maven2/io/github/copylibs/beanshell-android-lambda/3.0.0.beta10/beanshell-android-lambda-3.0.0.beta10.aar
python3 -c "import zipfile, os; open('tools/bsh.jar', 'wb').write(zipfile.ZipFile('tools/bsh.aar').read('classes.jar')); os.remove('tools/bsh.aar')"

curl -L -o tools/dx.jar https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/9.0.0_r3/dalvik-dx-9.0.0_r3.jar
curl -L -o tools/protobuf.jar https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.25.3/protobuf-java-3.25.3.jar

# 3. Android 编译期桩库
curl -L -o tools/android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
```

---

### 步骤 2：准备原生库与打包引擎

1. **准备 `tools/killer_lib`**：放置编译完成的 `libzcraft.so` 目标架构目录（或解压自官方 Releases）；
2. **准备 `tools/neoapk.jar`**：放置编译完成的 NeoApk 核心库单体 JAR 包。

---

### 步骤 3：放置官方原版 APK 并启动构建

将官方原版 QQ 安装包重命名为 `QQ.apk` 放置在项目根目录下：

#### 方式 A：标准去签构建
内嵌官方原包并注入签名欺骗模块，产物免 Root 直接安装：
```bash
python3 patcher.py QQ.apk QQ_Patched.apk
```

#### 方式 B：纯净 Debug 签名构建
跳过去签壳注入与原包内嵌，使用标准 Debug 密钥签名（包体体积最轻，适用于系统已集成核心破解的环境）：
```bash
python3 patcher.py --signer=debug QQ.apk QQ_Patched.apk
# 或简写为
python3 patcher.py --no-killer QQ.apk QQ_Patched.apk
```

#### 方式 C：免签构建
跳过签名流程：
```bash
python3 patcher.py --signer=none QQ.apk QQ_Patched.apk
# 或简写为
python3 patcher.py -n QQ.apk QQ_Patched.apk
```

#### 方式 D：常用调试参数
```bash
# 彻底清空构建增量缓存
python3 patcher.py --clean

# 跳过宿主 DEX 重构，仅编译注入扩展业务类
python3 patcher.py --skip-dex-patch

# 仅执行指定功能的单条规则
python3 patcher.py --only "闪照"

# 临时跳过指定规则
python3 patcher.py --skip "群文件"
```

---

## 项目结构概览

```text
QQNT-Patcher/
├── pipeline/                             # 流水线各阶段调度实现
│   ├── __init__.py                       # 流水线主调度流程
│   ├── context.py                        # 全局上下文与构建参数
│   ├── stage1_prepare.py                 # 阶段 1：环境自检与扩展构建
│   ├── stage2_rules.py                   # 阶段 2：指纹分析与规则推导
│   ├── stage3_transform.py               # 阶段 3：内存 AST 局部重构
│   ├── stage4_pack.py                    # 阶段 4：产物聚合与注入整理
│   ├── stage5_sign.py                    # 阶段 5：签名与装配
│   └── providers/                        # 签名提供者实现
├── rules/                                # 插桩规则插件目录
│   ├── engine.py                         # 规则自动加载引擎
│   ├── base_rules.py                     # 核心事件与消息总线
│   ├── security_rules.py                 # 风控与事件拦截规则
│   ├── setting_rules.py                  # 设置中心与搜索索引规则
│   ├── tablet_rules.py                   # 平板模式规则
│   ├── group_file_rules.py               # 群文件下载次数规则
│   ├── qq_version_rules.py               # 发送者版本识别规则
│   ├── tg_sticker_rules.py               # 外部表情包规则
│   ├── browser_rules.py                  # 网页拦截放行规则
│   └── parser.py                         # DEX 高效流式扫描引擎
├── src/                                  # 运行时 Java 扩展源码
│   └── com/tencent/qqnt/patch/
│       ├── modules/                      # 核心业务模块
│       ├── plugin/                       # 动态脚本引擎与兼容层
│       ├── ui/                           # 设置界面与搜索注入
│       └── util/                         # 资产穿透与辅助工具
├── assets/                               # 图标与版本字典等内置资产
├── tools/                                # 打包工具链与依赖库
├── DexPatcher.java                       # 内存 AST 多线程局部重构器
├── patcher.py                            # 构建入口脚本
└── README.md
```

---

## 鸣谢与致敬

- [NeoApk / NPatch](https://github.com/HSSkyBoy/NeoApk)：提供流式 ZIP 处理与 V2 签名实现；
- [ApkSignatureKillerEx](https://github.com/Cattafish/ApkSignatureKillerEx)：提供免 Root 去签思路与底层拦截参考；
- [QFun](https://github.com/oneQAQone/QFun)：提供 QQNT 防撤回机制思路与脚本接口设计参考；
- [Smali / Baksmali / Dexlib2](https://github.com/JesusFreke/smali)：提供强大的 DEX 字节码重构支持。

---

## 免责声明

本项目仅用于 Android 逆向工程、安全研究与技术交流。请勿将本项目用于任何违反法律法规或侵犯他人合法权益的场景。因使用本项目所产生的一切后果由使用者自行承担。
