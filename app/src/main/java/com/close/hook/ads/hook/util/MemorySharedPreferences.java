package com.close.hook.ads.hook.util;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MemorySharedPreferences implements SharedPreferences {
    private final Map<String, Object> data = new HashMap<>();
    private final SharedPreferences.Editor editor = new MemoryEditor();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(data);
    }

    @Override
    public String getString(String key, String defValue) {
        return data.containsKey(key) ? (String) data.get(key) : defValue;
    }

    @Override
    public int getInt(String key, int defValue) {
        return data.containsKey(key) ? (int) data.get(key) : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        return data.containsKey(key) ? (long) data.get(key) : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        return data.containsKey(key) ? (float) data.get(key) : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return data.containsKey(key) ? (boolean) data.get(key) : defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return data.containsKey(key) ? (Set<String>) data.get(key) : defValues;
    }

    @Override
    public boolean contains(String key) {
        return data.containsKey(key);
    }

    @Override
    public SharedPreferences.Editor edit() {
        return editor;
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    private class MemoryEditor implements SharedPreferences.Editor {
        private final Map<String, Object> tempData = new HashMap<>();
        private boolean clearAll = false;

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            tempData.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            tempData.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            tempData.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            tempData.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            tempData.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            tempData.put(key, values);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            tempData.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            clearAll = true;
            return this;
        }

        @Override
        public boolean commit() {
            if (clearAll) {
                data.clear();
                clearAll = false;
            }
            data.putAll(tempData);
            tempData.clear();
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
