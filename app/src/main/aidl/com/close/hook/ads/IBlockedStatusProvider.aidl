package com.close.hook.ads;

import com.close.hook.ads.BlockedBean;

interface IBlockedStatusProvider {
    BlockedBean getData(String type, String value);
}
