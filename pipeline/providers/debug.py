# -*- coding: utf-8 -*-
import os
import shlex
from .base import BaseProvider

class DebugSignerProvider(BaseProvider):
    name = "debug"

    def sign(self, ctx, in_apk, out_apk):
        ctx.log("INFO", "5. 正在执行 Debug Keystore 标准签名 (纯净模式，无去签载荷)...")
        if not os.path.exists(ctx.fixed_keystore):
            ctx.run_cmd(f"keytool -genkey -v -keystore {shlex.quote(ctx.fixed_keystore)} -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'")
        ctx.run_cmd(f"apksigner sign --ks {shlex.quote(ctx.fixed_keystore)} --ks-pass pass:android --key-pass pass:android --out {shlex.quote(out_apk)} {shlex.quote(in_apk)}")
        ctx.log("OK", "-> Debug 签名完成")
