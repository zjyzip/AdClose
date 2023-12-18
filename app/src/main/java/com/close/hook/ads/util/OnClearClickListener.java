package com.close.hook.ads.util;

import com.close.hook.ads.data.model.FilterBean;

public interface OnClearClickListener {

    void onClearAll();

    void search(String keyWord);

    void updateSortList(FilterBean filterBean, String keyWord, Boolean isReverse);

}
