package org.javacs.debug;

/** Response to 'setDataBreakpoints' request. Returned is information about each breakpoint created by this request. */
public class SetDataBreakpointsResponse extends Response {
    SetDataBreakpointsResponseBody body;
}
