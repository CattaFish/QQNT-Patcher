# -*- coding: utf-8 -*-
import os
import sys
import shlex
import shutil
import subprocess
from .base import BaseProvider

class KillerProvider(BaseProvider):
    name = "killer"

    def __init__(self, killer_dir):
        self.killer_dir = killer_dir
        self.work_killer = os.path.join(killer_dir, "work_killer") if killer_dir else None

    def is_available(self):
        return bool(self.killer_dir and os.path.isdir(self.work_killer))

    def get_extra_dexes(self, ctx):
        if not self.is_available(): return []
        killer_dex = os.path.join(self.work_killer, "classes.dex")
        if os.path.isfile(killer_dex):
            dex_name = f"classes{ctx.max_dex_idx + 3}.dex"
            return [(dex_name, killer_dex)]
        return []

    def get_extra_sos(self, ctx, target_abis):
        if not self.is_available(): return []
        so_list = []
        killer_lib = os.path.join(self.work_killer, "lib")
        if os.path.isdir(killer_lib):
            for abi in target_abis:
                abi_dir = os.path.join(killer_lib, abi)
                if not os.path.isdir(abi_dir): continue
                
                # 自动容错：无论本地库名叫 libzcraft.so 还是 libSignedByRS.so，统一识别
                src_zcraft = os.path.join(abi_dir, "libzcraft.so")
                src_rs = os.path.join(abi_dir, "libSignedByRS.so")
                
                real_src = src_zcraft if os.path.isfile(src_zcraft) else (src_rs if os.path.isfile(src_rs) else None)
                if real_src:
                    so_list.append((abi, f"lib/{abi}/libzcraft.so", real_src))
        return so_list

    def get_extra_assets(self, ctx):
        # NeoPacker 会原生处理 assets/Zcraft/input.apk 的 16KB 对齐与流式内嵌，无需外部提前写盘
        return []

    

    def _ensure_keystore(self, ctx):
        # 保证 JDK 自带的 debug.keystore 存在 (所有 Java 环境必带 keytool，免装 openssl)
        if not os.path.exists(ctx.fixed_keystore):
            ctx.log("INFO", "正在初始化签名证书 (keytool)...")
            ctx.run_cmd(f"keytool -genkeypair -v -keystore {shlex.quote(ctx.fixed_keystore)} -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'")
        return ctx.fixed_keystore

    def sign(self, ctx, in_apk, out_apk):
        ctx.log("INFO", "5. 正在执行 NeoPacker 流式打包、MT数据复用与原地 V2 签名...")
        
        keystore_path = self._ensure_keystore(ctx)
        neoapk_jar = os.path.join(ctx.tools_dir, "neoapk.jar")
        inject_dir = os.path.join(ctx.work_dir, "inject")
        
        # 调度 NeoPacker: 直接传递标准 Keystore 完成合法 V2 签名
        cp = f"{shlex.quote(neoapk_jar)}:{shlex.quote(ctx.engine_bin)}"
        cmd = f"java -cp {cp} com.tencent.qqnt.patcher.NeoPacker {shlex.quote(ctx.input_apk)} {shlex.quote(out_apk)} {shlex.quote(inject_dir)} {shlex.quote(keystore_path)}"
        
        ret = ctx.run_cmd_stream(cmd)
        if ret != 0 or not os.path.isfile(out_apk):
            ctx.log("ERR", "NeoPacker 执行失败！")
            sys.exit(1)

        # 官方验签自检
        apksigner_bin = ctx.find_tool("apksigner")
        if os.path.isfile(apksigner_bin):
            ret_verify = subprocess.run([apksigner_bin, "verify", "--min-sdk-version", "24", out_apk], capture_output=True, text=True)
            if ret_verify.returncode == 0:
                ctx.log("OK", "-> 官方 apksigner (min-sdk=24) V2 验签通过！")
            else:
                ctx.log("WARN", "apksigner 校验提示: " + ret_verify.stderr.strip())

        final_mb = os.path.getsize(out_apk) // (1024 * 1024)
        ctx.log("OK", f"-> 构建完成！最终产物体积: {final_mb}MB")
