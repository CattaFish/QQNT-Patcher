#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QQNT-Patcher 自动化构建总线入口
架构已完全解耦为 pipeline/* 分层阶段
"""

import os
import sys
from pipeline import PipelineContext, run_pipeline

def main():
    args = sys.argv[1:]
    no_sign = False
    skip_dex_patch = False
    no_killer = False
    skipped_keywords = []
    only_keywords = []

    if "--no-sign" in args or "-n" in args:
        no_sign = True
        if "--no-sign" in args: args.remove("--no-sign")
        if "-n" in args: args.remove("-n")

    if "--skip-dex-patch" in args:
        skip_dex_patch = True
        args.remove("--skip-dex-patch")

    signer_choice = None
    for arg in list(args):
        if arg.startswith("--signer="):
            signer_choice = arg.split("=")[1]
            args.remove(arg)
            break
    if signer_choice == "none": no_sign = True
    elif signer_choice == "debug": no_killer = True

    if "--no-killer" in args:
        no_killer = True
        args.remove("--no-killer")

    while "--skip" in args:
        idx = args.index("--skip")
        if idx + 1 < len(args):
            skipped_keywords.append(args[idx + 1])
            del args[idx:idx + 2]
        else:
            args.remove("--skip")

    while "--only" in args:
        idx = args.index("--only")
        if idx + 1 < len(args):
            only_keywords.append(args[idx + 1])
            del args[idx:idx + 2]
        else:
            args.remove("--only")

    input_apk = args[0] if len(args) > 0 else "QQ.apk"
    output_apk = args[1] if len(args) > 1 else "QQ_Patched.apk"

    if not os.path.exists(input_apk):
        print(f"\033[1;31m[ERROR]\033[0m 未找到输入 APK 文件: {input_apk}")
        sys.exit(1)

    ctx = PipelineContext(
        input_apk=input_apk,
        output_apk=output_apk,
        no_sign=no_sign,
        skip_dex_patch=skip_dex_patch,
        no_killer=no_killer,
        skipped_keywords=skipped_keywords,
        only_keywords=only_keywords
    )
    
    os.makedirs(ctx.work_dir, exist_ok=True)
    run_pipeline(ctx)

if __name__ == "__main__":
    main()
