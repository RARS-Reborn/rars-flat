package com.github.unaimillan.rars.api;

import picocli.CommandLine;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.IParameterConsumer;
import picocli.CommandLine.Option;

import java.util.Stack;

public class Options{
    @Option(names = {"--np", "--ne"}, description = "use of pseudo instructions and formats not permitted", defaultValue = "true")
    public boolean pseudo = true;            // pseudo instructions allowed in source code or not.
    @Option(names = "--we", description = "warnings are errors")
    public boolean warningsAreErrors = false; // Whether assembler warnings should be considered errors.
    @Option(names = "--sm", description = "start execution at statement with global label main, if defined")
    public boolean startAtMain = false;       // Whether to start execution at statement labeled 'main'
    @Option(names = "--smc", description = "Self Modifying Code - Program can write and branch to either text or data segment")
    public boolean selfModifyingCode = false; // Whether to allow self-modifying code (e.g. write to text segment)
    @Parameters(parameterConsumer = NumbersConsumer.class, arity = "0..1", paramLabel = "<n>", description = "where <n> is an integer maximum count of steps to simulate. If 0, negative or not specified, there is no maximum")
    public int maxSteps = -1;

    static class NumbersConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec, CommandLine.Model.CommandSpec commandSpec) {
            for(int i = stack.size() - 1; i >= 0; i--) {
                try {
                    argSpec.setValue(Integer.parseInt(stack.get(i)));
                    stack.remove(i);
                } catch (NumberFormatException ignore) {
                }
            }
        }
    }
}
