// IBlockedStatusProvider.aidl
package com.close.hook.ads;

import android.os.ParcelFileDescriptor;

interface IBlockedStatusProvider {
    ParcelFileDescriptor getData(String type, String value);
}
