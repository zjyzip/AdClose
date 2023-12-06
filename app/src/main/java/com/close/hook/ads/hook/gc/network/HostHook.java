package com.close.hook.ads.hook.gc.network;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import java.lang.reflect.Proxy;

import java.net.*;
import android.webkit.*;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage.*;

import com.close.hook.ads.hook.util.HookUtil;

public class HostHook {

    private static final String LOG_PREFIX = "[HostHook] ";
    private static final ConcurrentHashMap<String, Boolean> BLOCKED_HOSTS = new ConcurrentHashMap<>();
    private static Method openConnectionMethod;

    static {
        setupURLProxy();
        loadBlockedHostsAsync();
    }

    public static void init() {
        hookAllRelevantMethods();
    }

    private static void setupURLProxy() {
        try {
            Constructor<URL> urlConstructor = URL.class.getDeclaredConstructor(String.class);
            openConnectionMethod = URL.class.getDeclaredMethod("openConnection");

            XposedBridge.hookMethod(urlConstructor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final URL url = (URL) param.thisObject;
                    if (shouldBlockRequest(url.getHost())) {
                        XposedBridge.hookMethod(openConnectionMethod, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
							  String stackTrace = HookUtil.getFormattedStackTrace();
							  throw new IOException("Blocked by HostHook\nURL: " + url.toString() + "\nStack Trace for Request:\n" + stackTrace);
                            }
                        });
                    }
                }
            });
        } catch (NoSuchMethodException e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up URL proxy: " + e.getMessage());
        }
    }

    private static void loadBlockedHostsAsync() {
        CompletableFuture.runAsync(() -> {
        try {
            InputStream inputStream = HostHook.class.getClassLoader().getResourceAsStream("assets/blocked_hosts.txt");
            if (inputStream == null) {
                throw new FileNotFoundException("Blocked hosts list not found in assets");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String host;
                while ((host = reader.readLine()) != null) {
                    BLOCKED_HOSTS.put(host, Boolean.TRUE);
                }
            }
        } catch (IOException e) {
            XposedBridge.log(LOG_PREFIX + "Error loading blocked hosts: " + e);
        }
    });
}

    private static boolean shouldBlockRequest(String host) {
        return BLOCKED_HOSTS.containsKey(host);
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
				logMethodInvocationIssue(param, "args array is null or first argument is null");
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

		private static void logMethodInvocationIssue(XC_MethodHook.MethodHookParam param, String issue) {
			String methodName = param.method != null ? param.method.getName() : "Unknown Method";
			String className = param.method != null ? param.method.getDeclaringClass().getName() : "Unknown Class";
			XposedBridge.log(LOG_PREFIX + "Issue in " + className + "." + methodName + ": " + issue);
		}
	}
}
