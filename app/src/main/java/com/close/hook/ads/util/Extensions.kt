package com.close.hook.ads.util

import android.content.res.Resources

val Number.dp get() = (toFloat() * Resources.getSystem().displayMetrics.density).toInt()
