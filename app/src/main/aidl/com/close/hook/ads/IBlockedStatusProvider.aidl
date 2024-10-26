package com.close.hook.ads;

import com.close.hook.ads.IBlockedStatusCallback;

interface IBlockedStatusProvider {
    void getDataAsync(String type, String value, IBlockedStatusCallback callback);
}
