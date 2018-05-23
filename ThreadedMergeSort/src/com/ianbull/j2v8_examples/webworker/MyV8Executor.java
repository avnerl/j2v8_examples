package com.ianbull.j2v8_examples.webworker;

import com.eclipsesource.v8.*;
import com.eclipsesource.v8.utils.V8Executor;

import java.util.*;

public class MyV8Executor extends V8Executor {

    private final String script;
    private Collection<String> scripts;
    private V8 runtime;
    private String result;
    private Collection<Object> results;
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
        this.results = new ArrayList<Object>();
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
    protected void setup(final V8 runtime) {
        runtime.registerJavaMethod(MyV8Executor.this, "print", "print", new Class<?>[] { String.class });
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
    public String getResult() {
        return result;
    }

    public Collection<Object> getResults() {
        return results;
    }

    @Override
    public void run() {
        synchronized (this) {
            runtime = V8.createV8Runtime();
            runtime.registerJavaMethod(new MyExecutorTermination(), "__j2v8__checkThreadTerminate");
            setup(runtime);
        }
        try {
            if (!forceTerminating) {
                Object scriptResult = null;

                if (script != null)
                    scriptResult = runtime.executeScript("__j2v8__checkThreadTerminate();\n" + script, getName(), -1);
                else {
                    for (String sc: scripts) {
                        scriptResult = runtime.executeScript("__j2v8__checkThreadTerminate();\n" + sc, getName(), -1);
                        System.out.println(scriptResult);
                        //runtime.getObject("document").getString("title"); // TODO extract required results here. pass a runtime => Object function
                        results.add(scriptResult);
                    }
                }

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

        // single bad scripts demo

        String badScript = "while (true){ print('123') }";
        V8 runtime1 = V8.createV8Runtime();
        MyV8Executor executor1 = new MyV8Executor(badScript);
        executor1.start();
        V8Object key1 = new V8Object(runtime1);
        runtime1.registerV8Executor(key1, executor1);

        Thread.sleep(1000);

        System.out.println("executor1 is alive: " + executor1.isAlive());
        if (executor1.isAlive()) {
            System.out.println("executor1 timeout! shutting down...");
            runtime1.shutdownExecutors(true);
            Thread.sleep(100);
        }
        System.out.println("executor1 is alive: " + executor1.isAlive());

        System.out.println("executor1 shutting down: " + runtime1.getExecutor(key1).isShuttingDown());

        key1.release();
        runtime1.release();

        // multiple scripts demo

        String[] scripts = { "var document = {}", "var title = 'new title';", "document.title = title;" };
        V8 runtime2 = V8.createV8Runtime();
        MyV8Executor executor2 = new MyV8Executor(Arrays.asList(scripts));
        executor2.start();
        V8Object key2 = new V8Object(runtime2);
        runtime2.registerV8Executor(key2, executor2);

        Thread.sleep(1000);

        System.out.println("executor2 is alive: " + executor2.isAlive());
        if (executor2.isAlive()) {
            System.out.println("executor2 timeout! shutting down...");
            runtime1.shutdownExecutors(true);
            Thread.sleep(100);
        }
        System.out.println("executor2 is alive: " + executor2.isAlive());

        System.out.println("executor2 shutting down: " + runtime2.getExecutor(key2).isShuttingDown());

        key2.release();
        runtime2.release();

    }

}
