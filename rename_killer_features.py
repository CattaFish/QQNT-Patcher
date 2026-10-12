#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
去签特征一键重命名器
====================================================
用途:
    把 ApkSignatureKillerEx / QQNT-Patcher 中所有公开/硬编码的去签特征
    (包名、SO名、运行时文件名、内嵌资产路径、日志 tag、marker、
     memfd名) 统一换成无特征随机名。

用法:
    python3 rename_killer_features.py                 # 打印计划 (dry-run)
    python3 rename_killer_features.py --apply         # 真正执行替换
    python3 rename_killer_features.py --apply --config my_config.json

说明:
    - 第一次运行会生成 zzz_killer_rename_config.json 保存本次映射
    - 之后再跑会复用该映射 (幂等)
    - 删掉该 json 再跑 = 生成一套新随机名
"""
import argparse
import json
import os
import random
import string
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "zzz_killer_rename_config.json"

# 生成随机短标识符 (不含数字开头, 适配 java/C/python 标识符)
def rand_id(n=3):
    return "".join(random.choice(string.ascii_lowercase) for _ in range(n))

def rand_libname(n=3):
    # 只生成字母, 避免某些系统对纯数字 so 名的奇怪处理
    return "".join(random.choice(string.ascii_lowercase) for _ in range(n))

def default_mapping():
    """
    返回本次需要替换的完整映射表。
    以下为默认配置, 也可以直接在文件里手动改。
    """
    pkg = f"x{rand_id(2)}"          # 新包名第一段
    cls = f"y{rand_id(2)}"          # 新包名第二段
    new_pkg = f"{pkg}.{cls}"        # 示例: xu.ya
    lib = "lib" + rand_libname() + ".so"
    signed = rand_libname() + rand_id(1)
    entry = f"assets/{rand_id(4)}/{rand_id(5)}.apk"
    marker = "KZ_%s_%s_%s" % (rand_id(4), str(random.randint(1000,9999)), rand_id(3))
    memfd = "v" + rand_id(3)

    return {
        # ---- Java 包名 / 类名 ----
        # 完整包名.类名 (长串优先, 避免子串误替换)
        "r.s.sign.KillerApplication": f"{new_pkg}.KillerApplication",
        "r.s.sign.KillerProvider": f"{new_pkg}.KillerProvider",
        "r.s.sign": f"{new_pkg}",
        # smali / JNI 用斜杠形式 (长串优先)
        "r/s/sign/KillerApplication": f"{new_pkg.replace('.', '/')}/KillerApplication",
        "r/s/sign/KillerProvider": f"{new_pkg.replace('.', '/')}/KillerProvider",
        "r/s/sign": f"{new_pkg.replace('.', '/')}",

        # ---- SO 文件名 / loadLibrary 名 ----
        "libZcraft.so": lib,
        "libSignedByRS.so": lib,
        "libzcraft.so": lib,
        '"zcraft"': f'"{lib[3:-3]}"',   # System.loadLibrary("...") 去掉 lib 前缀和 .so
        '"SignedByRS"': f'"{lib[3:-3]}"',

        # ---- 运行时解包文件名 (dataDir 下) ----
        '"signed.apk"': f'"{signed}"',

        # ---- 内嵌资产固定路径 (构建期 + 运行时) ----
        '"assets/Zcraft/input.apk"': f'"{entry}"',
        '"Zcraft/input.apk"': f'"{entry}"',
        '"assets/input.apk"': f'"{entry}"',
        '"input.apk"': f'"{entry}"',

        # ---- Native 层特征字符串 ----
        "SIGB_PATH_REWRITE_20261001_829B367": marker,
        "npatch_proc_view": memfd,
        '"xhook"': '"kq"',          # xhook log tag (xh_log.c)
    }

def load_config():
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception:
            pass
    cfg = default_mapping()
    CONFIG_PATH.write_text(
        json.dumps(cfg, indent=2, ensure_ascii=False),
        encoding="utf-8"
    )
    return cfg

# 需要处理的文本扩展名
TEXT_EXTS = {
    ".java", ".kt", ".kts", ".c", ".h", ".cpp", ".cc", ".py",
    ".xml", ".gradle", ".yml", ".yaml", ".json", ".md", ".pro",
    ".properties", ".txt",
}

# 要跳过的目录
SKIP_DIRS = {".git", ".gradle", ".idea", "build", "out", "__pycache__"}
# 要跳过的文件名
SKIP_FILES = {
    "rename_killer_features.py",
    "zzz_killer_rename_config.json",
    "merged_qqntpatch.txt",
    "merged_ApkKiller.txt",
    "all_modified.diff",
    "libk_disasm.txt",
}

def iter_target_files():
    for root, dirs, files in os.walk(HERE):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if name in SKIP_FILES:
                continue
            p = Path(root) / name
            if p.suffix.lower() in TEXT_EXTS:
                yield p

def apply_mapping(text, mapping):
    total = 0
    for old, new in sorted(mapping.items(), key=lambda kv: -len(kv[0])):
        if old == new:
            continue
        cnt = text.count(old)
        if cnt:
            text = text.replace(old, new)
            total += cnt
    return text, total

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="真正执行替换")
    ap.add_argument("--config", help="指定配置文件路径 (默认 zzz_killer_rename_config.json)")
    args = ap.parse_args()

    global CONFIG_PATH
    if args.config:
        CONFIG_PATH = Path(args.config)

    cfg = load_config()
    print("\n===== 去签特征重命名映射 =====")
    for old, new in sorted(cfg.items(), key=lambda kv: -len(kv[0])):
        print(f"  {old}  →  {new}")
    print("===============================\n")

    if not args.apply:
        print("[dry-run] 未执行替换. 如需写入, 加 --apply\n")
        return

    changed_files = []
    for p in iter_target_files():
        try:
            original = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        modified, count = apply_mapping(original, cfg)
        if count:
            p.write_text(modified, encoding="utf-8")
            changed_files.append((str(p), count))

    print(f"[done] 共修改 {len(changed_files)} 个文件:")
    for path, cnt in sorted(changed_files, key=lambda x: x[0]):
        print(f"  {cnt:4d}  {path}")

if __name__ == "__main__":
    main()
