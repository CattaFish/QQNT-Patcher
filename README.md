# QQNT-Patcher

主人好喵！欢迎来到 QQNT-Patcher~ 这里是一只专门给 Android QQNT 做静态修补与动态扩展的小工具，不用 Root 也不依赖任何 Xposed / LSPosed 框架就能直接跑起来喵！

- Telegram 频道：[ZcraftMod](https://t.me/ZcraftMod)
- GitHub 仓库：[Cattafish/QQNT-Patcher](https://github.com/Cattafish/QQNT-Patcher)

---

## 项目简介

QQNT-Patcher 是一款针对 Android QQNT 架构的自动化静态字节码修补与运行时扩展工具。通过直接对官方安装包中的目标 Dex 进行纯内存流式 AST 局部重构，联动原位 Patch 底层 Native 查签指令，追加独立扩展 Dex，并内置现代化的动态 Java 脚本引擎，实现免框架、免 Root 的极致功能增强。

### 为什么采用静态 Patch
1. **规避框架特征检测**：核心修改直接固化于 Dex 字节码与 Native 指令中，运行时与官方原生代码流程一致，杜绝 Xposed / LSPosed 框架堆栈与特征检测。
2. **底层 Native 查签穿透**：针对 arm64-v8a 底层 `libcodecwrapperV2.so` 进行精准指令级对齐替换（原位 Bypass 校验跳转），从根本上避免签名校验异常。
3. **全闭环纯内存 AST 流式重构**：打通 Smali 底层 ANTLR 词法/语法分析器与 Dexlib2 `MemoryDataStore` 管道，在 JVM 堆内存中即时完成 AST 遍历与字节码装配，全程零临时文件落盘，消除闪存磨损与多线程 I/O 竞争。
4. **双重细粒度增量缓存机制**：
   - **规则推导级缓存**：基于规则模块源码哈希感知变动，未修改的规则推导 0 秒直接复用。
   - **Dex 分包级缓存**：基于原 Dex 内容与命中规则组合计算哈希，未受影响的分包完全跳过反编译与重构，二次构建仅耗费打包与签名耗时。
5. **规范化纯净编译链**：工程接入标准 Android SDK 编译期桩库（`android.jar`），杜绝生成 DEX 被冗余系统类污染，从源头保证运行稳定性。
6. **动态脚本生态扩展**：内置现代 BeanShell 3 运行时与原生 SSO 协议发包管道，全面兼容 QFun 生态的动态 Java 脚本。
7. **4 字节页面对齐**：构建链自动执行 `zipalign` 4 字节对齐，深度优化在 Android 11~15 系统上的冷启动加载效率与内存占用。

---

## 版本兼容性说明

- **当前主力测试版本**：QQ `9.3.55`
- **最低兼容测试版本**：QQ `9.2.90`
- **扩展支持**：理论兼容大部分基于 NT 架构的官方 QQ 安装包，构建工具会自动进行基于语义和指令的动态特征探测。

---

## 当前功能特性

### 1. 核心功能模块
- **消息防撤回**：拦截私聊与群聊实时撤回，树形递归过滤后台同步包，自身撤回正常放行；生成可点击打开资料卡、点击定位并高亮原消息的富文本交互灰条。
- **闪照破解与画廊放行**：实时推送与历史闪照自动转为普通图片并支持长按保存，解除 AIO 沉浸式画廊对闪照资源的查看与下载限制。
- **静默 @全体 与群待办**：拦截群内 `@全体成员` 强提醒与 `0x135` / 天枢群待办弹窗，仅放行真正 `@我` 的消息。
- **群文件显示下载次数**：动态拦截并解析群文件列表回包，在文件列表与卡片上直观显示下载次数（兼容 9.2.90 ~ 9.3.60+ 新旧渲染通道）。
- **发送 APK 自动重命名**：本地发送 `.apk` 文件时自动读取内部应用名与版本号，规范化重命名为 `应用名_版本号.APK`，防止被系统或平台策略拦截。
- **强制平板模式**：全动态穿透设备类型判定，强制开启 QQ 原厂双栏折叠屏/平板 UI 布局（需重启生效）。
- **喵喵助手**：发送消息时自动进行人称替换（“你” -> “主人”、“我” -> “猫猫”），智能识别尾部标点与括号并适配喵化尾缀。
- **会话感知悬浮球**：仅在进入聊天会话（AIO）时自动显示悬浮球，支持拖动与贴边停靠，退出聊天自动隐藏，一键唤出脚本快捷动作面板。
- **气泡长按菜单扩展**：支持脚本将自定义功能项注册到原生消息气泡长按弹出的上下文菜单中。
- **修改图片与表情外显**：自定义发送普通图片与商城大表情在聊天列表和消息气泡的外显文本。支持固定文本（$\le 30$ 字）或 HTTP(S) API 接口，支持嵌套 JSON Key 深度递归查找，发送后自动异步轮询更新下一条。

### 2. 动态脚本引擎（兼容 QFun 生态）
- **免框架热插拔**：外部存储放入脚本即时生效，支持标准 **Java 语法**（完整支持 Lambda 表达式与流式 API）。
- **底层 SSO 协议发包**：内置 `PacketHelper` 与 `MsgSender`，打通底层 MSF 管道，支持构造并投递二进制 Protobuf / OIDB 协议报文。
- **消息发送全家桶**：支持发送纯文本、图文混排（`@成员`、图片）、Silk 语音（支持精确毫秒时长）、Ark 卡片、短视频、文件、回复特定消息、拍一拍与主动撤回。
- **全套群管能力**：支持单人/全员禁言与解禁、踢人拉黑、修改群名片、设置/取消管理、设置专属头衔、群打卡、获取群成员列表与禁言列表。
- **凭证与高清密钥提取**：一键提取 `Skey`、`Pskey`、`Pt4Token`、`GTK`、`bkn`，并自动嗅探捕获聊天原图/高清图 `RKey` 鉴权密钥。
- **跨会话持久化存储**：提供 `putString`、`getInt`、`putBoolean` 等多类型本地 JSON 键值对读写能力。
- **预设脚本开箱即用**：支持在项目 `preset_plugins/` 放置脚本或压缩包，构建时自动打包注入，冷启动增量校验解压并自动激活首启。

### 3. QQ 原生二级设置中心 (Zzz)
- **原生顶层挂载**：在 QQ 设置顶层自动挂载 **“Zzz”** 设置与 **“动态脚本”** 双卡片入口。
- **模块化控制台**：核心功能按需启闭，动态脚本支持独立启停、动作触发、一键重载与全局扫描。
- **应用内免电脑实时日志监视器**：内置 300 条环形内存日志池，设置内可随时唤出终端风格弹窗查看运行状态，支持一键清空与导出到本地 `latest.log`。
- **原厂红点联动**：支持与 QQ 原厂 `QUIBadge`（ID: `0x7f0a5eb2`）控件联动，接入版本更新检测。

### 4. 底层 Native 与反风控对齐
- **Native 指令原位 Bypass**：针对 arm64-v8a 底层 `libcodecwrapperV2.so` 签名校验精准原位 Patch 跳转。
- **全套动态查签致盲**：致盲主查签逻辑（`SignatureReport`、`SecMd5Entry`）、阻断启动自检（`SignatureScan`、`CheckSafeCenterConfig`）。
- **阻断风控打击与转储**：阻断 `MSFIntChkStrike` 完整性惩罚打击、内存篡改转储与通道事件批量上报。
- **指纹脱敏与签名固化**：脱敏 QSec 设备指纹与云控账号参数，阻断 Beacon 硬件指纹收集与涉诈敏感词扫描；全动态固化官方原版 APK MD5 与签名 Hash。

---

## 外部脚本存放路径

修补安装完成后，你可以将任意兼容 QFun 的脚本文件夹放入手机内部存储对应的目录下（若目录不存在可手动创建）：

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

**`info.prop` 配置示例**：
```properties
pluginId=QuickBar
pluginName=快捷动作栏
pluginVersion=1.0.0
pluginAuthor=Zcraft
```

放置脚本后，在 QQ 设置 -> **Zzz** -> **动态脚本** 中开启对应脚本，或点击“重新扫描全部脚本”即可热载生效。

---

## 常用脚本 API 快速参考

脚本内可直接使用 `api` 对象调用丰富的内置扩展能力：

| 分类 | 核心方法 | 说明 |
| :--- | :--- | :--- |
| **交互与菜单** | `api.addItem(name, callback)` | 注册悬浮球菜单动作 |
| | `api.addMenuItem(name, callback, types)` | 注册消息气泡长按菜单动作 |
| | `api.toast(msg)` / `api.qqToast(icon, msg)` | 弹出系统 / QQ 原生 Toast |
| **消息发送** | `api.sendMsg(peerUin, content, chatType)` | 发送文本/图文（支持 `[atUin=...]`、`[pic=...]`） |
| | `api.sendPtt(peerUin, path, chatType, ms)` | 发送语音（毫秒时长可选） |
| | `api.sendReplyMsg(peerUin, replyMsgId, msg, type)` | 发送回复特定消息 |
| | `api.sendCard(peerUin, json, chatType)` | 发送 Ark 卡片消息 |
| | `api.sendVideo(peerUin, path, chatType)` | 发送短视频 |
| | `api.sendFile(peerUin, path, chatType)` | 发送文件 |
| | `api.sendPai(toUin, peerUin, chatType)` | 发送双击拍一拍 |
| | `api.recallMsg(chatType, peerUin, msgId)` | 主动撤回指定消息 |
| **群管操作** | `api.shutUp(troopUin, memberUin, seconds)` | 禁言指定群成员（秒数，0 为解禁） |
| | `api.shutUpAll(troopUin, enable)` | 开启/关闭全员禁言 |
| | `api.kickGroup(troopUin, memberUin, block)` | 移出群成员（可选择拉黑） |
| | `api.changeMemberName(troopUin, memberUin, card)` | 修改群成员群名片 |
| | `api.setGroupAdmin(troopUin, memberUin, enable)` | 设置/取消群管理员 |
| | `api.setGroupMemberTitle(troopUin, uin, title)` | 设置群专属头衔 |
| | `api.clockIn(troopUin)` | 群打卡签到 |
| **凭证与密钥** | `api.getSkey()` / `api.getRealSkey()` | 获取 Skey |
| | `api.getPskey(domain)` | 获取指定域名的 Pskey |
| | `api.getGTK(domain)` / `api.getBkn(key)` | 计算 GTK / bkn 参数 |
| | `api.getFriendRKey()` / `api.getGroupRKey()` | 获取好友/群高清图片鉴权密钥串 |
| **数据持久化** | `api.putString(cfg, key, val)` / `getString` | 字符串持久化配置存储 |
| | `api.putInt` / `putLong` / `putBoolean` | 基础数值与布尔值存储 |

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
```

---

## 快速使用教程

### 步骤 1：拉取构建依赖组件

在项目根目录下执行以下命令，自动下载构建所需的 **8 个核心依赖 Jar 包**至 `tools/` 目录：

```bash
mkdir -p tools

# 1. Smali / Dexlib2 核心字节码工具
curl -L -o tools/baksmali.jar https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
curl -L -o tools/smali.jar https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
curl -L -o tools/dexlib2.jar https://repo1.maven.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar
curl -L -o tools/guava.jar https://repo1.maven.org/maven2/com/google/guava/guava/18.0/guava-18.0.jar

# 2. 动态脚本引擎与协议依赖 (BeanShell 3.0.0 + Dx + Protobuf)
curl -f -L -o tools/bsh.aar https://repo1.maven.org/maven2/io/github/copylibs/beanshell-android-lambda/3.0.0.beta10/beanshell-android-lambda-3.0.0.beta10.aar
python3 -c "import zipfile, os; open('tools/bsh.jar', 'wb').write(zipfile.ZipFile('tools/bsh.aar').read('classes.jar')); os.remove('tools/bsh.aar')"

curl -L -o tools/dx.jar https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/9.0.0_r3/dalvik-dx-9.0.0_r3.jar
curl -L -o tools/protobuf.jar https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.25.3/protobuf-java-3.25.3.jar

# 3. Android SDK 核心编译期标准桩库 (提供纯净系统符号，不打包进 APK)
curl -L -o tools/android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
```

---

### 步骤 2：放置官方原版 APK

将官方原版 QQ 安装包重命名为 `QQ.apk` 并放置在项目根目录下（或在后续命令行中指定路径）。

---

### 步骤 3：执行自动化修补

#### 方式 A：标准构建模式（自动重签名）
使用固定的 Debug 证书进行重签名（首次会自动生成 `tools/debug.keystore`，后续永久复用，方便覆盖安装）：
```bash
python3 patcher.py
```

#### 方式 B：不签名模式
添加 `--no-sign`（或简写 `-n`）参数跳过签名阶段，自动剔除损坏签名元数据，适合配合核心破解等系统级免签模块使用：
```bash
python3 patcher.py -n
```

#### 方式 C：调试专用指令（极速构建）
```bash
# 场景 1：完全不修改官方宿主 Dex，仅编译注入 src/ 扩展 Dex（调试 Java 代码推荐）
python3 patcher.py --skip-dex-patch

# 场景 2：调试时临时跳过某个耗时/异常规则
python3 patcher.py --skip "群文件"

# 场景 3：单点调试指定规则，跳过其他所有规则
python3 patcher.py --only "闪照"

# 场景 4：自定义输入与输出路径
python3 patcher.py 我的QQ.apk 输出_已修补.apk
```

---

### 步骤 4：在应用内开启功能

安装并登录修补后的 QQ：
1. 打开 QQ，点击左上角头像 -> **设置**。
2. 找到顶层的 **“Zzz”** 选项。
3. 进入后即可按需开启防撤回、闪照破解、悬浮球、喵喵助手，或者导入并管理动态脚本。

---

## 项目结构全景

```text
QQNT-Patcher/
├── src/                                  # 扩展功能 Java 源码与 QFun 生态环境桩
│   ├── com/tencent/qqnt/patch/
│   │   ├── modules/                      # 模块化功能解耦实现
│   │   │   ├── AntiRevokeModule.java     # 消息防撤回与灰条构造模块
│   │   │   ├── AutoRemarkApkModule.java  # 发送 APK 自动重命名模块
│   │   │   ├── AtAllNotifyBlockModule.java# 静默 @全体 与群待办通知模块
│   │   │   ├── FlashPicModule.java       # 闪照转换与画廊放行模块
│   │   │   ├── FloatingBallModule.java   # 悬浮球生命周期联动模块
│   │   │   ├── MeowModule.java           # 喵喵助手拦截与改写模块
│   │   │   ├── ShowDownloadTimesModule.java# 群文件下载次数解析展示模块
│   │   │   └── TabletModeModule.java     # 强制平板模式穿透模块
│   │   ├── plugin/                       # 动态脚本引擎与 QFun 兼容生态
│   │   │   ├── AioMenuInjector.java      # AIO 消息气泡长按菜单注入器
│   │   │   ├── CookieHelper.java         # Token / Skey / Pskey / GTK 获取服务
│   │   │   ├── FixClassLoader.java       # 脚本动态类与双亲委派隔离加载器
│   │   │   ├── FloatingBallManager.java  # 悬浮球窗口交互与状态感知管理器
│   │   │   ├── FriendHelper.java         # 好友列表查询与点赞助手
│   │   │   ├── MsgSender.java            # 底层发包全家桶管道
│   │   │   ├── PluginCompiler.java       # BeanShell 3 解释器生命周期封装
│   │   │   ├── PluginManager.java        # 插件热插拔、加载与事件广播中枢
│   │   │   ├── PluginMethod.java         # 暴露给脚本调用的全局 api 实例
│   │   │   ├── PresetPluginInstaller.java# 预设脚本自动化解压校验器
│   │   │   ├── RKeyManager.java          # 高清图片密钥拦截与解析器
│   │   │   ├── TroopHelper.java          # 群管、禁言与群信息助手
│   │   │   └── TroopMemberJoinHandler.java# 群成员进群推送解析器
│   │   ├── AppContext.java               # 全局 Context 与当前 Activity 顶层感知
│   │   ├── ConfigManager.java            # 配置持久化与运行时标记缓存
│   │   ├── IPatchModule.java             # 模块化统一生命周期接口
│   │   ├── ModuleManager.java            # 核心功能模块注册与调度管理器
│   │   ├── NativeSettingHelper.java      # QQ 原生双行/单行设置项反射辅助类
│   │   ├── PatchBridge.java              # 字节码直接调用的全局桩分发桥梁
│   │   ├── PLog.java                     # 内存环形日志监视器与应用内调试弹窗
│   │   ├── QUIBadgeHelper.java           # QQ 原厂 QUIBadge 红点控件联动
│   │   ├── SettingInjector.java          # QQ 设置中心顶层入口动态挂载
│   │   ├── ToastHelper.java              # 快速弹出/重叠中断 Toast 工具
│   │   ├── UpdateHelper.java             # 版本更新静默检测与手动检测逻辑
│   │   └── ZzzSettingFragment.java       # Zzz 二级原生设置中心界面
│   └── me/yxp/qfun/                      # QFun 脚本生态标准实体与环境桩
├── rules/                                # 声明式 Hook 规则与特征探测模块
│   ├── __init__.py                       # 规则入口调度与设置动态挂载导出
│   ├── base_rules.py                     # 核心扩展与分流规则
│   ├── group_file_rules.py               # 群文件下载次数全版本自适应规则引擎
│   ├── parser.py                         # FastDexParser DEX 内存流式语义扫描引擎
│   ├── security_rules.py                 # 全套动态防反外挂、防篡改、风控对齐规则
│   ├── setting_rules.py                  # 设置中心动态挂载规则引擎
│   ├── stubs.py                          # Smali 汇编空桩与重定向工厂
│   ├── tablet_rules.py                   # 平板模式动态穿透规则引擎
│   └── troop_todo_rules.py               # 群待办强提醒动态规则引擎
├── assets/
│   ├── script_icon.png                   # 动态脚本设置入口图标
│   └── zzz_icon.png                      # 设置入口与悬浮球图标静态资源
├── tools/                                # 构建依赖工具链与固定签名证书
│   ├── android.jar                       # Android SDK 编译期标准桩库 (API 30)
│   ├── baksmali.jar                      # Dex 字节码反汇编引擎
│   ├── smali.jar                         # Smali 汇编器
│   ├── dexlib2.jar                       # Dex 局部重构 AST 引擎
│   ├── guava.jar                         # 字节码工具依赖库
│   ├── bsh.jar                           # BeanShell 3 动态脚本引擎
│   ├── dx.jar                            # 字节码转译编译器
│   ├── protobuf.jar                      # 二进制 Protobuf 协议支持
│   └── debug.keystore                    # 固定签名证书 (首次自动生成)
├── DexPatcher.java                       # DEX 纯内存流式 AST 多线程批量编译修补引擎
├── native_patcher.py                     # arm64-v8a Native SO 二进制跳转原位修补器
├── patcher.py                            # 核心自动化构建执行与调度脚本
└── README.md
```

---

## 鸣谢与致敬

- [QFun](https://github.com/oneQAQone/QFun)：感谢项目提供的 QQNT 消息防撤回思路与 BeanShell 脚本生态 API 设计参考。
- [BeanShell](https://github.com/beanshell/beanshell)：感谢现代化 BeanShell 解释器团队提供的 Java 8+ 脚本运行支持。
- [Smali / Baksmali / Dexlib2](https://github.com/JesusFreke/smali)：感谢 JesusFreke 提供的强大 Dex 字节码重构库。

---

## 免责声明

本项目仅供 Android 逆向工程与 Dex 字节码静态插桩技术的研究与交流使用。请勿将本项目用于任何商业牟利或侵犯他人合法权益的场景。使用修改版本产生的任何后果由使用者自行承担。
