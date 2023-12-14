package com.close.hook.ads.hook.gc.network;

import java.io.*;
import java.util.concurrent.*;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.lang.reflect.*;
import java.lang.reflect.Proxy;

import java.net.*;
import android.webkit.*;

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
					URL url = (URL) param.thisObject;
					if (shouldBlockRequest(url.getHost())) {
						param.setObjectExtra("block", true);
					}
				}
			});

			XposedBridge.hookMethod(openConnectionMethod, new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					URL url = (URL) param.thisObject;
					if (Boolean.TRUE.equals(param.getObjectExtra("block"))) {
						throw new IOException("Connection blocked: " + url);
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
				.observeOn(Schedulers.computation()).doFinally(loadDataLatch::countDown).subscribe(host -> {
				}, error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked hosts: " + error));
	}

	private static boolean shouldBlockRequest(String host) {
		waitForDataLoading();
		return BLOCKED_HOSTS.containsKey(host);
	}

	private static void waitForDataLoading() {
		try {
			loadDataLatch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			XposedBridge.log(LOG_PREFIX + "Interrupted while waiting for data loading");
		}
	}

	private static final XC_MethodHook blockHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = HostExtractor.extractHostFromParam(param);
			if (host != null && shouldBlockRequest(host)) {
				param.setThrowable(new UnknownHostException("Connection blocked by HostHook"));
				return;
			}
		}
	};

	private static final XC_MethodHook InetAddressHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockRequest(host)) {
				param.setResult(new InetAddress[0]);
				return;
			}
		}
	};

	private static void hookAllRelevantMethods() {

		XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddressHook);

		XposedHelpers.findAndHookMethod(Socket.class, "connect", SocketAddress.class, int.class, blockHook);

		XposedBridge.hookAllMethods(WebView.class, "loadUrl", blockHook);
		XposedBridge.hookAllMethods(WebView.class, "postUrl", blockHook);

	}

	private static class HostExtractor {

		static String extractHostFromParam(XC_MethodHook.MethodHookParam param) {

			if (param.args == null || param.args.length == 0 || param.args[0] == null) {
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

}
