# -*- coding: utf-8 -*-
import os
import sys
import shlex
import subprocess
from .base import BaseProvider
from .. import killer_config as KC

class KillerProvider(BaseProvider):
    name = "killer"

    def get_extra_dexes(self, ctx):
        if getattr(ctx, "killer_app_dex", None) and os.path.isfile(ctx.killer_app_dex):
            dex_name = f"classes{ctx.max_dex_idx + 3}.dex"
            return [(dex_name, ctx.killer_app_dex)]
        ctx.log("ERR", "killer_app.dex 未生成，无法注入")
        return []

    def get_extra_sos(self, ctx, target_abis):
        so_list = []
        lib_root = os.path.join(ctx.root_dir, KC.KILLER_LIB_DIR)
        for abi in target_abis:
            src = os.path.join(lib_root, abi, KC.SO_FILE_NAME)
            if os.path.isfile(src):
                so_list.append((abi, f"lib/{abi}/{KC.SO_FILE_NAME}", src))
        if not so_list:
            ctx.log("ERR", f"未找到 {KC.SO_FILE_NAME}，请先编译 killer_native 到 {KC.KILLER_LIB_DIR}/<abi>/")
            raise SystemExit(1)
        return so_list

    def _ensure_keystore(self, ctx):
        if not os.path.exists(ctx.fixed_keystore):
            ctx.log("INFO", "正在初始化签名证书 (keytool)...")
            ctx.run_cmd(
                f"keytool -genkeypair -v -keystore {shlex.quote(ctx.fixed_keystore)} "
                f"-alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 "
                f"-storepass android -keypass android "
                f"-dname 'CN=Android Debug,O=Android,C=US'"
            )
        return ctx.fixed_keystore

    def sign(self, ctx, in_apk, out_apk):
        ctx.log("INFO", "5. 正在执行 NeoPacker 流式打包、MT数据复用与原地 V2 签名...")

        if not ctx.host_entry_path:
            ctx.log("ERR", "host_entry_path 为空，killer 模式异常")
            sys.exit(1)

        keystore_path = self._ensure_keystore(ctx)
        neoapk_jar = os.path.join(ctx.tools_dir, "neoapk.jar")
        inject_dir = os.path.join(ctx.work_dir, "inject")

        cp = f"{shlex.quote(neoapk_jar)}:{shlex.quote(ctx.engine_bin)}"
        cmd = (
            f"java -cp {cp} com.tencent.qqnt.patcher.NeoPacker "
            f"{shlex.quote(ctx.input_apk)} {shlex.quote(out_apk)} {shlex.quote(inject_dir)} "
            f"{shlex.quote(keystore_path)} "
            f"--host-path={shlex.quote(ctx.host_entry_path)}"
        )

        ret = ctx.run_cmd_stream(cmd)
        if ret != 0 or not os.path.isfile(out_apk):
            ctx.log("ERR", "NeoPacker 执行失败！")
            sys.exit(1)

        apksigner_bin = ctx.find_tool("apksigner")
        if os.path.isfile(apksigner_bin):
            ret_verify = subprocess.run(
                [apksigner_bin, "verify", "--min-sdk-version", "24", out_apk],
                capture_output=True, text=True
            )
            if ret_verify.returncode == 0:
                ctx.log("OK", "-> 官方 apksigner (min-sdk=24) V2 验签通过！")
            else:
                ctx.log("WARN", "apksigner 校验提示: " + ret_verify.stderr.strip())

        final_mb = os.path.getsize(out_apk) // (1024 * 1024)
        ctx.log("OK", f"-> 构建完成！最终产物体积: {final_mb}MB")
