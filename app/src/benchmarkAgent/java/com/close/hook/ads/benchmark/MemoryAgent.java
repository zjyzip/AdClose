package com.close.hook.ads.benchmark;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import com.sun.management.ThreadMXBean;

/** Test JVM only; never packaged in the application. */
public final class MemoryAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        // The Gradle test worker may use another classloader for test classes.
        System.getProperties().put("adclose.benchmark.objectSize", (ToLongFunction<Object>) instrumentation::getObjectSize);
        ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        System.getProperties().put("adclose.benchmark.allocatedBytes",
                (LongSupplier) () -> allocation.getThreadAllocatedBytes(Thread.currentThread().getId()));
        System.getProperties().put("adclose.benchmark.vm", System.getProperty("java.vm.name"));
    }
}
