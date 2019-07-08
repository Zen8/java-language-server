package org.javacs.debug;

/** Arguments for 'dataBreakpointInfo' request. */
public class DataBreakpointInfoArguments {
    /** Reference to the Variable container if the data breakpoint is requested for a child of the container. */
    Integer variablesReference;
    /**
     * The name of the Variable's child to obtain data breakpoint information for. If variableReference isn’t provided,
     * this can be an expression.
     */
    String name;
}
