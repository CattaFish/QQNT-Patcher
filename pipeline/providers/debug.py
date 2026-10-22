# -*- coding: utf-8 -*-
import os
import sys
import shlex
from .base import BaseProvider
from ..context import Colors

class DebugSignerProvider(BaseProvider):
    name = "debug"

    def sign(self, ctx, in_apk, out_apk):
        ctx.log("INFO", f"启动 NeoPacker 纯净模式装配 ({Colors.CYAN}合并条目 / Debug签名 / 无Killer壳{Colors.RESET})")
        if not os.path.exists(ctx.fixed_keystore):
            ctx.run_cmd(
                f"keytool -genkeypair -v -keystore {shlex.quote(ctx.fixed_keystore)} "
                f"-alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 "
                f"-storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'"
            )
        
        neoapk_jar = os.path.join(ctx.tools_dir, "neoapk.jar")
        inject_dir = os.path.join(ctx.work_dir, "inject")
        cp = f"{shlex.quote(neoapk_jar)}:{shlex.quote(ctx.engine_bin)}"
        
        cmd = f"java -cp {cp} com.tencent.qqnt.patcher.NeoPacker {shlex.quote(ctx.input_apk)} {shlex.quote(out_apk)} {shlex.quote(inject_dir)} {shlex.quote(ctx.fixed_keystore)} --no-killer"
        ret = ctx.run_cmd_stream(cmd)
        if ret != 0 or not os.path.isfile(out_apk):
            ctx.log("ERR", "NeoPacker 纯净装配失败！")
            sys.exit(1)
        ctx.log("OK", "纯净打包签名完成")