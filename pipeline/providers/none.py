# -*- coding: utf-8 -*-
import shutil
from .base import BaseProvider

class NoneSignerProvider(BaseProvider):
    name = "none"

    def sign(self, ctx, in_apk, out_apk):
        ctx.log("INFO", "5. 跳过 APK 签名阶段 (--signer=none / --no-sign)")
        if in_apk != out_apk:
            shutil.copyfile(in_apk, out_apk)
