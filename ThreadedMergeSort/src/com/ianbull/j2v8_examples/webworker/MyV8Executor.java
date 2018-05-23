package com.ianbull.j2v8_examples.webworker;

import com.eclipsesource.v8.*;
import com.eclipsesource.v8.utils.V8Executor;

import java.util.Collection;
import java.util.LinkedList;

public class MyV8Executor extends V8Executor {

    private final String script;
    private Collection<String> scripts;
    private V8 runtime;
    private String result;
    private volatile boolean terminated = false;
    private volatile boolean shuttingDown = false;
    private volatile boolean forceTerminating = false;
    private Exception exception = null;
    private LinkedList<String[]> messageQueue = new LinkedList<String[]>();
    private boolean longRunning;
    private String messageHandler;

    public MyV8Executor(String script, boolean longRunning, String messageHandler) {
        super(script, longRunning, messageHandler);
        this.script = script;
        this.longRunning = longRunning;
        this.messageHandler = messageHandler;
    }

    public MyV8Executor(String script) {
        super(script);
        this.script = script;
        this.longRunning = false;
    }

    public MyV8Executor(Collection<String> scripts) {
        super("");
        this.script = null;
        this.scripts = scripts;
        this.longRunning = false;
    }

    @Override
    public void forceTermination() {
        synchronized (this) {
            forceTerminating = true;
            shuttingDown = true;
            if (runtime != null) {
                runtime.terminateExecution();
            }
            notify();
        }
    }

    @Override
    public void shutdown() {
        synchronized (this) {
            shuttingDown = true;
            notify();
        }
    }

    @Override
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    public boolean isTerminating() {
        return forceTerminating;
    }

    @Override
    public boolean hasException() { return exception != null; }

    @Override
    public Exception getException() {
        return exception;
    }

    @Override
    public boolean hasTerminated() {
        return terminated;
    }

    @Override
    public void run() {
        synchronized (this) {
            runtime = V8.createV8Runtime();
            runtime.registerJavaMethod(new MyExecutorTermination(), "__j2v8__checkThreadTerminate");
            runtime.registerJavaMethod(MyV8Executor.this, "print", "print", new Class<?>[] { String.class });
            setup(runtime);
        }
        try {
            if (!forceTerminating) {
                Object scriptResult = runtime.executeScript("__j2v8__checkThreadTerminate();\n" + script, getName(), -1);
                if (scriptResult != null) {
                    result = scriptResult.toString();
                }
                if (scriptResult instanceof Releasable) {
                    ((Releasable) scriptResult).release();
                }
                if (scriptResult instanceof Releasable) {
                    ((Releasable) scriptResult).release();
                }
            }
            while (!forceTerminating && longRunning) {
                synchronized (this) {
                    if (messageQueue.isEmpty() && !shuttingDown) {
                        wait();
                    }
                    if ((messageQueue.isEmpty() && shuttingDown) || forceTerminating) {
                        return;
                    }
                }
                if (!messageQueue.isEmpty()) {
                    String[] message = messageQueue.remove(0);
                    V8Array parameters = new V8Array(runtime);
                    V8Array strings = new V8Array(runtime);
                    try {
                        for (String string : message) {
                            strings.push(string);
                        }
                        parameters.push(strings);
                        runtime.executeVoidFunction(messageHandler, parameters);
                    } finally {
                        strings.release();
                        parameters.release();
                    }
                }
            }
        } catch (Exception e) {
            exception = e;
        } finally {
            synchronized (this) {
                if (runtime.getLocker().hasLock()) {
                    runtime.release();
                    runtime = null;
                }
                terminated = true;
            }
        }
    }

    class MyExecutorTermination implements JavaVoidCallback {

        public void invoke(final V8Object receiver, final V8Array parameters) {
            if (forceTerminating) {
                throw new RuntimeException("V8Thread Termination");
            }
        }
    }

    public void print(String s) {
        System.out.println(s);
    }

    public static void main(String[] args) throws InterruptedException {

        V8 runtime = V8.createV8Runtime();
        MyV8Executor executor = new MyV8Executor("while (true){ print('123') }");
        executor.start();
        V8Object key = new V8Object(runtime);
        runtime.registerV8Executor(key, executor);

        Thread.sleep(1000);

        runtime.shutdownExecutors(true);

        //assertTrue(runtime.getExecutor(key).isShuttingDown());
        key.release();
        runtime.release();
    }

}
