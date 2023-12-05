package com.close.hook.ads.crash.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import android.util.Log;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import com.close.hook.ads.crash.config.CrashConfig;

public final class CrashActivity {

	private final static String TAG = "CrashActivity";
	//Extras passed to the error activity
	private static final String EXTRA_CONFIG = "com.close.hook.ads.EXTRA_CONFIG";
	private static final String EXTRA_STACK_TRACE = "com.close.hook.ads.EXTRA_STACK_TRACE";
	private static final String EXTRA_ACTIVITY_LOG = "com.close.hook.ads.EXTRA_ACTIVITY_LOG";
	//General constants
	private static final String INTENT_ACTION_ERROR_ACTIVITY = "com.close.hook.ads.ERROR";
	private static final String INTENT_ACTION_RESTART_ACTIVITY = "com.close.hook.ads.RESTART";
	private static final String CRASH_HANDLER_PACKAGE_NAME = "com.close.hook.ads.";
	private static final String DEFAULT_HANDLER_PACKAGE_NAME = "com.android.internal.os";
	private static final int MAX_STACK_TRACE_SIZE = 131071; //128 KB - 1
	private static final int MAX_ACTIVITIES_IN_LOG = 50;
	//Shared preferences
	private static final String SHARED_PREFERENCES_FILE = "com.close.hook.ads_crash.preferences";
	private static final String SHARED_PREFERENCES_FIELD_TIMESTAMP = "last_crash_timestamp";
	//Internal variables
	private static Application application;
	private static CrashConfig config = new CrashConfig();
	private static final Deque<String> activityLog = new ArrayDeque<>(MAX_ACTIVITIES_IN_LOG);
	private static WeakReference<Activity> lastActivityCreated = new WeakReference<>(null);
	private static boolean isInBackground = true;

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static void install(@Nullable final Context context) {
		try {
			if (context == null) {
				Log.e(TAG, "Install failed: context is null!");
			} else {
				//INSTALL!
				final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
				if (oldHandler != null && oldHandler.getClass().getName().startsWith(CRASH_HANDLER_PACKAGE_NAME)) {
					Log.e(TAG, "CrashActivity was already installed, doing nothing!");
				} else {
					if (oldHandler != null
							&& !oldHandler.getClass().getName().startsWith(DEFAULT_HANDLER_PACKAGE_NAME)) {
						Log.e(TAG,
								"IMPORTANT WARNING! You already have an UncaughtExceptionHandler, are you sure this is correct? If you use a custom UncaughtExceptionHandler, you must initialize it AFTER CrashActivity! Installing anyway, but your original handler will not be called.");
					}
					application = (Application) context.getApplicationContext();
					Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
						if (config.isEnabled()) {
							Log.e(TAG, "App has crashed, executing CrashActivity's UncaughtExceptionHandler",
									throwable);
							if (hasCrashedInTheLastSeconds(application)) {
								Log.e(TAG,
										"App already crashed recently, not starting custom error activity because we could enter a restart loop. Are you sure that your app does not crash directly on init?",
										throwable);
								if (oldHandler != null) {
									oldHandler.uncaughtException(thread, throwable);
									return;
								}
							} else {
								setLastCrashTimestamp(application, new Date().getTime());
								Class<? extends Activity> errorActivityClass = config.getErrorActivityClass();
								if (errorActivityClass == null) {
									errorActivityClass = guessErrorActivityClass(application);
								}
								if (isStackTraceLikelyConflictive(throwable, errorActivityClass)) {
									Log.e(TAG,
											"Your application class or your error activity have crashed, the custom activity will not be launched!");
									if (oldHandler != null) {
										oldHandler.uncaughtException(thread, throwable);
										return;
									}
								} else if (config.getBackgroundMode() == CrashConfig.BACKGROUND_MODE_SHOW_CUSTOM
										|| !isInBackground) {
									final Intent intent = new Intent(application, errorActivityClass);
									StringWriter sw = new StringWriter();
									PrintWriter pw = new PrintWriter(sw);
									throwable.printStackTrace(pw);
									String stackTraceString = sw.toString();
									if (stackTraceString.length() > MAX_STACK_TRACE_SIZE) {
										String disclaimer = " [stack trace too large]";
										stackTraceString = stackTraceString.substring(0,
												MAX_STACK_TRACE_SIZE - disclaimer.length()) + disclaimer;
									}
									intent.putExtra(EXTRA_STACK_TRACE, stackTraceString);
									if (config.isTrackActivities()) {
										StringBuilder activityLogStringBuilder = new StringBuilder();
										while (!activityLog.isEmpty()) {
											activityLogStringBuilder.append(activityLog.poll());
										}
										intent.putExtra(EXTRA_ACTIVITY_LOG, activityLogStringBuilder.toString());
									}
									if (config.isShowRestartButton() && config.getRestartActivityClass() == null) {
										config.setRestartActivityClass(guessRestartActivityClass(application));
									}
									intent.putExtra(EXTRA_CONFIG, config);
									intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
									if (config.getEventListener() != null) {
										config.getEventListener().onLaunchErrorActivity();
									}
									application.startActivity(intent);
								} else if (config.getBackgroundMode() == CrashConfig.BACKGROUND_MODE_CRASH) {
									if (oldHandler != null) {
										oldHandler.uncaughtException(thread, throwable);
										return;
									}
								}
							}
							final Activity lastActivity = lastActivityCreated.get();
							if (lastActivity != null) {
								lastActivity.finish();
								lastActivityCreated.clear();
							}
							killCurrentProcess();
						} else if (oldHandler != null) {
							oldHandler.uncaughtException(thread, throwable);
						}
					});
					application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
						int currentlyStartedActivities = 0;
						final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

						@Override
						public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
							if (activity.getClass() != config.getErrorActivityClass()) {
								lastActivityCreated = new WeakReference<>(activity);
							}
							if (config.isTrackActivities()) {
								activityLog.add(dateFormat.format(new Date()) + ": "
										+ activity.getClass().getSimpleName() + " created\n");
							}
						}

						@Override
						public void onActivityStarted(Activity activity) {
							currentlyStartedActivities++;
							isInBackground = (currentlyStartedActivities == 0);
							//Do nothing
						}

						@Override
						public void onActivityResumed(Activity activity) {
							if (config.isTrackActivities()) {
								activityLog.add(dateFormat.format(new Date()) + ": "
										+ activity.getClass().getSimpleName() + " resumed\n");
							}
						}

						@Override
						public void onActivityPaused(Activity activity) {
							if (config.isTrackActivities()) {
								activityLog.add(dateFormat.format(new Date()) + ": "
										+ activity.getClass().getSimpleName() + " paused\n");
							}
						}

						@Override
						public void onActivityStopped(Activity activity) {
							//Do nothing
							currentlyStartedActivities--;
							isInBackground = (currentlyStartedActivities == 0);
						}

						@Override
						public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
							//Do nothing
						}

