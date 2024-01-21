package com.github.unaimillan.rars;

import com.github.unaimillan.rars.api.Options;
import com.github.unaimillan.rars.riscv.hardware.*;
import com.github.unaimillan.rars.util.Binary;

import java.io.File;
import java.util.*;

import picocli.CommandLine;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.IParameterConsumer;
import picocli.CommandLine.ParameterException;

import static java.lang.System.exit;

public class Parser{
    @Mixin
    public Options options;

    //Remove elements from stack only here
    @Parameters(parameterConsumer = FileConsumer.class, description = "Files to execute. If more than one filename is listed, the first is assumed to be the main unless the global statement label 'main' is defined in one of the files. Exception handler not automatically assembled.  Add it to the file list. Options used here do not affect RARS Settings menu values and vice versa.")
    private File[] files = new File[0];

    static class FileConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec, CommandLine.Model.CommandSpec commandSpec) {
            File temp = new File(stack.pop());
            if (temp.exists() && !temp.isDirectory()) {
                File[] old = argSpec.getValue();
                List<File> files = new LinkedList<>(Arrays.asList(old));
                files.add(temp);
                File[] newFiles = new File[files.size()];
                newFiles = files.toArray(newFiles);
                argSpec.setValue(newFiles);
            }
        }
    }

    @Option(names = { "-h", "--help" }, usageHelp = true, description = "display a help message") // Make class runnable to make it work
    private boolean helpRequested = false;

    @Option(names = "-g", description = "force GUI mode")
    private boolean gui;
    @Option(names = "-a", description = "assemble only, do not simulate", defaultValue = "true")
    private boolean simulate;
    @Option(names = "--rv64", description = "Enables 64 bit assembly and executables (Not fully compatible with rv32)")
    private boolean rv64;
    @Option(names = "-b", description = "brief - do not display register/memory address along with contents",
            defaultValue = "true")
    private boolean verbose;  // display register name or address along with contents
    @Option(names = "-p", description = "Project mode - assemble all files in the same directory as given file")
    private boolean assembleProject; // assemble only the given file or all files in its directory
    @Option(names = "--ic", description = "display count of basic instructions 'executed'")
    private boolean countInstructions; // Whether to count and report number of instructions executed
    @Option(names = "--nc", description = "do not display copyright notice (for cleaner redirected/piped output)",
            defaultValue = "true")
    private boolean displayCopyright; // Default: true // "nc": false

    @Option(names = "--ae", description = "terminate RARS with integer exit code <n> if an assemble error occurs",
            defaultValue = "0", paramLabel = "<n>")
    private int assembleErrorExitCode;  // RARS command exit code to return if assemble error occurs
    @Option(names = "--se", description = "terminate RARS with integer exit code <n> if a simulation (run) error occurs",
            defaultValue = "0", paramLabel = "<n>")
    private int simulateErrorExitCode; // RARS command exit code to return if simulation error occurs

    @Option(paramLabel = "<arguments>", arity = "0..*", names = "--pa", description = "Program Arguments follow in a " +
            "space-separated list. This option must be placed AFTER ALL FILE NAMES, because everything that follows " +
            "it is interpreted as a program argument to be made available to the program at runtime.")
    // optional program args for program (becomes argc, argv)
    private ArrayList<String> programArgumentList = new ArrayList<>();

    @Parameters(parameterConsumer = RangeConsumer.class, paramLabel = "<m>-<n>", description = "memory address range " +
            "from <m> to <n> whose contents to display at end of run. <m> and <n> may be hex or decimal, must be on " +
            "word boundary, <m> <= <n>.  Option may be repeated.") // Are you sure?
    private final ArrayList<String> memoryDisplayList = new ArrayList<>();

    static class RangeConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec,
                                      CommandLine.Model.CommandSpec commandSpec) {
            String arg = stack.peek();
            if (arg.indexOf(rangeSeparator) > 0 &&
                    arg.indexOf(rangeSeparator) < arg.length() - 1) { // -a -g --mc
                // assume correct format, two numbers separated by -, no embedded spaces.
                // If that doesn't work it is invalid.
                ArrayList<String> temp = argSpec.getValue();
                String[] memoryRange = new String[2];
                memoryRange[0] = arg.substring(0, arg.indexOf(rangeSeparator));
                memoryRange[1] = arg.substring(arg.indexOf(rangeSeparator) + 1);
                // NOTE: I will use homegrown decoder, because Integer.decode will throw
                // exception on address higher than 0x7FFFFFFF (e.g. sign bit is 1).
                if (Binary.stringToInt(memoryRange[0]) > Binary.stringToInt(memoryRange[1]) ||
                        !Memory.wordAligned(Binary.stringToInt(memoryRange[0])) ||
                        !Memory.wordAligned(Binary.stringToInt(memoryRange[1]))) {
                    Launch.out.println("Invalid range");
                    exit(Globals.exitCode);
                }
                temp.add(memoryRange[0]);
                temp.add(memoryRange[1]);
                argSpec.setValue(temp);
            }
        }
    }

    @Parameters(parameterConsumer = regByNumConsumer.class, paramLabel = "x<reg>", description = "where <reg> is " +
            "number or name (e.g. 5, t3, f10) of register whose content to display at end of run.  Option may be " +
            "repeated.")
    private final ArrayList<String> registerByNumber = new ArrayList<>();

    static class regByNumConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec,
                                      CommandLine.Model.CommandSpec commandSpec) {
            String arg = stack.peek();
            if (arg.indexOf("x") == 0) {
                if (RegisterFile.getRegister(arg) == null &&
                        FloatingPointRegisterFile.getRegister(arg) == null) {
                    Launch.out.println("Invalid Register Name: " + arg); //shouldn't it throw error? (e.g. add exit)
                } else {
                    ArrayList<String> temp = argSpec.getValue();
                    temp.add(arg);
                    argSpec.setValue(temp);
                }
            }
        }
    }

    @Parameters(parameterConsumer = regByNameConsumer.class, paramLabel = "<reg_name>", description = "where " +
            "<reg_name> is name (e.g. t3, f10) of register whose content to display at end of run. Option may be " +
            "repeated.")
    private final ArrayList<String> registerByName = new ArrayList<>();

    static class regByNameConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec,
                                      CommandLine.Model.CommandSpec commandSpec) {
            String arg = stack.peek();
            if (RegisterFile.getRegister(arg) != null ||
                    FloatingPointRegisterFile.getRegister(arg) != null) {
                ArrayList<String> temp = argSpec.getValue();
                temp.add(arg);
                argSpec.setValue(temp);
            }
        }
    }

    @Parameters(parameterConsumer = UnmatchedConsumer.class)
    ArrayList<String> unmatched = new ArrayList<>();

    static class UnmatchedConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec,
                                      CommandLine.Model.CommandSpec commandSpec) {
            String arg = stack.peek();
            File file = new File(arg);
            if(file.exists() && !file.isDirectory()) {
                return;
            }
            if(arg.indexOf(rangeSeparator) > 0 &&
                    arg.indexOf(rangeSeparator) < arg.length() - 1) {
                return;
            }
            if(arg.indexOf("x") == 0) {
                return;
            }
            if (RegisterFile.getRegister(arg) != null ||
                    FloatingPointRegisterFile.getRegister(arg) != null) {
                return;
            }
            try {
                Integer.parseInt(arg);
            } catch (NumberFormatException ignore) {
                ArrayList<String> temp = argSpec.getValue();
                temp.add(arg);
                argSpec.setValue(temp);
            }
        }
    }
    private DisplayFormat displayFormat = DisplayFormat.HEXADECIMAL;
    private final ArrayList<String> registerDisplayList = new ArrayList<>();
    private final ArrayList<String> filenameList = new ArrayList<>();
    private ArrayList<String[]> dumpTriples = null; // each element holds 3 arguments for dump option
    private static final String rangeSeparator = "-";
    private final String[] args;

    @Command(name = "--me", description = "display RARS messages to standard err instead of standard out. " +
            "Can separate messages from program output using redirection")
    private void changeOut() {
        Launch.out = System.err;
    }

    @Command(name = "-d", description = "display RARS debugging statements")
    private void setDebug() {
        Globals.debug = true;
    }

    @Command(name = "--ascii", description = "display memory or register contents interpreted as ASCII codes")
    private void displayAscii() {
        displayFormat = DisplayFormat.ASCII;
    }

    @Command(name = "--dec", description = "display memory or register contents in decimal")
    private void displayDecimal() {
        displayFormat = DisplayFormat.DECIMAL;
    }

    @Command(name = "--hex", description = "display memory or register contents in hexadecimal (default)")
    private void displayHexadecimal() {
        displayFormat = DisplayFormat.HEXADECIMAL;
    }

    @Command(name = "--mc", description = "set memory configuration")
    private void setMemoryConfig(@Parameters(arity = "1", paramLabel = "<config>",
            description = "is case-sensitive and possible values are: Default for the default 32-bit address space, " +
                    "CompactDataAtZero for a 32KB memory with data segment at address 0, or CompactTextAtZero for a " +
                    "32KB memory with text segment at address 0") String configName) {
        MemoryConfiguration config = MemoryConfigurations.getConfigurationByName(configName);
        if (config == null) {
            Launch.out.println("Invalid memory configuration: " + configName);
            exit(Globals.exitCode);
        } else {
            MemoryConfigurations.setCurrentConfiguration(config);
        }
    }

    @Command(name = "--dump", description = "memory dump of specified memory segment in specified format to specified " +
            "file. Option may be repeated (not yet). Dump occurs at the end of simulation unless 'a' option is used.")
    private void dump(
            @Parameters(paramLabel = "<segment>", description = ".text, .data, or a range like 0x400000-0x10000000")
            String segment,
            @Parameters(paramLabel = "<format>", description = "AsciiText, Binary, BinaryText, HexText, HEX, " +
                    "SegmentWindow") String format,
            @Parameters(paramLabel = "<file>") File file) {
        if (dumpTriples == null)
            dumpTriples = new ArrayList<>();
        dumpTriples.add(new String[]{segment, format, file.getPath()});
    }

    public Parser(String[] args){
        this.args = args;
        options = new Options();
        gui = args.length == 0;
        registerDisplayList.addAll(registerByNumber);
        registerDisplayList.addAll(registerByName);
    }

    public void parseCommandArgs() throws Exception {
        CommandLine cl = new CommandLine(this);
        cl.setOptionsCaseInsensitive(true)
                .setSubcommandsCaseInsensitive(true)
                .parseArgs(args);
        if (!unmatched.isEmpty()) {
            StringBuilder message = new StringBuilder();
            for (int i = 0; i < unmatched.size(); i++) {
                if (i != 0) {
                    message.append("\n");
                }
                message.append("Invalid Command Argument: ").append(unmatched.get(i));
            }
            throw new Exception(String.valueOf(message));
        }
        for (File file : files) {
            filenameList.add(file.getPath());
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Check for memory address subrange.  Has to be two integers separated
    // by "-"; no embedded spaces.  e.g. 0x00400000-0x00400010
    // If number is not multiple of 4, will be rounded up to next higher.

    public String[] checkMemoryAddressRange(String arg) throws NumberFormatException {
        String[] memoryRange = null;
        if (arg.indexOf(rangeSeparator) > 0 &&
                arg.indexOf(rangeSeparator) < arg.length() - 1) {
            // assume correct format, two numbers separated by -, no embedded spaces.
            // If that doesn't work it is invalid.
            memoryRange = new String[2];
            memoryRange[0] = arg.substring(0, arg.indexOf(rangeSeparator));
            memoryRange[1] = arg.substring(arg.indexOf(rangeSeparator) + 1);
            // NOTE: I will use homegrown decoder, because Integer.decode will throw
            // exception on address higher than 0x7FFFFFFF (e.g. sign bit is 1).
            if (Binary.stringToInt(memoryRange[0]) > Binary.stringToInt(memoryRange[1]) ||
                    !Memory.wordAligned(Binary.stringToInt(memoryRange[0])) ||
                    !Memory.wordAligned(Binary.stringToInt(memoryRange[1]))) {
                throw new NumberFormatException();
            }
        }
        return memoryRange;
    }

    public boolean isGui() {
        return gui;
    }

    public boolean isSimulate() {
        return simulate;
    }

    public boolean isRv64() {
        return rv64;
    }

    public boolean isAssembleProject() {
        return assembleProject;
    }

    public boolean isCountInstructions() {
        return countInstructions;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public DisplayFormat getDisplayFormat() {
        return displayFormat;
    }

    public boolean isDisplayCopyright() {
        return displayCopyright;
    }

    public int getAssembleErrorExitCode() {
        return assembleErrorExitCode;
    }

    public int getSimulateErrorExitCode() {
        return simulateErrorExitCode;
    }

    public ArrayList<String[]> getDumpTriples() {
        return dumpTriples;
    }

    public ArrayList<String> getFilenameList() {
        return filenameList;
    }

    public ArrayList<String> getProgramArgumentList() {
        return programArgumentList;
    }

    public ArrayList<String> getRegisterDisplayList() {
        return registerDisplayList;
    }

    public ArrayList<String> getMemoryDisplayList() {
        return memoryDisplayList;
    }
}
