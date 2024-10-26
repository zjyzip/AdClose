package com.close.hook.ads;

import com.close.hook.ads.BlockedBean;

interface IBlockedStatusCallback {
    void onResult(in BlockedBean result);
}
