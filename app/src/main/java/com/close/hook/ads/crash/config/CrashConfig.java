package com.close.hook.ads.crash.config;

import android.app.Activity;

import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.close.hook.ads.crash.activity.CrashActivity;

import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Modifier;

public class CrashConfig implements Serializable {

    @IntDef({BACKGROUND_MODE_CRASH, BACKGROUND_MODE_SHOW_CUSTOM, BACKGROUND_MODE_SILENT})
    @Retention(RetentionPolicy.SOURCE)
    private @interface BackgroundMode {
    }

    static final int BACKGROUND_MODE_SILENT = 0;
    public static final int BACKGROUND_MODE_SHOW_CUSTOM = 1;
    public static final int BACKGROUND_MODE_CRASH = 2;
    private int backgroundMode = BACKGROUND_MODE_SHOW_CUSTOM;
    private boolean enabled = true;
    private boolean showErrorDetails = true;
    private boolean showRestartButton = true;
    private boolean logErrorOnRestart = true;
    private boolean trackActivities = false;
    private int minTimeBetweenCrashesMs = 3000;
    private Integer errorDrawable = null;
    private Class<? extends Activity> errorActivityClass = null;
    private Class<? extends Activity> restartActivityClass = null;
    private CrashActivity.EventListener eventListener = null;

    @BackgroundMode
    public int getBackgroundMode() {
        return backgroundMode;
    }

    public void setBackgroundMode(@BackgroundMode int backgroundMode) {
        this.backgroundMode = backgroundMode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowErrorDetails() {
        return showErrorDetails;
    }

    public void setShowErrorDetails(boolean showErrorDetails) {
        this.showErrorDetails = showErrorDetails;
    }

    public boolean isShowRestartButton() {
        return showRestartButton;
    }

    public void setShowRestartButton(boolean showRestartButton) {
        this.showRestartButton = showRestartButton;
    }

    public boolean isLogErrorOnRestart() {
        return logErrorOnRestart;
    }

    public void setLogErrorOnRestart(boolean logErrorOnRestart) {
        this.logErrorOnRestart = logErrorOnRestart;
    }

    public boolean isTrackActivities() {
        return trackActivities;
    }

    public void setTrackActivities(boolean trackActivities) {
        this.trackActivities = trackActivities;
    }

    public int getMinTimeBetweenCrashesMs() {
        return minTimeBetweenCrashesMs;
    }

    public void setMinTimeBetweenCrashesMs(int minTimeBetweenCrashesMs) {
        this.minTimeBetweenCrashesMs = minTimeBetweenCrashesMs;
    }

    @Nullable
    @DrawableRes
    public Integer getErrorDrawable() {
        return errorDrawable;
    }

    public void setErrorDrawable(@Nullable @DrawableRes Integer errorDrawable) {
        this.errorDrawable = errorDrawable;
    }

    @Nullable
    public Class<? extends Activity> getErrorActivityClass() {
        return errorActivityClass;
    }

    public void setErrorActivityClass(@Nullable Class<? extends Activity> errorActivityClass) {
        this.errorActivityClass = errorActivityClass;
    }

    @Nullable
    public Class<? extends Activity> getRestartActivityClass() {
        return restartActivityClass;
    }

    public void setRestartActivityClass(@Nullable Class<? extends Activity> restartActivityClass) {
        this.restartActivityClass = restartActivityClass;
    }

    @Nullable
    public CrashActivity.EventListener getEventListener() {
        return eventListener;
    }

    public void setEventListener(@Nullable CrashActivity.EventListener eventListener) {
        this.eventListener = eventListener;
    }

    public static class Builder {
        private CrashConfig config;

        @NonNull
        public static Builder create() {
            Builder builder = new Builder();
            CrashConfig currentConfig = CrashActivity.getConfig();
            CrashConfig config = new CrashConfig();
            config.backgroundMode = currentConfig.backgroundMode;
            config.enabled = currentConfig.enabled;
            config.showErrorDetails = currentConfig.showErrorDetails;
            config.showRestartButton = currentConfig.showRestartButton;
            config.logErrorOnRestart = currentConfig.logErrorOnRestart;
            config.trackActivities = currentConfig.trackActivities;
            config.minTimeBetweenCrashesMs = currentConfig.minTimeBetweenCrashesMs;
            config.errorDrawable = currentConfig.errorDrawable;
            config.errorActivityClass = currentConfig.errorActivityClass;
            config.restartActivityClass = currentConfig.restartActivityClass;
            config.eventListener = currentConfig.eventListener;
            builder.config = config;
            return builder;
        }

        @NonNull
        public Builder backgroundMode(@BackgroundMode int backgroundMode) {
            config.backgroundMode = backgroundMode;
            return this;
        }

        @NonNull
        public Builder enabled(boolean enabled) {
            config.enabled = enabled;
            return this;
        }

        @NonNull
        public Builder showErrorDetails(boolean showErrorDetails) {
            config.showErrorDetails = showErrorDetails;
            return this;
        }

        @NonNull
        public Builder showRestartButton(boolean showRestartButton) {
            config.showRestartButton = showRestartButton;
            return this;
        }

        @NonNull
        public Builder logErrorOnRestart(boolean logErrorOnRestart) {
            config.logErrorOnRestart = logErrorOnRestart;
            return this;
        }

        @NonNull
        public Builder trackActivities(boolean trackActivities) {
            config.trackActivities = trackActivities;
            return this;
        }

        @NonNull
        public Builder minTimeBetweenCrashesMs(int minTimeBetweenCrashesMs) {
            config.minTimeBetweenCrashesMs = minTimeBetweenCrashesMs;
            return this;
        }

        @NonNull
        public Builder errorDrawable(@Nullable @DrawableRes Integer errorDrawable) {
            config.errorDrawable = errorDrawable;
            return this;
        }

        @NonNull
        public Builder errorActivity(@Nullable Class<? extends Activity> errorActivityClass) {
            config.errorActivityClass = errorActivityClass;
            return this;
        }

        @NonNull
        public Builder restartActivity(@Nullable Class<? extends Activity> restartActivityClass) {
            config.restartActivityClass = restartActivityClass;
            return this;
        }

        @NonNull
        public Builder eventListener(@Nullable CrashActivity.EventListener eventListener) {
            if (eventListener != null && eventListener.getClass().getEnclosingClass() != null
                    && !Modifier.isStatic(eventListener.getClass().getModifiers())) {
                throw new IllegalArgumentException(
                        "The event listener cannot be an inner or anonymous class, because it will need to be serialized. Change it to a class of its own, or make it a static inner class.");
            } else {
                config.eventListener = eventListener;
            }
            return this;
        }

        @NonNull
        public CrashConfig get() {
            return config;
        }

        public void apply() {
            CrashActivity.setConfig(config);
        }
    }
}
