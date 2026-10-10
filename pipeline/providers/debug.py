# -*- coding: utf-8 -*-
import os
import shlex
from .base import BaseProvider

class DebugSignerProvider(BaseProvider):
    name = "debug"

    def sign(self, ctx, in_apk, out_apk):
        ctx.log("INFO", "5. 正在执行 NeoPacker 纯净模式打包与签名...")
        if not os.path.exists(ctx.fixed_keystore):
            ctx.run_cmd(f"keytool -genkeypair -v -keystore {shlex.quote(ctx.fixed_keystore)} -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'")
        
        neoapk_jar = os.path.join(ctx.tools_dir, "neoapk.jar")
        inject_dir = os.path.join(ctx.work_dir, "inject")
        cp = f"{shlex.quote(neoapk_jar)}:{shlex.quote(ctx.engine_bin)}"
        
        # 传递 --no-killer 彻底关闭 input.apk 内嵌与 Host 挂载，直接纯净打包
        cmd = f"java -cp {cp} com.tencent.qqnt.patcher.NeoPacker {shlex.quote(ctx.input_apk)} {shlex.quote(out_apk)} {shlex.quote(inject_dir)} {shlex.quote(ctx.fixed_keystore)} --no-killer"
        ret = ctx.run_cmd_stream(cmd)
        if ret != 0 or not os.path.isfile(out_apk):
            ctx.log("ERR", "NeoPacker 纯净打包失败！")
            sys.exit(1)
