package org.javacs.debug;

/** Disassemble request; value of command field is 'disassemble'. Disassembles code stored at the provided location. */
public class DisassembleRequest extends Request {
    DisassembleArguments arguments;
}
