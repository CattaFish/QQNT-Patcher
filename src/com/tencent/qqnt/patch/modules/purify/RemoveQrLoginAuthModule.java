package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;

public class RemoveQrLoginAuthModule implements IPatchModule {

    @Override
    public String getId() {
        return "remove_qr_login_auth";
    }

    @Override
    public String getName() {
        return "去除相册扫码登录检验";
    }

    @Override
    public String getSubName() {
        return "解除相册选图或长按图片扫码登录的限制";
    }

    @Override
    public String getCategory() {
        return CATEGORY_PURIFY;
    }

    @Override
    public boolean defaultEnabled() {
        return false;
    }
}