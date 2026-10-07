# -*- coding: utf-8 -*-
import os
import re
import json
import shlex
import shutil
import zipfile

def run_stage4(ctx):
    ctx.log("INFO", "4. 正在准备注入目录 (收集 Dex、SO、资产与扩展包)...")

    inject_dir = os.path.join(ctx.work_dir, "inject")
    if os.path.exists(inject_dir): shutil.rmtree(inject_dir, ignore_errors=True)
    os.makedirs(inject_dir, exist_ok=True)

    # 4.1 收集已修改的宿主 Dex
    for local_path, in_zip_name in ctx.modified_dex_files:
        if os.path.isfile(local_path):
            shutil.copyfile(local_path, os.path.join(inject_dir, in_zip_name))

    # 4.2 收集 Native 补丁库
    for local_so, in_zip_so in ctx.patched_so_files:
        if os.path.isfile(local_so):
            target_so_dir = os.path.join(inject_dir, os.path.dirname(in_zip_so))
            os.makedirs(target_so_dir, exist_ok=True)
            shutil.copyfile(local_so, os.path.join(inject_dir, in_zip_so))

    # 4.3 收集扩展库 Dex
    libs_dex_name = f"classes{ctx.max_dex_idx + 1}.dex"
    patch_dex_name = f"classes{ctx.max_dex_idx + 2}.dex"
    if ctx.libs_dex_path and os.path.isfile(ctx.libs_dex_path):
        shutil.copyfile(ctx.libs_dex_path, os.path.join(inject_dir, libs_dex_name))

    if ctx.patch_dex_path and os.path.isfile(ctx.patch_dex_path):
        shutil.copyfile(ctx.patch_dex_path, os.path.join(inject_dir, patch_dex_name))

    # 4.4 从 Provider 索取额外的 Dex 载荷 (killer 特性开启时注入)
    if "killer" in ctx.active_features:
        for dex_name, src_path in ctx.provider.get_extra_dexes(ctx):
            shutil.copyfile(src_path, os.path.join(inject_dir, dex_name))
            ctx.log("OK", f"-> [Provider:{ctx.provider.name}] 注入 Dex: {dex_name}")

    # 4.5 从 Provider 索取额外的 SO 载荷 (killer 特性开启时注入)
    if "killer" in ctx.active_features:
        apk_abis = set()
        with zipfile.ZipFile(ctx.input_apk, 'r') as zf:
            for n in zf.namelist():
                m = re.match(r'^lib/([^/]+)/', n)
                if m: apk_abis.add(m.group(1))

        for abi, rel_so_path, src_path in ctx.provider.get_extra_sos(ctx, apk_abis):
            dst_so_dir = os.path.join(inject_dir, "lib", abi)
            os.makedirs(dst_so_dir, exist_ok=True)
            shutil.copyfile(src_path, os.path.join(dst_so_dir, os.path.basename(rel_so_path)))
            ctx.log("OK", f"-> [Provider:{ctx.provider.name}] 注入 Native 库: {rel_so_path}")

    # 4.6 挂载静态资产与扩展插件 (按需注入)
    assets_dir = os.path.join(inject_dir, "assets")
    os.makedirs(assets_dir, exist_ok=True)

    # 写入构建期特性激活清单，供 Java 运行时精确感知
    features_json_path = os.path.join(assets_dir, "zzz_features.json")
    with open(features_json_path, "w", encoding="utf-8") as f:
        json.dump(list(ctx.active_features), f, ensure_ascii=False, indent=2)
    ctx.log("OK", f"-> 已生成特性激活清单: assets/zzz_features.json ({len(ctx.active_features)} 项)")

    # 仅在 script 特性激活时，向包体注入脚本引擎和预设插件
    if "script" in ctx.active_features:
        if ctx.bsh_standalone_dex and os.path.isfile(ctx.bsh_standalone_dex):
            shutil.copyfile(ctx.bsh_standalone_dex, os.path.join(assets_dir, "bsh.dex"))
            ctx.log("OK", "-> 注入脚本引擎: assets/bsh.dex")

        if ctx.preset_plugins_zip and os.path.isfile(ctx.preset_plugins_zip):
            shutil.copyfile(ctx.preset_plugins_zip, os.path.join(assets_dir, "preset_plugins.zip"))
            ctx.log("OK", "-> 注入预设脚本包: assets/preset_plugins.zip")
    else:
        ctx.log("WARN", "已跳过 [script] 特性: 拒绝向包体注入 bsh.dex 与预设脚本资产")

    # 注入通用图标资源
    if "setting" in ctx.active_features and os.path.isdir(ctx.assets_src_dir):
        for root, _, files in os.walk(ctx.assets_src_dir):
            for f in files:
                if not f.startswith("."):
                    src_fp = os.path.join(root, f)
                    rel_fp = os.path.relpath(src_fp, ctx.assets_src_dir)
                    dst_fp = os.path.join(assets_dir, rel_fp)
                    os.makedirs(os.path.dirname(dst_fp), exist_ok=True)
                    shutil.copyfile(src_fp, dst_fp)
                    ctx.log("OK", f"-> 注入静态图标资源: assets/{rel_fp}")

    ctx.log("OK", "-> 注入目录整理就绪 (绝无 input.apk 冗余磁盘拷贝)")
