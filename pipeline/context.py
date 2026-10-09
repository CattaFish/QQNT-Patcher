# -*- coding: utf-8 -*-
import os
import shutil
import subprocess
from .features import resolve_active_features, ALL_FEATURES

class PipelineContext:
    def __init__(self, input_apk, output_apk, no_sign=False, skip_dex_patch=False, no_killer=False, skipped_keywords=None, only_keywords=None):
        self.root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        self.tools_dir = os.path.join(self.root_dir, "tools")
        self.work_dir = os.path.join(self.root_dir, "build_cache")
        self.assets_src_dir = os.path.join(self.root_dir, "assets")
        self.preset_plugins_dir = os.path.join(self.root_dir, "preset_plugins")
        
        self.input_apk = os.path.abspath(input_apk)
        self.output_apk = os.path.abspath(output_apk)
        
        self.no_sign = no_sign
        self.skip_dex_patch = skip_dex_patch
        self.no_killer = no_killer
        self.skipped_keywords = skipped_keywords or []
        self.only_keywords = only_keywords or []
        
        # 特性决议
        self.active_features = resolve_active_features(self.only_keywords, self.skipped_keywords)
        if self.no_killer:
            self.active_features.discard("killer")
        elif "killer" not in self.active_features:
            self.no_killer = True

        print(f"\033[1;36m[*] [Feature 决策] 当前已激活 {len(self.active_features)} / {len(ALL_FEATURES)} 个特性:\033[0m")
        for fid, desc in ALL_FEATURES.items():
            status = "\033[32m[ON]\033[0m" if fid in self.active_features else "\033[31m[SKIP]\033[0m"
            print(f"    {status} {fid:<18} ({desc})")

        self.killer_dir = self._detect_killer_dir()
        self.work_killer = os.path.join(self.killer_dir, "work_killer") if self.killer_dir else None
        
        self.baksmali_jar = os.path.join(self.tools_dir, "baksmali.jar")
        self.smali_jar = os.path.join(self.tools_dir, "smali.jar")
        self.dexlib2_jar = os.path.join(self.tools_dir, "dexlib2.jar")
        self.guava_jar = os.path.join(self.tools_dir, "guava.jar")
        self.bsh_jar = os.path.join(self.tools_dir, "bsh.jar")
        self.dx_jar = os.path.join(self.tools_dir, "dx.jar")
        self.protobuf_jar = os.path.join(self.tools_dir, "protobuf.jar")
        self.android_jar = os.path.join(self.tools_dir, "android.jar")
        self.fixed_keystore = os.path.join(self.tools_dir, "debug.keystore")
        
        self.orig_apk_md5 = ""
        self.orig_sig_md5 = ""
        self.dex_data_dict = {}
        self.dex_classes_map = {}
        self.max_dex_idx = 0
        self.all_rules = []
        self.dex_to_rules = {}
        self.modified_dex_files = []
        
        self.libs_dex_path = None
        self.patch_dex_path = None
        self.bsh_standalone_dex = None
        self.preset_plugins_zip = None
        self.engine_bin = None
        
        from .providers import get_provider
        p_name = "none" if self.no_sign else ("debug" if self.no_killer else "killer")
        self.provider = get_provider(self, p_name)

    def _detect_killer_dir(self):
        candidates = [
            os.path.abspath(os.path.join(self.root_dir, "../ApkSignatureKillerEx/signature-killer")),
            os.path.abspath(os.path.join(self.root_dir, "../ApkSignatureKillerEx")),
            os.path.abspath(os.path.join(self.root_dir, "signature-killer")),
        ]
        for c in candidates:
            target = os.path.join(c, "signature-killer") if os.path.isdir(os.path.join(c, "signature-killer")) else c
            if os.path.isdir(target) and (
                os.path.isdir(os.path.join(target, "killer")) or
                os.path.isdir(os.path.join(target, "work_killer")) or
                os.path.isfile(os.path.join(target, "settings.gradle"))
            ):
                return target
        return None

    def log(self, tag, msg):
        colors = {
            "INFO": "\033[1;34m[INFO]\033[0m",
            "OK": "\033[1;32m[SUCCESS]\033[0m",
            "WARN": "\033[1;33m[WARN]\033[0m",
            "ERR": "\033[1;31m[ERROR]\033[0m",
            "TIME": "\033[1;35m[TIME]\033[0m"
        }
        print(f"{colors.get(tag, '[*]')} {msg}")

    def run_cmd(self, cmd, cwd=None):
        ret = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=cwd)
        out_msg = ret.stdout.decode('utf-8', errors='ignore').strip()
        err_msg = ret.stderr.decode('utf-8', errors='ignore').strip()
        if err_msg:
            for line in err_msg.split("\n"):
                if "[WARN]" in line: self.log("WARN", line)
                elif "[ERROR]" in line or "Exception" in line: self.log("ERR", line)
        if ret.returncode != 0:
            self.log("ERR", f"命令执行失败 (Exit code {ret.returncode}): {cmd}")
            if err_msg: self.log("ERR", err_msg)
            if out_msg: self.log("WARN", out_msg)
            return ""
        return out_msg

    def run_cmd_stream(self, cmd, cwd=None):
        p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=cwd, universal_newlines=True, bufsize=1)
        for line in p.stdout:
            l = line.strip()
            if not l: continue
            if "[WARN]" in l: self.log("WARN", l)
            elif "[ERROR]" in l: self.log("ERR", l)
            elif "[DexPatcher]" in l: print(f"\033[1;32m[*] {l}\033[0m")
            else: print(f"    {l}")
        p.wait()
        return p.returncode

    def find_tool(self, name):
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