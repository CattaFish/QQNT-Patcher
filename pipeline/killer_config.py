# -*- coding: utf-8 -*-
"""
去签载荷统一配置区 —— 只改这个文件，其余模块全部读取这里。
"""

# ========== 1. 自定义类与命名 ==========
KILLER_APP_CLASS    = "com.tencent.qqnt.patch.killer.App"   # Java FQCN（不允许与输入 APK 已有类冲突，stage2 会校验）
LIB_NAME            = "zcraft"                              # System.loadLibrary("zcraft")
SO_FILE_NAME        = "libzcraft.so"                        # 实际 so 文件名
ASSET_DIR           = "Zcraft"                              # assets 子目录
ASSET_NAME          = "meow.apk"                            # assets 内原包文件名
FILES_DIR           = "files/zcraft"                        # 释放目录（相对 /data/data/<pkg>/）

# ========== 2. HostEntry 路径策略 ==========
HOST_ENTRY_MODE     = "random"                              # random | fixed
HOST_ENTRY_FIXED    = "assets/Zcraft/meow.apk"              # fixed 模式使用

# ========== 3. Tinker 目标 ==========
TINKER_APP_CLASS    = "Lcom/tencent/tinker/loader/app/TinkerApplication;"
TINKER_ORIG_SUPER   = "Landroid/app/Application;"
TINKER_FLAGS_METHOD = "getTinkerFlags()I"

# ========== 4. 签名模式 ==========
SIGNATURE_MODE      = "cert_der"                            # cert_der（优先）| p7_raw

# ========== 5. 目录约定（一般不用改） ==========
KILLER_SRC_DIR      = "killer"                              # App.java.tpl + stub/ 所在
KILLER_NATIVE_DIR   = "killer_native"                       # mt_jni.c + xhook 源码
KILLER_LIB_DIR      = "tools/killer_lib"                    # 预编 libzcraft.so（CI 或本地产出）
