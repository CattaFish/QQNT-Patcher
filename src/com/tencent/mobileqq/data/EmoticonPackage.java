package com.tencent.mobileqq.data;

import java.io.Serializable;

public class EmoticonPackage implements Serializable {
    public String epId;
    public String name;
    public int type;
    public int status;
    public boolean valid;
    public boolean aio;
    public int latestVersion;
    public String ipJumpUrl;
    public String ipDetail;

    public EmoticonPackage() {}
}