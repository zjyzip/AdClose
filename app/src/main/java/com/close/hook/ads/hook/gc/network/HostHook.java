package com.close.hook.ads.hook.gc.network;

import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class HostHook {

    private static final String LOG_PREFIX = "[HostHook] ";
	private static final Set<String> BLOCKED_HOSTS = new HashSet<>();
    private static boolean isURLHooked = false;

    static {
        setupURLProxy();
        loadBlockedHosts();
    }

    public static void init() {
        hookAllRelevantMethods();
    }

    private static void setupURLProxy() {
        try {
            Constructor<URL> urlConstructor = URL.class.getDeclaredConstructor(String.class);
            Method openConnectionMethod = URL.class.getDeclaredMethod("openConnection");

            XposedBridge.hookMethod(urlConstructor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final URL url = (URL) param.thisObject;
                    if (shouldBlockRequest(url.getHost()) && !isURLHooked) {
                        isURLHooked = true;
                        XposedBridge.hookMethod(openConnectionMethod, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                throw new IOException("Just URL Blocked: " + url);
                            }
                        });
                    }
                }
            });
        } catch (NoSuchMethodException e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up URL proxy: " + e.getMessage());
        }
    }


	private static void loadBlockedHosts() {
		try (InputStream inputStream = HostHook.class.getResourceAsStream("/assets/blocked_hosts.txt");
				BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
			if (inputStream == null) {
				XposedBridge.log("Blocked hosts list not found");
				return;
			}

			String host;
			while ((host = br.readLine()) != null) {
				BLOCKED_HOSTS.add(host);
			}
		} catch (IOException e) {
			XposedBridge.log("Error loading blocked hosts: " + e.getMessage());
		}
	}

    private static boolean shouldBlockRequest(String host) {
		return BLOCKED_HOSTS.contains(host);
    }

    private static final XC_MethodHook blockHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String host = HostExtractor.extractHostFromParam(param);
            if (host != null && shouldBlockRequest(host)) {
		    	param.setThrowable(new UnknownHostException("Connection blocked by HostHook"));
            }
        }
    };

    private static final XC_MethodHook InetAddressHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String host = (String) param.args[0];
            if (host != null && shouldBlockRequest(host)) {
                param.setResult(new InetAddress[0]);
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