						@Override
						public void onActivityDestroyed(Activity activity) {
							if (config.isTrackActivities()) {
								activityLog.add(dateFormat.format(new Date()) + ": "
										+ activity.getClass().getSimpleName() + " destroyed\n");
							}
						}
					});
				}
				Log.i(TAG, "CrashActivity has been installed.");
			}
		} catch (Throwable t) {
			Log.e(TAG,
					"An unknown error occurred while installing CrashActivity, it may not have been properly initialized. Please report this as a bug if needed.",
					t);
		}
	}

	@Nullable
	public static String getStackTraceFromIntent(@NonNull Intent intent) {
		return intent.getStringExtra(CrashActivity.EXTRA_STACK_TRACE);
	}

	public static CrashConfig getConfigFromIntent(@NonNull Intent intent) {
		CrashConfig config = (CrashConfig) intent.getSerializableExtra(CrashActivity.EXTRA_CONFIG);
		if (Objects.requireNonNull(config).isLogErrorOnRestart()) {
			String stackTrace = getStackTraceFromIntent(intent);
			if (stackTrace != null) {
				Log.e(TAG, "The previous app process crashed. This is the stack trace of the crash:\n"
						+ getStackTraceFromIntent(intent));
			}
		}
		return config;
	}

	@Nullable
	private static String getActivityLogFromIntent(@NonNull Intent intent) {
		return intent.getStringExtra(CrashActivity.EXTRA_ACTIVITY_LOG);
	}

	@NonNull
	public static String getAllErrorDetailsFromIntent(@NonNull Context context, @NonNull Intent intent) {
		Date currentDate = new Date();
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
		String buildDateAsString = getBuildDateAsString(context, dateFormat);
		String versionName = getVersionName(context);
		String errorDetails = "";
		errorDetails += "Build version: " + versionName + " \n";
		if (buildDateAsString != null) {
			errorDetails += "Build date: " + buildDateAsString + " \n";
		}
		errorDetails += "Current date: " + dateFormat.format(currentDate) + " \n";
		errorDetails += "Device: " + getDeviceModelName() + " \n \n";
		errorDetails += "Stack trace:  \n";
		errorDetails += getStackTraceFromIntent(intent);
		String activityLog = getActivityLogFromIntent(intent);
		if (activityLog != null) {
			errorDetails += "\nUser actions: \n";
			errorDetails += activityLog;
		}
		return errorDetails;
	}

	private static void restartApplicationWithIntent(@NonNull Activity activity, @NonNull Intent intent,
			@NonNull CrashConfig config) {
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
				| Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		if (intent.getComponent() != null) {
			//If the class name has been set, we force it to simulate a Launcher launch.
			//If we don't do this, if you restart from the error activity, then press home,
			//and then launch the activity from the launcher, the main activity appears twice on the backstack.
			//This will most likely not have any detrimental effect because if you set the Intent component,
			//if will always be launched regardless of the actions specified here.
			intent.setAction(Intent.ACTION_MAIN);
			intent.addCategory(Intent.CATEGORY_LAUNCHER);
		}
		if (config.getEventListener() != null) {
			config.getEventListener().onRestartAppFromErrorActivity();
		}
		activity.finish();
		activity.startActivity(intent);
		killCurrentProcess();
	}

	public static void restartApplication(@NonNull Activity activity, @NonNull CrashConfig config) {
		Intent intent = new Intent(activity, config.getRestartActivityClass());
		restartApplicationWithIntent(activity, intent, config);
	}

	public static void closeApplication(@NonNull Activity activity, @NonNull CrashConfig config) {
		if (config.getEventListener() != null) {
			config.getEventListener().onCloseAppFromErrorActivity();
		}
		activity.finish();
		killCurrentProcess();
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	@NonNull
	public static CrashConfig getConfig() {
		return config;
	}

	@RestrictTo(RestrictTo.Scope.LIBRARY)
	public static void setConfig(@NonNull CrashConfig config) {
		CrashActivity.config = config;
	}

	private static boolean isStackTraceLikelyConflictive(@NonNull Throwable throwable,
			@NonNull Class<? extends Activity> activityClass) {
		do {
			StackTraceElement[] stackTrace = throwable.getStackTrace();
			for (StackTraceElement element : stackTrace) {
				if ((element.getClassName().equals("android.app.ActivityThread")
						&& element.getMethodName().equals("handleBindApplication"))
						|| element.getClassName().equals(activityClass.getName())) {
					return true;
				}
			}
		} while ((throwable = throwable.getCause()) != null);
		return false;
	}

	@Nullable
	private static String getBuildDateAsString(@NonNull Context context, @NonNull DateFormat dateFormat) {
		long buildDate;
		try {
			ApplicationInfo ai = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
			ZipFile zf = new ZipFile(ai.sourceDir);

			//If this failed, try with the old zip method
			ZipEntry ze = zf.getEntry("classes.dex");
			buildDate = ze.getTime();

			zf.close();
		} catch (Exception e) {
			buildDate = 0;
		}
		if (buildDate > 312764400000L) {
			return dateFormat.format(new Date(buildDate));
		} else {
			return null;
		}
	}

	@NonNull
	private static String getVersionName(Context context) {
		try {
			PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return packageInfo.versionName;
		} catch (Exception e) {
			return "Unknown";
		}
	}

	@NonNull
	private static String getDeviceModelName() {
		String manufacturer = Build.MANUFACTURER;
		String model = Build.MODEL;
		if (model.startsWith(manufacturer)) {
			return capitalize(model);
		} else {
			return capitalize(manufacturer) + " " + model;
		}
	}

	@NonNull
	private static String capitalize(@Nullable String s) {
		if (s == null || s.length() == 0) {
			return "";
		}
		char first = s.charAt(0);
		if (Character.isUpperCase(first)) {
			return s;
		} else {
			return Character.toUpperCase(first) + s.substring(1);
		}
	}

	@Nullable
	private static Class<? extends Activity> guessRestartActivityClass(@NonNull Context context) {
		Class<? extends Activity> resolvedActivityClass;
		resolvedActivityClass = getRestartActivityClassWithIntentFilter(context);
		if (resolvedActivityClass == null) {
			resolvedActivityClass = getLauncherActivity(context);
		}
		return resolvedActivityClass;
	}

	@Nullable
	private static Class<? extends Activity> getRestartActivityClassWithIntentFilter(@NonNull Context context) {
		Intent searchedIntent = new Intent().setAction(INTENT_ACTION_RESTART_ACTIVITY)
				.setPackage(context.getPackageName());
		List<ResolveInfo> resolveInfos = context.getPackageManager().queryIntentActivities(searchedIntent,
				PackageManager.GET_RESOLVED_FILTER);
		if (resolveInfos != null && resolveInfos.size() > 0) {
			ResolveInfo resolveInfo = resolveInfos.get(0);
			try {
				return (Class<? extends Activity>) Class.forName(resolveInfo.activityInfo.name);
			} catch (ClassNotFoundException e) {
				//Should not happen, print it to the log!
				Log.e(TAG, "Failed when resolving the restart activity class via intent filter, stack trace follows!",
						e);
			}
		}
		return null;
	}

	@Nullable
	private static Class<? extends Activity> getLauncherActivity(@NonNull Context context) {
		Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
		if (intent != null && intent.getComponent() != null) {
			try {
				return (Class<? extends Activity>) Class.forName(intent.getComponent().getClassName());
			} catch (ClassNotFoundException e) {
				//Should not happen, print it to the log!
				Log.e(TAG,
						"Failed when resolving the restart activity class via getLaunchIntentForPackage, stack trace follows!",
						e);
			}
		}
		return null;
	}

	@NonNull
	private static Class<? extends Activity> guessErrorActivityClass(@NonNull Context context) {
		Class<? extends Activity> resolvedActivityClass;
		resolvedActivityClass = getErrorActivityClassWithIntentFilter(context);
		if (resolvedActivityClass == null) {
			resolvedActivityClass = DefaultErrorActivity.class;
		}
		return resolvedActivityClass;
	}

	@Nullable
	private static Class<? extends Activity> getErrorActivityClassWithIntentFilter(@NonNull Context context) {
		Intent searchedIntent = new Intent().setAction(INTENT_ACTION_ERROR_ACTIVITY)
				.setPackage(context.getPackageName());
		List<ResolveInfo> resolveInfos = context.getPackageManager().queryIntentActivities(searchedIntent,
				PackageManager.GET_RESOLVED_FILTER);
		if (resolveInfos != null && resolveInfos.size() > 0) {
			ResolveInfo resolveInfo = resolveInfos.get(0);
			try {
				return (Class<? extends Activity>) Class.forName(resolveInfo.activityInfo.name);
			} catch (ClassNotFoundException e) {
				Log.e(TAG, "Failed when resolving the error activity class via intent filter, stack trace follows!", e);
			}
		}
		return null;
	}

	private static void killCurrentProcess() {
		android.os.Process.killProcess(android.os.Process.myPid());
		System.exit(10);
	}

	@SuppressLint("ApplySharedPref") //This must be done immediately since we are killing the app
	private static void setLastCrashTimestamp(@NonNull Context context, long timestamp) {
		context.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE).edit()
				.putLong(SHARED_PREFERENCES_FIELD_TIMESTAMP, timestamp).commit();
	}

	private static long getLastCrashTimestamp(@NonNull Context context) {
		return context.getSharedPreferences(SHARED_PREFERENCES_FILE, Context.MODE_PRIVATE)
				.getLong(SHARED_PREFERENCES_FIELD_TIMESTAMP, -1);
	}

	private static boolean hasCrashedInTheLastSeconds(@NonNull Context context) {
		long lastTimestamp = getLastCrashTimestamp(context);
		long currentTimestamp = new Date().getTime();
		return (lastTimestamp <= currentTimestamp
				&& currentTimestamp - lastTimestamp < config.getMinTimeBetweenCrashesMs());
	}

	public interface EventListener extends Serializable {
		void onLaunchErrorActivity();

		void onRestartAppFromErrorActivity();

		void onCloseAppFromErrorActivity();
	}
}
