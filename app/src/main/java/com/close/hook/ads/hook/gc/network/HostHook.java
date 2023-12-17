package com.close.hook.ads.hook.gc.network;

import java.io.*;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.net.*;
import java.lang.reflect.*;

import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.webkit.*;

import androidx.annotation.Nullable;

import com.close.hook.ads.data.module.BlockedRequest;

import de.robv.android.xposed.*;

public class HostHook {
	private static final String LOG_PREFIX = "[HostHook] ";
	private static final ConcurrentHashMap<String, Boolean> BLOCKED_HOSTS = new ConcurrentHashMap<>();
	private static final CountDownLatch loadDataLatch = new CountDownLatch(1);

	static {
		setupURLProxy();
		loadBlockedHostsAsync();
	}

    private static void setupURLProxy() {
        try {
            Constructor<URL> urlConstructor = URL.class.getDeclaredConstructor(String.class);
            Method openConnectionMethod = URL.class.getDeclaredMethod("openConnection");

            XposedBridge.hookMethod(urlConstructor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final URL url = (URL) param.thisObject;
                    String host = url != null ? url.getHost() : null;

                    if (host != null && shouldBlockRequest(host)) {
                        XposedBridge.hookMethod(openConnectionMethod, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
								sendBlockedRequestBroadcast("block"," setupURLProxy",null, url.toString());
                                return new BlockedURLConnection(url);
                            }
                        });
                    } else if (host != null && !shouldBlockRequest(host)) {
						sendBlockedRequestBroadcast("pass",null,null, url.toString());
                    }
                }
            });
        } catch (NoSuchMethodException e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up URL proxy: " + e.getMessage());
        }
    }

	public static void init() {
		try {
			hookAllRelevantMethods();
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
		}
	}

	@SuppressLint("CheckResult")
	private static void loadBlockedHostsAsync() {
		Flowable.<String>create(emitter -> {
					try (InputStream inputStream = HostHook.class.getResourceAsStream("/assets/blocked_hosts.txt");
						 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
						if (inputStream == null) {
							emitter.onError(new FileNotFoundException("Blocked hosts list not found"));
							return;
						}

						String host;
						while ((host = reader.readLine()) != null) {
							BLOCKED_HOSTS.put(host, Boolean.TRUE);
							emitter.onNext(host);
						}
						emitter.onComplete();
					} catch (IOException e) {
						emitter.onError(e);
					}
				}, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io())
				.observeOn(Schedulers.computation())
				.doFinally(loadDataLatch::countDown)
				.subscribe(
						host -> {},
						error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked hosts: " + error));
	}

	private static void waitForDataLoading() {
		try {
			loadDataLatch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			XposedBridge.log(LOG_PREFIX + "Interrupted while waiting for data loading");
		}
	}

	private static boolean shouldBlockRequest(String host) {
		if (host == null) {
			return false;
		}

		waitForDataLoading();
		sendBlockedRequestBroadcast("all",null,BLOCKED_HOSTS.containsKey(host), host);
		return BLOCKED_HOSTS.containsKey(host);
	}

	private static final XC_MethodHook blockHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = HostExtractor.extractHostFromParam(param);
			if (host != null && shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("block"," blockHook",null,  host);
				param.setThrowable(new UnknownHostException("Connection blocked by HostHook"));
			} else if (host != null && !shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("pass",null, null,host);
			}
		}
	};

	private static final XC_MethodHook InetAddressHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("block"," InetAddressHook",null, host);
				param.setResult(new InetAddress[0]);
				return;
			} else if (host != null && !shouldBlockRequest(host)) {
				sendBlockedRequestBroadcast("pass",null,null, host);
			}
		}
	};

	private static void hookAllRelevantMethods() {

		XposedHelpers.findAndHookMethod(Socket.class, "connect", SocketAddress.class, int.class, blockHook);

		XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddressHook);

		XposedBridge.hookAllMethods(WebView.class, "loadUrl", blockHook);
		XposedBridge.hookAllMethods(WebView.class, "postUrl", blockHook);

	}

	private static class HostExtractor {

		static String extractHostFromParam(XC_MethodHook.MethodHookParam param) {

			if (param.args == null || param.args.length == 0) {
				return null;

			}

			Object arg = param.args[0];

			if (arg instanceof InetSocketAddress) {
				return ((InetSocketAddress) arg).getHostName();

			} else if (arg instanceof WebResourceRequest) {
				return ((WebResourceRequest) arg).getUrl().getHost();

			} else {
				XposedBridge.log(LOG_PREFIX + "Unhandled argument type: " + arg.getClass().getName());
				return null;
			}
		}

	}

	private static void sendBlockedRequestBroadcast(String type, @Nullable String blockType,@Nullable Boolean isBlocked, String request) {
		Intent intent;
		if (Objects.equals(type, "all")) {
			intent = new Intent("com.rikkati.ALL_REQUEST");
		} else if (Objects.equals(type, "block")) {
			intent = new Intent("com.rikkati.BLOCKED_REQUEST");
		} else {
			intent = new Intent("com.rikkati.PASS_REQUEST");
		}

		Context currentContext = AndroidAppHelper.currentApplication();
		if (currentContext != null){
			PackageManager pm = currentContext.getPackageManager();
			String appName;
			try {
				appName = pm
						.getApplicationLabel(
								pm.getApplicationInfo(currentContext.getPackageName(), PackageManager.GET_META_DATA))
						.toString();
			} catch (PackageManager.NameNotFoundException e) {
				appName = currentContext.getPackageName(); // 使用包名作为备选名称

			}
			if (Objects.equals(type, "block")){
				appName += blockType;
			}
			String packageName = currentContext.getPackageName();
			BlockedRequest blockedRequest = new BlockedRequest(appName, packageName, request, System.currentTimeMillis(), type, isBlocked);

			intent.putExtra("request", blockedRequest);
			AndroidAppHelper.currentApplication().sendBroadcast(intent);
		} else {
			Log.w("HostHook", "sendBlockedRequestBroadcast: currentContext is null");
		}

	}

}