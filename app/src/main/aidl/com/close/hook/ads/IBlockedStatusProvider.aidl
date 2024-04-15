// IBlockedStatusProvider.aidl
package com.close.hook.ads;

import android.os.ParcelFileDescriptor;
import com.close.hook.ads.BlockedBean;

interface IBlockedStatusProvider {
    BlockedBean getData(String type, String value);
    ParcelFileDescriptor getMemoryFileDescriptor();
}
