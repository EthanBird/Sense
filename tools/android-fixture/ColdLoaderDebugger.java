import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Host-JDK tool only. Never compiled into an APK. Uses a single event-thread breakpoint. */
public final class ColdLoaderDebugger {
    static final String SERVICE = "io.github.ethanbird.senseime.service.SenseInputMethodService";
    static final String METHOD = "loadProductionDecoderAsync$lambda$0";
    static final String SIGNATURE = "(Lio/github/ethanbird/senseime/service/SenseInputMethodService;)Lio/github/ethanbird/senseime/service/LoadedCandidateDecoderRuntime;";

    static void install(VirtualMachine vm, ReferenceType type) {
        List<Method> methods = type.methodsByName(METHOD, SIGNATURE);
        if (methods.size() != 1 || methods.get(0).isSynchronized() || methods.get(0).isNative())
            throw new IllegalStateException("Reviewed loader entry method changed");
        Method method = methods.get(0);
        BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(method.location());
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.addCountFilter(1);
        request.enable();
        System.out.println("Installed " + method + " codeIndex=" + method.location().codeIndex());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("port new-control-directory");
        Path control = Path.of(args[1]);
        Files.createDirectory(control);
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1");
        options.get("port").setValue(args[0]);
        options.get("timeout").setValue("10000");
        VirtualMachine vm = connector.attach(options);
        EventSet held = null;
        boolean installed = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(75).toNanos();
        try {
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(SERVICE);
            prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            prepare.enable();
            for (ReferenceType type : vm.classesByName(SERVICE)) if (type.isPrepared()) {
                install(vm, type); installed = true; prepare.disable();
            }
            Files.writeString(control.resolve("attached"), "attached\n");
            while (System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(500);
                if (events == null) continue;
                boolean retain = false;
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent ready) {
                        if (!installed) { install(vm, ready.referenceType()); installed = true; }
                        prepare.disable();
                    } else if (event instanceof BreakpointEvent hit) {
                        held = events;
                        ThreadReference thread = hit.thread();
                        if (events.suspendPolicy() != EventRequest.SUSPEND_EVENT_THREAD ||
                            !thread.name().equals("sense-decoder-loader"))
                            throw new IllegalStateException("Unexpected breakpoint thread/policy");
                        if (!vm.canGetOwnedMonitorInfo()) throw new IllegalStateException("Owned monitor inspection unavailable");
                        List<ObjectReference> monitors = thread.ownedMonitors();
                        if (!monitors.isEmpty()) throw new IllegalStateException("Loader entry owns monitors: " + monitors);
                        List<String> trace = new ArrayList<>();
                        trace.add("thread=" + thread.name());
                        trace.add("suspendPolicy=EVENT_THREAD");
                        trace.add("ownedMonitors=0");
                        trace.add("location=" + hit.location());
                        for (StackFrame frame : thread.frames()) trace.add("frame=" + frame.location());
                        for (ThreadReference other : vm.allThreads()) if (other.name().equals("main")) {
                            if (other.isSuspended()) throw new IllegalStateException("Main thread was suspended");
                            trace.add("mainSuspended=false");
                        }
                        Files.write(control.resolve("paused"), trace);
                        System.out.println(String.join("\n", trace));
                        retain = true;
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        throw new IllegalStateException("VM ended before loader acceptance");
                    }
                }
                if (!retain) events.resume();
                else {
                    while (!Files.exists(control.resolve("resume"))) {
                        if (System.nanoTime() >= deadline) throw new IllegalStateException("Loader pause deadline expired");
                        Thread.sleep(50);
                    }
                    held.resume(); held = null;
                    Files.writeString(control.resolve("resumed"), "resumed\n");
                    return;
                }
            }
            throw new IllegalStateException("Loader breakpoint was not reached");
        } finally {
            try { if (held != null) held.resume(); }
            finally { vm.dispose(); }
        }
    }
}
