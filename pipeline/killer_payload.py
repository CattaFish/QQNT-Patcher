# -*- coding: utf-8 -*-
"""
把 killer/App.java.tpl 渲染成 App.java，javac + d8 产出 killer_app.dex。
只负责"载荷生产线"，不接触 APK 本身。
"""
import os
import shutil

from . import killer_config as KC


def _sh_quote(s):
    return "'" + str(s).replace("'", "'\\''") + "'"


def fqcn_to_dex_desc(fqcn: str) -> str:
    return "L" + fqcn.replace(".", "/") + ";"


def build_killer_app_dex(ctx, target_pkg: str, signature_b64: str, asset_path: str) -> str:
    """
    返回 killer_app.dex 的绝对路径。
    依赖 ctx.tools_dir（含 android.jar）与 ctx.work_dir。
    """
    killer_dir = os.path.join(ctx.root_dir, KC.KILLER_SRC_DIR)
    tpl_path   = os.path.join(killer_dir, "App.java.tpl")
    stub_dir   = os.path.join(killer_dir, "stub")

    if not os.path.isfile(tpl_path):
        ctx.log("ERR", "缺少模板: " + tpl_path)
        raise SystemExit(1)
    if not os.path.isdir(stub_dir):
        ctx.log("ERR", "缺少 stub 目录: " + stub_dir)
        raise SystemExit(1)

    android_jar = os.path.join(ctx.tools_dir, "android.jar")
    if not os.path.isfile(android_jar):
        ctx.log("ERR", "缺少 android.jar: " + android_jar)
        raise SystemExit(1)

    work = os.path.join(ctx.work_dir, "killer_build")
    shutil.rmtree(work, ignore_errors=True)
    src_root = os.path.join(work, "src")
    cls_dir  = os.path.join(work, "classes")
    os.makedirs(src_root, exist_ok=True)
    os.makedirs(cls_dir, exist_ok=True)

    pkg_parts = KC.KILLER_APP_CLASS.split(".")
    pkg_dir = os.path.join(src_root, *pkg_parts[:-1])
    os.makedirs(pkg_dir, exist_ok=True)
    java_path = os.path.join(pkg_dir, pkg_parts[-1] + ".java")

    with open(tpl_path, "r", encoding="utf-8") as f:
        code = f.read()
    code = code.replace("%%TARGET_PKG%%", target_pkg)
    code = code.replace("%%SIGNATURE_B64%%", signature_b64)
    code = code.replace("%%LIB_NAME%%", KC.LIB_NAME)
    code = code.replace("%%ASSET_PATH%%", asset_path)
    code = code.replace("%%FILES_DIR%%", KC.FILES_DIR)
    with open(java_path, "w", encoding="utf-8") as f:
        f.write(code)

    javac_cmd = (
        f"javac -source 1.8 -target 1.8 -nowarn "
        f"-bootclasspath {_sh_quote(android_jar)} "
        f"-classpath {_sh_quote(stub_dir)} "
        f"-sourcepath {_sh_quote(src_root)}:{_sh_quote(stub_dir)} "
        f"-implicit:none -d {_sh_quote(cls_dir)} "
        f"{_sh_quote(java_path)}"
    )
    ctx.run_cmd(javac_cmd)

    for p in ("sun", "dalvik"):
        d = os.path.join(cls_dir, p)
        if os.path.isdir(d):
            shutil.rmtree(d, ignore_errors=True)

    class_files = []
    for r, _, fs in os.walk(cls_dir):
        for fname in fs:
            if fname.endswith(".class"):
                class_files.append(os.path.join(r, fname))
    if not class_files:
        ctx.log("ERR", "javac 后没有 class 产物")
        raise SystemExit(1)

    dex_out = os.path.join(work, "dex_out")
    os.makedirs(dex_out, exist_ok=True)
    d8_cmd = (
        f"d8 --min-api 21 --lib {_sh_quote(android_jar)} "
        f"--output {_sh_quote(dex_out)} "
        + " ".join(_sh_quote(c) for c in class_files)
    )
    ctx.run_cmd(d8_cmd)

    dex_path = os.path.join(dex_out, "classes.dex")
    if not os.path.isfile(dex_path):
        ctx.log("ERR", "d8 未产出 classes.dex")
        raise SystemExit(1)
    ctx.log("OK", f"killer_app.dex 生成成功 ({os.path.getsize(dex_path)} bytes)")
    return dex_path
