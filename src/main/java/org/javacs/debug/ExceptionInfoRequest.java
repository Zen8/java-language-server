package org.javacs.debug;

/**
 * ExceptionInfo request; value of command field is 'exceptionInfo'. Retrieves the details of the exception that caused
 * this event to be raised.
 */
public class ExceptionInfoRequest extends Request {
    ExceptionInfoArguments arguments;
}
