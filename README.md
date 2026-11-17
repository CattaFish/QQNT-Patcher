# QQNT-Patcher

主人好喵！欢迎来到 QQNT-Patcher~ 这是一个专门给 Android QQNT 做修补、去签和跑脚本的小工具，不用 Root 也不用装 Xposed 或 LSPosed 框架就能直接用喵！

- Telegram 频道：[ZcraftMod](https://t.me/ZcraftMod)
- GitHub 仓库：[Cattafish/QQNT-Patcher](https://github.com/Cattafish/QQNT-Patcher)

---

## 项目简介

QQNT-Patcher 会在本地把官方 QQ 安装包拆开，通过直接修改 DEX 字节码加上各种实用功能，再搭配去签壳和 BeanShell 3 脚本环境重新打包，装上就能直接用。

---

## 实现原理

- **打包与去签**：用 NeoApk 把原版 QQ 塞进 `assets/Zcraft/meow.apk`，没改到的资源直接复用原包数据，不占多余体积，同时保留原本的 `META-INF/` 证书文件并重新做 V2 签名。运行时注入 `libzcraft.so` 和 Java 代理拦住底层 IO 和系统签名校验；我们自己加进去的 `assets/` 文件通过 `PatchAssetHelper` 直接从外层包体读取。
- **构建流程**：打包脚本按 5 个步骤跑：检查工具并编译扩展代码 -> 提取原包信息并扫 DEX 定位要改的方法 -> 用 `DexPatcher` 修改目标 DEX -> 整理要注入的 DEX、so 库、图标和配置 -> 打包并签名。
- **规则扫描**：各个功能的修改规则放在 `rules/` 下，运行前自动读取。大部分规则通过特征字符串动态查找类名和方法名，尽量不写死混淆名，QQ 升级后也不容易失效。

---

## 兼容版本

- **支持版本**：QQ `9.2.90~9.3.85`
- **支持架构**：打包时会看原包里带了哪些架构的库再注入对应的 `libzcraft.so`，比如纯 64 位包只会注入 `arm64-v8a`。

---

## 当前功能

### 聊天增强
- **消息防撤回**：拦截私聊、群聊和漫游同步里的他人撤回，自己主动撤回正常放行，灰条支持点击跳回原消息。
- **闪照破解**：把收到的闪照和历史闪照转成普通图片，取消画廊大图的查看和保存限制。
- **发送者 QQ 版本识别**：在好友设置和群成员设置页面显示对方的客户端版本、系统、架构和历史捕获记录，会自动从云端同步版本字典。
- **打开本地聊天记录**：在设置里输入好友 QQ、UID 或群号，直接调用官方界面查看本地保存的历史消息，就算被单删或已退群也能看。
- **修改图片外显**：自定义发送图片和大表情的外显文字，支持写死固定文本、从本地 `summary.txt` 随机抽，或者配 HTTP 接口自动获取。
- **Telegram 表情包**：读取本地外部表情包文件夹，支持调整 3 到 8 列排版，也可以顺便隐藏官方商城表情和杂项入口。
- **群文件显示下载次数**：读取群文件回包，在文件列表里直接显示下载次数。
- **上传 APK 自动重命名**：发送本地 `.apk` 时自动按 `应用名_版本号.APK` 重命名，并清掉回包里被加上的 `.1` 后缀。
- **消息复读**：支持左滑消息直接复读，或者在气泡旁边显示 `+1` 按钮点击复读。
- **语音转发**：长按语音气泡多出转发按钮，可以把语音转发给好友或群聊。
- **文字表情混排**：输入框有文字或处于回复状态时，点击表情会先放进输入框，和文字一起发送。
- **解除左滑回复限制**：允许对所有类型的消息左滑回复。
- **去除回复自动艾特**：引用回复消息时，不再自动往输入框里塞 `@`。
- **查看聊天记录回复图片**：允许查看聊天记录里引用的图片消息。
- **喵喵助手**：发消息前按自定义规则替换文字，还可以在句尾加上语气词和符号。

### 净化与限制解除
- **屏蔽群聊 @全体成员**：过滤群聊 `@全体成员` 通知。
- **静默群待办强提醒**：屏蔽群待办通知弹窗。
- **隐藏主页横幅广告**：隐藏消息列表顶部的 VIP 续费、活动和游戏推广横幅，并直接把对应的广告请求拦下来。
- **隐藏空间广告**：隐藏 QQ 空间说说流和详情页里的推广广告。
- **查看被封禁群聊**：解除被封禁群聊进不去、看不了资料以及长按消息被限制的问题。
- **去除相册扫码登录检验**：允许从相册选图或长按图片二维码完成登录授权。
- **去除更新检测**：去掉关于页更新提示和强制更新弹窗，降级使用时不容易被强制踢下线。
- **强制平板模式**：把设备类型判定改成平板，开启双栏界面，重启 QQ 生效。
- **伪装非多窗口模式**：绕过分屏状态检测，解除分屏时无法扫码等限制。
- **禁用内置浏览器网页拦截**：绕过安全提示跳转，直接在内置浏览器打开第三方网页。

### 脚本与设置
- **动态脚本**：内置 BeanShell 3 引擎，把脚本放进指定目录就能跑。支持消息收发监听、群管操作、发包、Cookie 与 RKey 获取、气泡长按菜单以及聊天界面的悬浮球快捷菜单。
- **设置与日志**：在 QQ 原生设置页加上「Zzz」和「动态脚本」入口，接入设置搜索且支持结果置顶；设置里自带运行日志查看，可以按级别筛选、复制或导出成文件，也支持检查更新。
- **更换应用图标与名称**：打包前可以把自定义图标和 `names.txt` 放进 `icons/` 目录，装上后在杂项设置里随时切换桌面图标和名字，也支持添加桌面快捷方式。

---

## 外部目录与脚本编写

装好改过的安装包后，外部资源和脚本统一放在手机存储的这个目录里：

```text
/sdcard/Android/media/com.tencent.mobileqq/zzz/
├── latest.log           # 在设置里导出生成的运行日志
├── summary.txt          # 本地图片外显词库，每行写一条文本
├── stickers/            # 外部表情包目录，每个子文件夹是一个表情包
│   └── 示例表情包/
│       ├── __cover__.png
│       ├── 01.png
│       └── 02.webp
└── plugins/             # 动态脚本目录
    └── 示例脚本/
        ├── info.prop    # 脚本信息，包含 ID、名称等
        └── main.java    # 脚本入口源码
```

如果想把自定义桌面图标或预设脚本直接打进包里，可以在构建前把图标图片和 `names.txt` 放进项目根目录的 `icons/`，把脚本放进 `preset_plugins/`。

### 怎么写脚本

动态脚本的语法和接口直接沿用了 QFun 模块的规范，想自己写脚本可以直接参考 QFun 的官方文档：

- [QFun Plugin API 文档](https://github.com/oneQAQone/QFun/blob/main/doc/Plugin%20API.md)

---

## 环境准备

构建环境需要准备 Python 3、JDK 17、Android SDK 构建工具如 d8 和 apksigner，以及 curl。

### Linux 与 WSL 环境
```bash
sudo apt update
sudo apt install python3 openjdk-17-jdk android-sdk-build-tools curl -y
```

### Termux 环境
```bash
pkg update
pkg install python openjdk-17 d8 apksigner android-tools curl -y
```

---

## 快速开始

### 步骤 1：下载基础依赖库

在项目根目录下运行下面的命令，把需要的依赖下载到 `tools/` 目录：

```bash
mkdir -p tools

# 字节码解析与资源修改工具
curl -L -o tools/baksmali.jar https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar
curl -L -o tools/smali.jar https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar
curl -L -o tools/dexlib2.jar https://repo1.maven.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar
curl -L -o tools/guava.jar https://repo1.maven.org/maven2/com/google/guava/guava/18.0/guava-18.0.jar
curl -L -o tools/arsclib.jar https://repo1.maven.org/maven2/io/github/reandroid/ARSCLib/1.4.0/ARSCLib-1.4.0.jar

# 脚本引擎与协议依赖
curl -f -L -o tools/bsh.aar https://repo1.maven.org/maven2/io/github/copylibs/beanshell-android-lambda/3.0.0.beta10/beanshell-android-lambda-3.0.0.beta10.aar
python3 -c "import zipfile, os; open('tools/bsh.jar', 'wb').write(zipfile.ZipFile('tools/bsh.aar').read('classes.jar')); os.remove('tools/bsh.aar')"
curl -L -o tools/dx.jar https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/9.0.0_r3/dalvik-dx-9.0.0_r3.jar
curl -L -o tools/protobuf.jar https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.25.3/protobuf-java-3.25.3.jar

# Android 编译期桩库
curl -L -o tools/android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
```

---

### 步骤 2：准备原生库与打包引擎

1. **准备 `tools/killer_lib`**：放好编译完成的各架构 `libzcraft.so`，或者直接从官方 Releases 下载解压。
2. **准备 `tools/neoapk.jar`**：放好编译完成的 NeoApk 单体 JAR 包。

---

### 步骤 3：放入原版 APK 并开始构建

把官方原版 QQ 安装包改名为 `QQ.apk` 放到项目根目录下：

#### 方式 A：默认带去签打包
内嵌官方原包并注入去签模块，免 Root 直接安装：
```bash
python3 patcher.py QQ.apk QQ_Patched.apk
```

#### 方式 B：Debug 签名打包
不注入去签壳也不嵌原包，直接使用 Debug 密钥签名，包体积更小，适合系统已经做了核心破解的环境：
```bash
python3 patcher.py --signer=debug QQ.apk QQ_Patched.apk
# 也可以简写为
python3 patcher.py --no-killer QQ.apk QQ_Patched.apk
```

#### 方式 C：不签名打包
跳过签名步骤，方便后续自己签名：
```bash
python3 patcher.py --signer=none QQ.apk QQ_Patched.apk
# 也可以简写为
python3 patcher.py -n QQ.apk QQ_Patched.apk
```

#### 常用调试参数
```bash
# 清空构建缓存
python3 patcher.py --clean

# 跳过修改宿主 DEX，只编译注入扩展代码
python3 patcher.py --skip-dex-patch

# 只启用指定规则
python3 patcher.py --only "闪照"

# 跳过指定规则
python3 patcher.py --skip "群文件"
```

---

## 项目结构

```text
QQNT-Patcher/
├── pipeline/                      # 打包流程各个步骤的脚本
│   ├── __init__.py                # 主调度入口
│   ├── context.py                 # 构建参数与上下文
│   ├── features.py                # 功能开关与依赖关系
│   ├── killer_config.py           # 去签配置
│   ├── killer_payload.py          # 编译去签 Application DEX
│   ├── subid_sync.py              # 同步云端 SubID 字典
│   ├── stage1_prepare.py          # 阶段 1：检查环境与编译扩展代码
│   ├── stage2_rules.py            # 阶段 2：读取原包指纹并生成修改规则
│   ├── stage3_transform.py        # 阶段 3：批量修改目标 DEX
│   ├── stage4_pack.py             # 阶段 4：整理 DEX、so 库、资产和自定义图标
│   ├── stage5_sign.py             # 阶段 5：打包并签名
│   └── providers/                 # 不同签名模式的实现
├── rules/                         # 各功能的 DEX 扫描与插桩规则
├── src/                           # 注入进 QQ 运行的 Java 源码
├── tools/                         # 打包工具链与依赖库
│   ├── NeoPacker.java             # 负责流式打包、复用原包条目和 V2 签名
│   └── IconManifestPatcher.java   # 修改 Manifest 和 resources.arsc 注入桌面图标
├── killer/                        # 去签 Application Java 模板与 stub
├── killer_native/                 # libzcraft.so 的 C 源码与 xHook
├── assets/                        # 图标和版本字典等内置文件
├── icons/                         # 打包时放入的自定义桌面图标与名称配置
├── DexPatcher.java                # 多线程反汇编、改 Smali 和重汇编 DEX
├── patcher.py                     # 构建入口脚本
└── README.md
```

---

## 鸣谢

- [qqDecoder](https://github.com/CattaFish/qqDecoder)：提供客户端 SubID 版本映射信息
- [NeoApk / NPatch](https://github.com/HSSkyBoy/NeoApk)：提供流式 ZIP 处理与 V2 签名实现
- [ApkSignatureKillerEx](https://github.com/CattaFish/ApkSignatureKillerEx)：提供免 Root 去签思路与底层拦截参考
- [QFun](https://github.com/oneQAQone/QFun)：提供 QQNT 防撤回思路、脚本引擎与 API 设计参考
- [Smali / Baksmali / Dexlib2](https://github.com/JesusFreke/smali)：提供 DEX 反汇编与重汇编支持
- [ARSCLib](https://github.com/REAndroid/ARSCLib)：提供 Manifest 与资源表修改支持

---

## 免责声明

本项目仅供 Android 逆向学习、安全研究与技术交流使用，请勿用于任何违反法律法规或侵犯他人合法权益的场景，由此产生的一切后果由使用者自行承担。