#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本地 Killer 协同构建器:
1. 默认模式: 直接解压 tools/killer-release.aar (免编译)
2. --local-java: 本地直接用 javac+d8 编译 Killer 的 Java 源码 (改 Java 免推 GitHub，自动补全 hiddenapibypass 依赖)
3. --ci: 改了 C 代码时，自动推送到 GitHub Actions 编译并静默下载 AAR 到 tools/
"""

import os
import sys
import shutil
import subprocess
import zipfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS_DIR = os.path.join(HERE, "tools")
KILLER_DIR = os.path.abspath(os.path.join(HERE, "../ApkSignatureKillerEx/signature-killer"))
WORK_KILLER = os.path.join(KILLER_DIR, "work_killer")

def run(cmd, cwd=None):
    print(f"+ {' '.join(cmd)}")
    ret = subprocess.run(cmd, cwd=cwd)
    if ret.returncode != 0:
        print(f"[FAIL] 命令执行失败: {cmd[0]}")
        sys.exit(1)

def find_tool(name):
    p = shutil.which(name)
    if p: return p
    h = os.environ.get("ANDROID_HOME", "")
    if h:
        bt = os.path.join(h, "build-tools")
        if os.path.isdir(bt):
            for v in sorted(os.listdir(bt), reverse=True):
                tp = os.path.join(bt, v, name)
                if os.path.isfile(tp): return tp
    return name

def ensure_hiddenapibypass_jar():
    """确保 tools/hiddenapibypass.jar 存在 (供 javac 编译与 d8 合并入 dex)"""
    target_jar = os.path.join(TOOLS_DIR, "hiddenapibypass.jar")
    if os.path.isfile(target_jar) and os.path.getsize(target_jar) > 1024:
        return target_jar

    print(">>> 正在检索/下载 hiddenapibypass 依赖库...")
    # 1. 尝试从本地 gradle 缓存查找
    gradle_cache = os.path.expanduser("~/.gradle/caches")
    if os.path.isdir(gradle_cache):
        for r, _, fs in os.walk(gradle_cache):
            if "hiddenapibypass" in r:
                for f in fs:
                    if f.endswith(".jar") and not f.endswith("-sources.jar"):
                        shutil.copy2(os.path.join(r, f), target_jar)
                        print(f"[ok] 从本地 Gradle 缓存提取依赖: {target_jar}")
                        return target_jar

    # 2. 从 Maven Central 下载 6.1 AAR 并解包 classes.jar
    aar_tmp = os.path.join(TOOLS_DIR, "hiddenapibypass-6.1.aar")
    url = "https://repo.maven.apache.org/maven2/org/lsposed/hiddenapibypass/hiddenapibypass/6.1/hiddenapibypass-6.1.aar"
    try:
        print(f"正在从 Maven 仓库拉取: {url}")
        urllib.request.urlretrieve(url, aar_tmp)
        with zipfile.ZipFile(aar_tmp, 'r') as zf:
            with open(target_jar, "wb") as out_f:
                out_f.write(zf.read("classes.jar"))
        if os.path.isfile(aar_tmp):
            os.remove(aar_tmp)
        print(f"[ok] 依赖就绪: {target_jar}")
        return target_jar
    except Exception as e:
        print(f"[FAIL] 下载 hiddenapibypass 失败: {e}")
        sys.exit(1)

def build_java_locally():
    print(">>> 正在本地直接编译 Killer 的 Java 源码 (无需 NDK，无需推 GitHub)...")
    java_src_dir = os.path.join(KILLER_DIR, "killer/src/main/java")
    android_jar = os.path.join(TOOLS_DIR, "android.jar")
    hidden_jar = ensure_hiddenapibypass_jar()
    
    java_files = []
    for r, _, fs in os.walk(java_src_dir):
        for f in fs:
            if f.endswith(".java"):
                java_files.append(os.path.join(r, f))
                
    if not java_files:
        print("[FAIL] 未找到 Killer 的 Java 源码！")
        return False
        
    classes_out = os.path.join(WORK_KILLER, "java_classes")
    shutil.rmtree(classes_out, ignore_errors=True)
    os.makedirs(classes_out, exist_ok=True)
    
    # 编译 classpath 同时引入 android.jar 与 hiddenapibypass.jar
    cp_str = f"{android_jar}:{hidden_jar}"
    javac_cmd = ["javac", "-cp", cp_str, "-d", classes_out] + java_files
    run(javac_cmd)
    
    # 转译为 classes.dex，同时将 hiddenapibypass 一并打进 dex
    d8_bin = find_tool("d8")
    class_files = [os.path.join(r, f) for r, _, fs in os.walk(classes_out) for f in fs if f.endswith(".class")]
    d8_cmd = [d8_bin, "--min-api", "21", "--output", WORK_KILLER]
    if os.path.isfile(android_jar):
        d8_cmd.extend(["--lib", android_jar])
    d8_cmd.append(hidden_jar)
    d8_cmd.extend(class_files)
    run(d8_cmd)
    
    shutil.rmtree(classes_out, ignore_errors=True)
    print(">>> [SUCCESS] 本地 Java 代码与依赖库已秒级打包为 classes.dex！")
    return True

def trigger_ci_and_download():
    print(">>> 正在触发 GitHub Actions 远程编译 C/C++ 动态库...")
    if not shutil.which("gh"):
        print("[FAIL] 未安装 GitHub CLI (gh)，请先在 Termux 执行: pkg install gh")
        sys.exit(1)
        
    repo_root = os.path.abspath(os.path.join(KILLER_DIR, ".."))
    run(["git", "add", "."], cwd=repo_root)
    subprocess.run(["git", "commit", "-m", "chore: trigger remote NDK build"], cwd=repo_root)
    run(["git", "push"], cwd=repo_root)
    
    print(">>> 已推送到 GitHub，正在等待 Actions 编译 AAR 产物...")
    run(["gh", "run", "watch"], cwd=repo_root)
    
    print(">>> 编译完成，正在自动拉取 killer-aar 产物到 tools/...")
    run(["gh", "run", "download", "-n", "killer-aar", "-D", TOOLS_DIR], cwd=repo_root)
    
    downloaded = os.path.join(TOOLS_DIR, "killer-release.aar")
    if os.path.isfile(downloaded):
        print(f">>> [SUCCESS] 产物已自动就绪于: {downloaded}")
    else:
        for r, _, fs in os.walk(TOOLS_DIR):
            for f in fs:
                if f.endswith(".aar"):
                    shutil.move(os.path.join(r, f), downloaded)
                    break

def unpack_aar():
    aar_file = os.path.join(TOOLS_DIR, "killer-release.aar")
    if not os.path.isfile(aar_file):
        print(f"[FAIL] 未找到 {aar_file}，请先放置或执行 python3 sync_killer.py --ci")
        sys.exit(1)
        
    print(f">>> 正在从 {aar_file} 提取预编 SO 与 Dex...")
    with zipfile.ZipFile(aar_file, 'r') as zf:
        zf.extract("classes.jar", WORK_KILLER)
        for item in zf.namelist():
            if item.startswith("jni/"):
                zf.extract(item, WORK_KILLER)

    # 规范化 lib 目录
    work_lib = os.path.join(WORK_KILLER, "lib")
    os.makedirs(work_lib, exist_ok=True)
    jni_dir = os.path.join(WORK_KILLER, "jni")
    if os.path.exists(jni_dir):
        for abi in os.listdir(jni_dir):
            src_abi = os.path.join(jni_dir, abi)
            dst_abi = os.path.join(work_lib, abi)
            os.makedirs(dst_abi, exist_ok=True)
            for f in os.listdir(src_abi):
                shutil.copy2(os.path.join(src_abi, f), os.path.join(dst_abi, f))
            # 兼容性别名
            if f == "libSignedByRS.so":
                shutil.copy2(os.path.join(src_abi, f), os.path.join(dst_abi, "libzcraft.so"))
            elif f == "libzcraft.so":
                shutil.copy2(os.path.join(src_abi, f), os.path.join(dst_abi, "libSignedByRS.so"))
        shutil.rmtree(jni_dir, ignore_errors=True)

    # 转译 classes.jar 为 classes.dex
    d8_bin = find_tool("d8")
    android_jar = os.path.join(TOOLS_DIR, "android.jar")
    hidden_jar = ensure_hiddenapibypass_jar()
    d8_cmd = [d8_bin, "--min-api", "21", "--output", WORK_KILLER]
    if os.path.isfile(android_jar):
        d8_cmd.extend(["--lib", android_jar])
    d8_cmd.append(hidden_jar)
    d8_cmd.append(os.path.join(WORK_KILLER, "classes.jar"))
    run(d8_cmd)

def main():
    os.makedirs(WORK_KILLER, exist_ok=True)
    os.makedirs(TOOLS_DIR, exist_ok=True)
    
    if "--ci" in sys.argv:
        trigger_ci_and_download()
        unpack_aar()
    elif "--local-java" in sys.argv:
        build_java_locally()
    else:
        unpack_aar()
        
    print(">>> [ALL DONE] Killer 载荷就绪，可直接执行 patcher.py！")

if __name__ == "__main__":
    main()
