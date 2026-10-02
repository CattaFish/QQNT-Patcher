# -*- coding: utf-8 -*-
from abc import ABC, abstractmethod

class BaseProvider(ABC):
    name = "base"

    def get_extra_dexes(self, ctx):
        """返回待注入的 Dex 列表: list[(rel_name, abs_path)]"""
        return []

    def get_extra_sos(self, ctx, target_abis):
        """返回待注入的 Native 库列表: list[(abi, rel_path, abs_path)]"""
        return []

    def get_extra_assets(self, ctx):
        """返回待注入的 Asset 列表: list[(zip_entry_name, abs_path, is_store_mode)]"""
        return []

    @abstractmethod
    def sign(self, ctx, in_apk, out_apk):
        """执行签名流水线"""
        pass
