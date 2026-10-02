# -*- coding: utf-8 -*-
import os
import sys
import shutil
import shlex
import zipfile

def run_stage1(ctx):
    ctx.log("INFO", "1. 正在准备构建环境与扩展 Dex...")
    
    # 1. 编译 DexPatcher.java
    engine_src = os.path.join(ctx.root_dir, "DexPatcher.java")
    ctx.engine_bin = os.path.join(ctx.work_dir, "patcher_bin")
    engine_class = os.path.join(ctx.engine_bin, "com/tencent/qqnt/patcher/DexPatcher.class")
    
    if not (os.path.exists(engine_class) and os.path.getmtime(engine_class) >= os.path.getmtime(engine_src)):
        os.makedirs(ctx.engine_bin, exist_ok=True)
        cp = f"{shlex.quote(ctx.dexlib2_jar)}:{shlex.quote(ctx.smali_jar)}:{shlex.quote(ctx.baksmali_jar)}"
        ctx.run_cmd(f"javac -cp {cp} -d {shlex.quote(ctx.engine_bin)} {shlex.quote(engine_src)}")

    # 2. 增量编译纯 Java 扩展 (src -> classes)
    src_dir = os.path.join(ctx.root_dir, "src")
    bin_dir = os.path.join(ctx.work_dir, "bin")
    dex_out = os.path.join(ctx.work_dir, "dex_out")
    target_patch_dex = os.path.join(dex_out, "patch_classes.dex")
    libs_cached_dex = os.path.join(ctx.work_dir, "libs_cached.dex")
    
    java_files = [os.path.join(r, f) for r, _, fs in os.walk(src_dir) for f in fs if f.endswith(".java")]
    os.makedirs(bin_dir, exist_ok=True)
    os.makedirs(dex_out, exist_ok=True)

    # 基础依赖库 Dex 编译
    dep_mtime = max(
        os.path.getmtime(ctx.dx_jar) if os.path.exists(ctx.dx_jar) else 0,
        os.path.getmtime(ctx.protobuf_jar) if os.path.exists(ctx.protobuf_jar) else 0
    )
    if not os.path.exists(libs_cached_dex) or os.path.getmtime(libs_cached_dex) < dep_mtime:
        ctx.log("INFO", "检测到基础依赖库更新，正在预编译基础依赖 Dex (dx + protobuf)...")
        libs_temp_dir = os.path.join(ctx.work_dir, "libs_temp")
        os.makedirs(libs_temp_dir, exist_ok=True)
        ctx.run_cmd(f"d8 --min-api 26 --output {shlex.quote(libs_temp_dir)} {shlex.quote(ctx.dx_jar)} {shlex.quote(ctx.protobuf_jar)}")
        temp_out = os.path.join(libs_temp_dir, "classes.dex")
        if os.path.exists(temp_out): shutil.move(temp_out, libs_cached_dex)
        shutil.rmtree(libs_temp_dir, ignore_errors=True)

    # 业务代码增量重编
    latest_src_mtime = max((os.path.getmtime(f) for f in java_files), default=0)
    if not os.path.exists(target_patch_dex) or os.path.getmtime(target_patch_dex) < latest_src_mtime:
        modified_java = []
        for jf in java_files:
            rel = os.path.relpath(jf, src_dir)
            cf = os.path.join(bin_dir, os.path.splitext(rel)[0] + ".class")
            if not os.path.exists(cf) or os.path.getmtime(jf) > os.path.getmtime(cf):
                modified_java.append(jf)

        if modified_java:
            quoted_java = [shlex.quote(f) for f in modified_java]
            cp_dep = f"{shlex.quote(ctx.android_jar)}:{shlex.quote(bin_dir)}:{shlex.quote(ctx.dx_jar)}:{shlex.quote(ctx.protobuf_jar)}"
            ctx.run_cmd(f"javac -cp {cp_dep} -sourcepath {shlex.quote(src_dir)} -d {shlex.quote(bin_dir)} " + " ".join(quoted_java))

        patch_classes = [os.path.join(r, f) for r, _, fs in os.walk(bin_dir) for f in fs if f.endswith(".class")]
        if patch_classes:
            quoted_classes = [shlex.quote(f) for f in patch_classes]
            temp_patch_dir = os.path.join(ctx.work_dir, "patch_temp")
            os.makedirs(temp_patch_dir, exist_ok=True)
            ctx.run_cmd(f"d8 --min-api 26 --output {shlex.quote(temp_patch_dir)} " + " ".join(quoted_classes))
            t_out = os.path.join(temp_patch_dir, "classes.dex")
            if os.path.exists(t_out): shutil.move(t_out, target_patch_dex)
            shutil.rmtree(temp_patch_dir, ignore_errors=True)

    ctx.libs_dex_path = libs_cached_dex if os.path.exists(libs_cached_dex) else None
    ctx.patch_dex_path = target_patch_dex if os.path.exists(target_patch_dex) else None

    # 3. 编译 bsh 资产 Dex
    bsh_dex_dir = os.path.join(ctx.work_dir, "bsh_dex")
    target_bsh_dex = os.path.join(bsh_dex_dir, "classes.dex")
    final_bsh_dex = os.path.join(ctx.work_dir, "bsh.dex")
    bsh_dep_mtime = max(os.path.getmtime(ctx.bsh_jar), dep_mtime)

    if not (os.path.exists(final_bsh_dex) and os.path.getmtime(final_bsh_dex) >= bsh_dep_mtime):
        os.makedirs(bsh_dex_dir, exist_ok=True)
        ctx.run_cmd(f"d8 --min-api 26 --output {shlex.quote(bsh_dex_dir)} {shlex.quote(ctx.bsh_jar)} {shlex.quote(ctx.dx_jar)} {shlex.quote(ctx.protobuf_jar)}")
        if os.path.exists(target_bsh_dex): shutil.copyfile(target_bsh_dex, final_bsh_dex)

    ctx.bsh_standalone_dex = final_bsh_dex if os.path.exists(final_bsh_dex) else None

    # 4. 打包预设插件
    valid_files = [os.path.join(r, f) for r, _, fs in os.walk(ctx.preset_plugins_dir) for f in fs if f not in (".gitkeep", "README.md")]
    if valid_files:
        p_zip = os.path.join(ctx.work_dir, "preset_plugins.zip")
        latest_p_mtime = max(os.path.getmtime(f) for f in valid_files)
        if not (os.path.exists(p_zip) and os.path.getmtime(p_zip) >= latest_p_mtime):
            with zipfile.ZipFile(p_zip, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
                for fp in valid_files:
                    zf.write(fp, os.path.relpath(fp, ctx.preset_plugins_dir))
        ctx.preset_plugins_zip = p_zip

    # 编译 NeoPacker.java
    packer_src = os.path.join(ctx.tools_dir, "NeoPacker.java")
    packer_class = os.path.join(ctx.engine_bin, "com/tencent/qqnt/patcher/NeoPacker.class")
    neoapk_jar = os.path.join(ctx.tools_dir, "neoapk.jar")
    
    if os.path.isfile(packer_src) and not (os.path.exists(packer_class) and os.path.getmtime(packer_class) >= os.path.getmtime(packer_src)):
        os.makedirs(ctx.engine_bin, exist_ok=True)
        ctx.run_cmd(f"javac -cp {shlex.quote(neoapk_jar)} -d {shlex.quote(ctx.engine_bin)} {shlex.quote(packer_src)}")

