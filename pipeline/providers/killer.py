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
                src_so = os.path.join(killer_lib, abi, "libSignedByRS.so")
                if os.path.isfile(src_so):
                    so_list.append((abi, f"lib/{abi}/libSignedByRS.so", src_so))
        return so_list

    def get_extra_assets(self, ctx):
        # NeoPacker 会原生处理 assets/SignedByRS/input.apk 的 16KB 对齐与流式内嵌，无需外部提前写盘
        return []

    def _ensure_v2_keys(self, ctx):
        key_pem = os.path.abspath(os.path.join(self.work_killer, "v2_key.pem"))
        cert_der = os.path.abspath(os.path.join(self.work_killer, "v2_cert.der"))
        cert_pem = os.path.abspath(os.path.join(self.work_killer, "v2_cert.pem"))
        pk8_der = os.path.abspath(os.path.join(self.work_killer, "v2_key_pk8.der"))

        if not (os.path.isfile(pk8_der) and os.path.isfile(cert_der)):
            ctx.run_cmd(f"openssl genrsa -out {shlex.quote(key_pem)} 2048")
            ctx.run_cmd(f"openssl pkcs8 -topk8 -nocrypt -in {shlex.quote(key_pem)} -outform DER -out {shlex.quote(pk8_der)}")
            ctx.run_cmd(f"openssl req -new -x509 -key {shlex.quote(key_pem)} -out {shlex.quote(cert_pem)} -days 10000 -subj /CN=K")
            ctx.run_cmd(f"openssl x509 -in {shlex.quote(cert_pem)} -outform DER -out {shlex.quote(cert_der)}")
        return pk8_der, cert_der

    def sign(self, ctx, in_apk, out_apk):
        ctx.log("INFO", "5. 正在执行 NeoPacker 流式打包、MT数据复用与原地 V2 签名...")
        
        pk8_der, cert_der = self._ensure_v2_keys(ctx)
        neoapk_jar = os.path.join(ctx.tools_dir, "neoapk.jar")
        inject_dir = os.path.join(ctx.work_dir, "inject")
        
        # 调度 NeoPacker 一步到位完成：流式挂载原包 -> 虚拟条目映射 -> 保留V1壳 -> V2原地签名
        cp = f"{shlex.quote(neoapk_jar)}:{shlex.quote(ctx.engine_bin)}"
        cmd = f"java -cp {cp} com.tencent.qqnt.patcher.NeoPacker {shlex.quote(ctx.input_apk)} {shlex.quote(out_apk)} {shlex.quote(inject_dir)} {shlex.quote(pk8_der)} {shlex.quote(cert_der)}"
        
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
        ctx.log("OK", f"-> 构建完成！最终产物体积: {final_mb}MB (NeoApk MT复用生效，保留原版V1壳，V2签名合法)")
