package com.close.hook.ads.service;

interface IHookPrefsAidlInterface {
    boolean getBoolean(String key, boolean defaultValue);
    void setBoolean(String key, boolean value);

    int getInt(String key, int defaultValue);
    void setInt(String key, int value);

    long getLong(String key, long defaultValue);
    void setLong(String key, long value);

    float getFloat(String key, float defaultValue);
    void setFloat(String key, float value);

    String getString(String key, String defaultValue);
    void setString(String key, String value);

    boolean contains(String key);
    void remove(String key);
    void clear();

    String getCustomHookConfigsJson(String packageName);
    void setCustomHookConfigsJson(String packageName, String configsJson);

    Bundle getAll();
}
