package org.javacs.debug;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.*;
import org.javacs.LogFormat;
import org.javacs.debug.proto.*;

public class JavaDebugServer implements DebugServer {
    public static void main(String[] args) { // TODO don't show references for main method
        // createLogFile();
        LOG.info(String.join(" ", args));
        new DebugAdapter(JavaDebugServer::new, System.in, System.out).run();
        System.exit(0);
    }

    private static void createLogFile() {
        try {
            // TODO make location configurable
            var logFile =
                    new FileHandler("/Users/georgefraser/Documents/java-language-server/java-debug-server.log", false);
            logFile.setFormatter(new LogFormat());
            Logger.getLogger("").addHandler(logFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final DebugClient client;
    private List<Path> sourceRoots = List.of();
    private VirtualMachine vm;
    private final List<Breakpoint> pendingBreakpoints = new ArrayList<>();
    private static int breakPointCounter = 0;

    class ReceiveVmEvents implements Runnable {
        @Override
        public void run() {
            var events = vm.eventQueue();
            while (true) {
                try {
                    var nextSet = events.remove();
                    for (var event : nextSet) {
                        process(event);
                    }
                } catch (VMDisconnectedException __) {
                    LOG.info("VM disconnected");
                    return;
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e.getMessage(), e);
                    return;
                }
            }
        }

        private void process(com.sun.jdi.event.Event event) {
            LOG.info("Received " + event.toString() + " from VM");
            if (event instanceof ClassPrepareEvent) {
                var prepare = (ClassPrepareEvent) event;
                var type = prepare.referenceType();
                LOG.info("ClassPrepareRequest for class " + type.name() + " in source " + path(type));
                enablePendingBreakpointsIn(type);
                vm.resume();
            } else if (event instanceof com.sun.jdi.event.BreakpointEvent) {
                var breakpoint = (com.sun.jdi.event.BreakpointEvent) event;
                var evt = new StoppedEventBody();
                evt.reason = "breakpoint";
                evt.threadId = breakpoint.thread().uniqueID();
                evt.allThreadsStopped = breakpoint.request().suspendPolicy() == EventRequest.SUSPEND_ALL;
                client.stopped(evt);
            } else if (event instanceof StepEvent) {
                var breakpoint = (StepEvent) event;
                var evt = new StoppedEventBody();
                evt.reason = "step";
                evt.threadId = breakpoint.thread().uniqueID();
                evt.allThreadsStopped = breakpoint.request().suspendPolicy() == EventRequest.SUSPEND_ALL;
                client.stopped(evt);
                // Disable event so we can create new step events
                event.request().disable();
            } else if (event instanceof VMDeathEvent) {
                client.exited(new ExitedEventBody());
            } else if (event instanceof VMDisconnectEvent) {
                client.terminated(new TerminatedEventBody());
            }
        }
    }

    public JavaDebugServer(DebugClient client) {
        this.client = client;
        class LogToConsole extends Handler {
            private final LogFormat format = new LogFormat();

            @Override
            public void publish(LogRecord r) {
                var evt = new OutputEventBody();
                evt.category = "console";
                evt.output = format.format(r);
                client.output(evt);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        }
        Logger.getLogger("debug").addHandler(new LogToConsole());
    }

    @Override
    public Capabilities initialize(InitializeRequestArguments req) {
        var resp = new Capabilities();
        resp.supportsConfigurationDoneRequest = true;
        return resp;
    }

    @Override
    public SetBreakpointsResponseBody setBreakpoints(SetBreakpointsArguments req) {
        LOG.info("Received " + req.breakpoints.length + " breakpoints");
        // Add these breakpoints to the pending set
        var resp = new SetBreakpointsResponseBody();
        resp.breakpoints = new Breakpoint[req.breakpoints.length];
        for (var i = 0; i < req.breakpoints.length; i++) {
            resp.breakpoints[i] = tryEnableBreakpoint(req.source, req.breakpoints[i]);
        }
        return resp;
    }

    private Breakpoint tryEnableBreakpoint(Source source, SourceBreakpoint breakpoint) {
        // Check for breakpoint in loaded classes
        for (var type : vm.allClasses()) {
            var path = path(type);
            if (path != null && source.path.endsWith(path)) {
                return enableBreakpointImmediately(source, breakpoint, type);
            }
        }
        // If class hasn't been loaded, add breakpoint to pending list
        LOG.info(String.format("Enable %s:%d later", source.path, breakpoint.line));
        var pending = new Breakpoint();
        pending.id = breakPointCounter++;
        pending.source = new Source();
        pending.source.path = source.path;
        pending.line = breakpoint.line;
        pending.column = breakpoint.column;
        pending.verified = false;
        pending.message = source.name + " is not yet loaded";
        pendingBreakpoints.add(pending);
        return pending;
    }

    private Breakpoint enableBreakpointImmediately(Source source, SourceBreakpoint breakpoint, ReferenceType type) {
        try {
            var locations = type.locationsOfLine(breakpoint.line);
            for (var line : locations) {
                LOG.info(
                        String.format(
                                "Create breakpoint %s:%d for %s:%d",
                                line.sourcePath(), line.lineNumber(), source.path, breakpoint.line));
                var req = vm.eventRequestManager().createBreakpointRequest(line);
                req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                req.enable();
            }
            if (locations.isEmpty()) {
                LOG.info(
                        String.format(
                                "No locations in %s for breakpoint %s:%d", path(type), source.path, breakpoint.line));
                var failed = new Breakpoint();
                failed.verified = false;
                failed.message = source.name + ":" + breakpoint.line + " could not be found or had no code on it";
                return failed;
            }
            var ok = new Breakpoint();
            ok.verified = true;
            ok.source = source;
            ok.line = breakpoint.line;
            ok.column = breakpoint.column;
            return ok;
        } catch (AbsentInformationException __) {
            var failed = new Breakpoint();
            failed.verified = false;
            failed.message = source.name + ":" + breakpoint.line + " could not be found or had no code on it";
            return failed;
        }
    }

    @Override
    public SetFunctionBreakpointsResponseBody setFunctionBreakpoints(SetFunctionBreakpointsArguments req) {
        LOG.warning("Not yet implemented");
        return new SetFunctionBreakpointsResponseBody();
    }

    @Override
    public void setExceptionBreakpoints(SetExceptionBreakpointsArguments req) {
        LOG.warning("Not yet implemented");
    }

    @Override
    public void configurationDone() {
        listenForClassPrepareEvents();
        enablePendingBreakpointsInLoadedClasses();
        vm.resume();
    }

    /* Request to be notified when files with pending breakpoints are loaded */
    private void listenForClassPrepareEvents() {
        Objects.requireNonNull(vm, "vm has not been initialized");
        // Get all file names
        var distinctSourceNames = new HashSet<String>();
        for (var b : pendingBreakpoints) {
            var path = Paths.get(b.source.path);
            var name = path.getFileName();
            distinctSourceNames.add(name.toString());
        }
        // Listen for classes with those names
        for (var name : distinctSourceNames) {
            LOG.info("Listen for ClassPrepareRequest in " + name);
            var requestClassEvent = vm.eventRequestManager().createClassPrepareRequest();
            requestClassEvent.addSourceNameFilter("*" + name);
            requestClassEvent.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            requestClassEvent.enable();
        }
    }

    @Override
    public void launch(LaunchRequestArguments req) {
        throw new UnsupportedOperationException();
    }

    private static AttachingConnector connector(String transport) {
        var found = new ArrayList<String>();
        for (var conn : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (conn.transport().name().equals(transport)) {
                return conn;
            }
            found.add(conn.transport().name());
        }
        throw new RuntimeException("Couldn't find connector for transport " + transport + " in " + found);
    }

    @Override
    public void attach(AttachRequestArguments req) {
        // Remember available source roots
        sourceRoots = new ArrayList<Path>();
        for (var string : req.sourceRoots) {
            var path = Paths.get(string);
            if (!Files.exists(path)) {
                LOG.warning(string + " does not exist");
                continue;
            } else if (!Files.isDirectory(path)) {
                LOG.warning(string + " is not a directory");
                continue;
            } else {
                LOG.info(path + " is a source root");
                sourceRoots.add(path);
            }
        }
        // Attach to the running VM
        if (!tryToConnect(req.port)) {
            throw new RuntimeException("Failed to connect after 15 attempts");
        }
        // Create a thread that reads events from the VM
        var reader = new java.lang.Thread(new ReceiveVmEvents(), "receive-vm");
        reader.setDaemon(true);
        reader.start();
        // Tell the client we are ready to receive breakpoints
        client.initialized();
    }

    private boolean tryToConnect(int port) {
        var conn = connector("dt_socket");
        var args = conn.defaultArguments();
        var intervalMs = 500;
        var tryForS = 15;
        var attempts = tryForS * 1000 / intervalMs;
        args.get("port").setValue(Integer.toString(port));
        for (var attempt = 0; attempt < attempts; attempt++) {
            try {
                vm = conn.attach(args);
                return true;
            } catch (ConnectException e) {
                LOG.warning(e.getMessage());
                try {
                    java.lang.Thread.sleep(intervalMs);
                } catch (InterruptedException __) {
                    // Nothing to do
                }
            } catch (IOException | IllegalConnectorArgumentsException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    /* Set breakpoints for already-loaded classes */
    private void enablePendingBreakpointsInLoadedClasses() {
        Objects.requireNonNull(vm, "vm has not been initialized");
        for (var type : vm.allClasses()) {
            enablePendingBreakpointsIn(type);
        }
    }

    private void enablePendingBreakpointsIn(ReferenceType type) {
        // Check that class has source information
        var path = path(type);
        if (path == null) return;
        // Look for pending breakpoints that can be enabled
        var enabled = new ArrayList<Breakpoint>();
        for (var b : pendingBreakpoints) {
            if (b.source.path.endsWith(path)) {
                enablePendingBreakpoint(b, type);
                enabled.add(b);
            }
        }
        pendingBreakpoints.removeAll(enabled);
    }

    private void enablePendingBreakpoint(Breakpoint b, ReferenceType type) {
        LOG.info("Enable breakpoint at " + b.source.path + ":" + b.line);
        try {
            var locations = type.locationsOfLine(b.line);
            for (var line : locations) {
                var req = vm.eventRequestManager().createBreakpointRequest(line);
                req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                req.enable();
            }
            if (locations.isEmpty()) {
                var failed = new BreakpointEventBody();
                failed.reason = "no code";
                failed.breakpoint = b;
                b.verified = false;
                b.message = b.source.name + ":" + b.line + " could not be found or had no code on it";
                client.breakpoint(failed);
                return;
            }
            var ok = new BreakpointEventBody();
            ok.reason = "class loaded";
            ok.breakpoint = b;
            b.verified = true;
            b.message = null;
            client.breakpoint(ok);
        } catch (AbsentInformationException __) {
            var failed = new BreakpointEventBody();
            failed.reason = "no code";
            failed.breakpoint = b;
            b.verified = false;
            b.message = b.source.name + ":" + b.line + " could not be found or had no code on it";
            client.breakpoint(failed);
        }
    }

    private String path(ReferenceType type) {
        try {
            for (var path : type.sourcePaths(vm.getDefaultStratum())) {
                return path;
            }
            return null;
        } catch (AbsentInformationException __) {
            return null;
        }
    }

    @Override
    public void disconnect(DisconnectArguments req) {
        try {
            vm.dispose();
        } catch (VMDisconnectedException __) {
            LOG.warning("VM has already terminated");
        }
        vm = null;
    }

    @Override
    public void terminate(TerminateArguments req) {
        vm.exit(1);
    }

    @Override
    public void continue_(ContinueArguments req) {
        vm.resume();
    }

    @Override
    public void next(NextArguments req) {
        var thread = findThread(req.threadId);
        if (thread == null) {
            LOG.warning("No thread with id " + req.threadId);
            return;
        }
        LOG.info("Send StepRequest(STEP_LINE, STEP_OVER) to VM and resume");
        var step = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
        step.addCountFilter(1);
        step.enable();
        vm.resume();
    }

    @Override
    public void stepIn(StepInArguments req) {
        var thread = findThread(req.threadId);
        if (thread == null) {
            LOG.warning("No thread with id " + req.threadId);
            return;
        }
        LOG.info("Send StepRequest(STEP_LINE, STEP_INTO) to VM and resume");
        var step = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
        step.addCountFilter(1);
        step.enable();
        vm.resume();
    }

    @Override
    public void stepOut(StepOutArguments req) {
        var thread = findThread(req.threadId);
        if (thread == null) {
            LOG.warning("No thread with id " + req.threadId);
            return;
        }
        LOG.info("Send StepRequest(STEP_LINE, STEP_OUT) to VM and resume");
        var step = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OUT);
        step.addCountFilter(1);
        step.enable();
        vm.resume();
    }

    @Override
    public ThreadsResponseBody threads() {
        var threads = new ThreadsResponseBody();
        threads.threads = vm.allThreads().stream().map(this::asThread).toArray(org.javacs.debug.proto.Thread[]::new);
        return threads;
    }

    private org.javacs.debug.proto.Thread asThread(ThreadReference t) {
        var thread = new org.javacs.debug.proto.Thread();
        thread.id = t.uniqueID();
        thread.name = t.name();
        return thread;
    }

    private ThreadReference findThread(long threadId) {
        for (var thread : vm.allThreads()) {
            if (thread.uniqueID() == threadId) {
                return thread;
            }
        }
        return null;
    }

    @Override
    public StackTraceResponseBody stackTrace(StackTraceArguments req) {
        try {
            for (var t : vm.allThreads()) {
                if (t.uniqueID() == req.threadId) {
                    var length = t.frameCount() - req.startFrame;
                    if (req.levels != null && req.levels < length) {
                        length = req.levels;
                    }
                    var frames = t.frames(req.startFrame, length);
                    var resp = new StackTraceResponseBody();
                    resp.stackFrames =
                            frames.stream().map(this::asStackFrame).toArray(org.javacs.debug.proto.StackFrame[]::new);
                    resp.totalFrames = t.frameCount();
                    return resp;
                }
            }
            throw new RuntimeException("Couldn't find thread " + req.threadId);
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException(e);
        }
    }

    private org.javacs.debug.proto.StackFrame asStackFrame(com.sun.jdi.StackFrame f) {
        var frame = new org.javacs.debug.proto.StackFrame();
        frame.id = uniqueFrameId(f);
        frame.name = f.location().method().name();
        frame.source = asSource(f.location());
        frame.line = f.location().lineNumber();
        return frame;
    }

    private Source asSource(Location l) {
        try {
            var path = findSource(l);
            var src = new Source();
            src.name = l.sourceName();
            src.path = Objects.toString(path, null);
            return src;
        } catch (AbsentInformationException __) {
            var src = new Source();
            src.path = path(l.declaringType());
            src.name = l.declaringType().name();
            src.presentationHint = "deemphasize";
            return src;
        }
    }

    private static final Set<String> warnedCouldNotFind = new HashSet<>();

    private Path findSource(Location l) throws AbsentInformationException {
        var relative = l.sourcePath();
        for (var root : sourceRoots) {
            var absolute = root.resolve(relative);
            if (Files.exists(absolute)) {
                return absolute;
            }
        }
        if (!warnedCouldNotFind.contains(relative)) {
            LOG.warning("Could not find " + relative);
            warnedCouldNotFind.add(relative);
        }
        return null;
    }

    /** Debug adapter protocol doesn't seem to like frame 0 */
    private static final int FRAME_OFFSET = 100;

    private long uniqueFrameId(com.sun.jdi.StackFrame f) {
        try {
            long count = FRAME_OFFSET;
            for (var thread : f.virtualMachine().allThreads()) {
                if (thread.equals(f.thread())) {
                    for (var frame : thread.frames()) {
                        if (frame.equals(f)) {
                            return count;
                        } else {
                            count++;
                        }
                    }
                } else {
                    count += thread.frameCount();
                }
            }
            return count;
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException(e);
        }
    }

    private com.sun.jdi.StackFrame findFrame(long id) {
        try {
            long count = FRAME_OFFSET;
            for (var thread : vm.allThreads()) {
                if (id < count + thread.frameCount()) {
                    var offset = (int) (id - count);
                    return thread.frame(offset);
                } else {
                    count += thread.frameCount();
                }
            }
            throw new RuntimeException("Couldn't find frame " + id);
        } catch (IncompatibleThreadStateException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ScopesResponseBody scopes(ScopesArguments req) {
        var resp = new ScopesResponseBody();
        var locals = new Scope();
        locals.name = "Locals";
        locals.presentationHint = "locals";
        locals.variablesReference = req.frameId * 2;
        var arguments = new Scope();
        arguments.name = "Arguments";
        arguments.presentationHint = "arguments";
        arguments.variablesReference = req.frameId * 2 + 1;
        resp.scopes = new Scope[] {locals, arguments};
        return resp;
    }

    @Override
    public VariablesResponseBody variables(VariablesArguments req) {
        var frameId = req.variablesReference / 2;
        var scopeId = (int) (req.variablesReference % 2);
        var frame = findFrame(frameId);
        var resp = new VariablesResponseBody();
        switch (scopeId) {
            case 0: // locals
                resp.variables = locals(frame);
                break;
            case 1: // arguments
                resp.variables = arguments(frame);
                break;
        }
        return resp;
    }

    private Variable[] locals(com.sun.jdi.StackFrame frame) {
        return visible(frame)
                .stream()
                .filter(v -> !v.isArgument())
                .map(v -> asVariable(v, frame))
                .toArray(Variable[]::new);
    }

    private Variable[] arguments(com.sun.jdi.StackFrame frame) {
        return visible(frame)
                .stream()
                .filter(v -> v.isArgument())
                .map(v -> asVariable(v, frame))
                .toArray(Variable[]::new);
    }

    private List<LocalVariable> visible(com.sun.jdi.StackFrame frame) {
        try {
            return frame.visibleVariables();
        } catch (AbsentInformationException __) {
            LOG.warning(String.format("No visible variable information in %s", frame.location()));
            return List.of();
        }
    }

    private Variable asVariable(LocalVariable v, com.sun.jdi.StackFrame frame) {
        Variable convert = new Variable();
        convert.name = v.name();
        convert.value = frame.getValue(v).toString();
        convert.type = v.typeName();
        convert.variablesReference =
                -1; // TODO set variablesReference and allow inspecting structure of collections and POJOs
        // TODO set variablePresentationHint
        return convert;
    }

    @Override
    public EvaluateResponseBody evaluate(EvaluateArguments req) {
        throw new UnsupportedOperationException();
    }

    private static final Logger LOG = Logger.getLogger("debug");
}
