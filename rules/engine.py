# -*- coding: utf-8 -*-
import os
import sys
import importlib.util

class RulePlugin:
    def __init__(self, plugin_id, name, resolver_func, file_path, enabled=True):
        self.plugin_id = plugin_id
        self.name = name
        self.resolver_func = resolver_func
        self.file_path = file_path
        self.enabled = enabled

    def resolve(self, dex_data_dict, meta):
        return self.resolver_func(dex_data_dict, meta)

def discover_rule_plugins(rules_dir):
    """自动扫描 rules/*.py 并注册为标准插件"""
    plugins = []
    ignored = {"__init__.py", "engine.py", "parser.py", "stubs.py"}
    
    for f in sorted(os.listdir(rules_dir)):
        if f.endswith(".py") and f not in ignored and not f.startswith("."):
            mod_name = f[:-3]
            file_path = os.path.join(rules_dir, f)
            
            try:
                spec = importlib.util.spec_from_file_location(f"rules.{mod_name}", file_path)
                mod = importlib.util.module_from_spec(spec)
                sys.modules[f"rules.{mod_name}"] = mod
                spec.loader.exec_module(mod)
                
                rule_id = getattr(mod, "RULE_ID", mod_name)
                rule_name = getattr(mod, "RULE_NAME", mod_name)
                resolver = getattr(mod, "resolve_rules", None)
                enabled = getattr(mod, "RULE_ENABLED", True)
                
                if resolver and callable(resolver):
                    plugins.append(RulePlugin(rule_id, rule_name, resolver, file_path, enabled))
            except Exception as e:
                print(f"[1;31m[ERROR][0m 加载规则插件失败 [{f}]: {e}")
                
    return plugins
