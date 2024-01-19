package com.github.unaimillan.rars;

import com.github.unaimillan.rars.api.Options;
import com.github.unaimillan.rars.riscv.dump.DumpFormat;
import com.github.unaimillan.rars.riscv.dump.DumpFormatLoader;
import com.github.unaimillan.rars.riscv.hardware.*;
import com.github.unaimillan.rars.util.Binary;
import com.github.unaimillan.rars.util.MemoryDump;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import picocli.CommandLine;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.IParameterConsumer;

public class Parser implements Runnable{
    @Mixin
    public Options options;

    @Parameters(parameterConsumer = IgnoreNumbers.class, description = "Files to execute. If more than one filename is listed, the first is assumed to be the main unless the global statement label 'main' is defined in one of the files. Exception handler not automatically assembled.  Add it to the file list. Options used here do not affect RARS Settings menu values and vice versa.")
    private File[] files = new File[0]; // calls 2 times with all arguments and without numbers. How to call only without numbers

    static class IgnoreNumbers implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec, CommandLine.Model.CommandSpec commandSpec) { //why evoked several times????
//            System.out.println(stack.size() + " " + stack.peek());
            File[] old = argSpec.getValue();
            List<File> files = new LinkedList<>(Arrays.asList(old));
            for (int i = stack.size() - 1; i >= 0; i--) {
                try { // should be unnecessary in future
                    Integer.parseInt(stack.get(i));
                    stack.remove(i);
                } catch (NumberFormatException ignore) {
                    File temp = new File(stack.get(i));
                    if (temp.exists() && !temp.isDirectory()) {
                        files.add(temp);
                        stack.remove(i);
                    }
                }
            }
            File[] newFiles = new File[files.size()];
            newFiles = files.toArray(newFiles);
            argSpec.setValue(newFiles);
//            System.out.println(argSpec);
        }
    }

    @Option(names = { "-h", "--help" }, usageHelp = true, description = "display a help message")
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

    @Option(paramLabel = "<arguments>", arity = "0..*", names = "--pa", description = "Program Arguments follow in a space-separated list. This option must be placed AFTER ALL FILE NAMES, because everything that follows it is interpreted as a program argument to be made available to the program at runtime.")
    private ArrayList<String> programArgumentList = new ArrayList<>(); // optional program args for program (becomes argc, argv)

    @Parameters(parameterConsumer = RangeConsumer.class, paramLabel = "<m>-<n>", description = "memory address range from <m> to <n> whose contents to display at end of run. <m> and <n> may be hex or decimal, must be on word boundary, <m> <= <n>.  Option may be repeated.") // Are you sure?
    private final ArrayList<String> memoryDisplayList = new ArrayList<>();

    static class RangeConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec, CommandLine.Model.CommandSpec commandSpec) {
            ArrayList<String> temp = argSpec.getValue();
            for(int i = stack.size() - 1; i >= 0; i--) {
                String arg = stack.get(i);
                String[] memoryRange = null;
                if (arg.indexOf(rangeSeparator) > 0 &&
                        arg.indexOf(rangeSeparator) < arg.length() - 1) { // -a -g --mc
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
                    temp.add(memoryRange[0]);
                    temp.add(memoryRange[1]);
                    stack.remove(i);
                }
            }
            argSpec.setValue(temp);
        }
    }

    @Parameters(parameterConsumer = regByNumConsumer.class, paramLabel = "x<reg>", description = "where <reg> is number or name (e.g. 5, t3, f10) of register whose content to display at end of run.  Option may be repeated.")
    private final ArrayList<String> registerByNumber = new ArrayList<>();

    static class regByNumConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec, CommandLine.Model.CommandSpec commandSpec) {
            ArrayList<String> temp = argSpec.getValue();
            for(int i = stack.size() - 1; i >= 0; i--) {
                String arg = stack.get(i);
                if (arg.indexOf("x") == 0) {
                    if (RegisterFile.getRegister(arg) == null &&
                            FloatingPointRegisterFile.getRegister(arg) == null) {
                        Launch.out.println("Invalid Register Name: " + arg); //shouldn't it throw error?
                    } else {
                        temp.add(arg);

                    }
                    stack.remove(i); //needed?
                }
            }
            argSpec.setValue(temp);
        }
    }

    @Parameters(parameterConsumer = regByNameConsumer.class, paramLabel = "<reg_name>", description = "where <reg_name> is name (e.g. t3, f10) of register whose content to display at end of run.  Option may be repeated.")
    private final ArrayList<String> registerByName = new ArrayList<>();

    static class regByNameConsumer implements IParameterConsumer {
        @Override
        public void consumeParameters(Stack<String> stack, CommandLine.Model.ArgSpec argSpec, CommandLine.Model.CommandSpec commandSpec) {
            ArrayList<String> temp = argSpec.getValue();
            for(int i = stack.size() - 1; i >= 0; i--) {
                String arg = stack.get(i);
                if (RegisterFile.getRegister(arg) != null ||
                        FloatingPointRegisterFile.getRegister(arg) != null) {
                    temp.add(arg);
                    stack.remove(i);
                }
            }
            argSpec.setValue(temp);
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
            description = "is case-sensitive and possible values are: Default for the default 32-bit address space, CompactDataAtZero for a 32KB memory with data segment at address 0, or CompactTextAtZero for a 32KB memory with text segment at address 0") String configName) throws Exception { //Maybe another one is needed
        MemoryConfiguration config = MemoryConfigurations.getConfigurationByName(configName);
        if (config == null) {
            throw new Exception("Invalid memory configuration: " + configName);
        } else {
            MemoryConfigurations.setCurrentConfiguration(config);
        }
    }

    @Command(name = "--dump", description = "memory dump of specified memory segment in specified format to specified file. Option may be repeated (not yet). Dump occurs at the end of simulation unless 'a' option is used.")
    private void dump(@Parameters(paramLabel = "<segment>", description = ".text, .data, or a range like 0x400000-0x10000000") String segment,
                      @Parameters(paramLabel = "<format>", description = "AsciiText, Binary, BinaryText, HexText, HEX, SegmentWindow") String format,
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

    public void parseCommandArgs() {
        new CommandLine(this)
                .setOptionsCaseInsensitive(true)
                .setSubcommandsCaseInsensitive(true)
                .execute(args);
        for (File file : files) {
            filenameList.add(file.getPath());
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Parse command line arguments.  The initial parsing has already been
    // done, since each space-separated argument is already in a String array
    // element.  Here, we check for validity, set switch variables as appropriate
    // and build data structures.  For help option (h), display the help.
    // Returns true if command args parse OK, false otherwise.
    public void XparseCommandArgs(Options options) throws Exception{ //ParameterException
        String noCopyrightSwitch = "nc";
        String displayMessagesToErrSwitch = "me";
        boolean inProgramArgumentList = false;
        programArgumentList = null;
        if (args.length == 0)
            return; // should not get here...
        // If the option to display RARS messages to standard erro is used,
        // it must be processed before any others (since messages may be
        // generated during option parsing).
        processDisplayMessagesToErrSwitch(args, displayMessagesToErrSwitch);
        processDisplayCopyright(args, noCopyrightSwitch);

        if (args.length == 1 && args[0].equals("h")) {
            displayHelp();
            System.exit(Globals.exitCode); // ???
        }
        for (int i = 0; i < args.length; i++) {
            // We have seen "pa" switch, so all remaining args are program args
            // that will become "argc" and "argv" for the program.
            if (inProgramArgumentList) {
                if (programArgumentList == null) {
                    programArgumentList = new ArrayList<>();
                }
                programArgumentList.add(args[i]);
                continue;
            }
            // Once we hit "pa", all remaining command args are assumed
            // to be program arguments.
            if (args[i].toLowerCase().equals("pa")) {
                inProgramArgumentList = true;
                continue;
            }
            // messages-to-standard-error switch already processed, so ignore.
            if (args[i].toLowerCase().equals(displayMessagesToErrSwitch)) {
                continue;
            }
            // no-copyright switch already processed, so ignore.
            if (args[i].toLowerCase().equals(noCopyrightSwitch)) {
                continue;
            }
            if (args[i].toLowerCase().equals("dump")) {
                if (args.length <= (i + 3)) {
                    throw new Exception("Dump command line argument requires a segment, format and file name.");
                } else {
                    if (dumpTriples == null)
                        dumpTriples = new ArrayList<>();
                    dumpTriples.add(new String[]{args[++i], args[++i], args[++i]});
                    //simulate = false;
                }
                continue;
            }
            if (args[i].toLowerCase().equals("mc")) {
                String configName = args[++i];
                MemoryConfiguration config = MemoryConfigurations.getConfigurationByName(configName);
                if (config == null) {
                    throw new Exception("Invalid memory configuration: " + configName);
                } else {
                    MemoryConfigurations.setCurrentConfiguration(config);
                }
                continue;
            }
            // Set RARS exit code for assemble error
            if (args[i].toLowerCase().indexOf("ae") == 0) {
                String s = args[i].substring(2);
                try {
                    assembleErrorExitCode = Integer.decode(s);
                    continue;
                } catch (NumberFormatException nfe) {
                    // Let it fall thru and get handled by catch-all
                }
            }
            // Set RARS exit code for simulate error
            if (args[i].toLowerCase().indexOf("se") == 0) {
                String s = args[i].substring(2);
                try {
                    simulateErrorExitCode = Integer.decode(s);
                    continue;
                } catch (NumberFormatException nfe) {
                    // Let it fall thru and get handled by catch-all
                }
            }
            if (args[i].toLowerCase().equals("d")) {
                Globals.debug = true;
                continue;
            }
            if (args[i].toLowerCase().equals("a")) {
                simulate = false;
                continue;
            }
            if (args[i].toLowerCase().equals("ad") ||
                    args[i].toLowerCase().equals("da")) {
                Globals.debug = true;
                simulate = false;
                continue;
            }
            if (args[i].toLowerCase().equals("p")) {
                assembleProject = true;
                continue;
            }
            if (args[i].toLowerCase().equals("dec")) {
                displayFormat = DisplayFormat.DECIMAL;
                continue;
            }
            if (args[i].toLowerCase().equals("g")) {
                gui = true;
                continue;
            }
            if (args[i].toLowerCase().equals("hex")) {
                displayFormat = DisplayFormat.HEXADECIMAL;
                continue;
            }
            if (args[i].toLowerCase().equals("ascii")) {
                displayFormat = DisplayFormat.ASCII;
                continue;
            }
            if (args[i].toLowerCase().equals("b")) {
                verbose = false;
                continue;
            }
            if (args[i].toLowerCase().equals("np") || args[i].toLowerCase().equals("ne")) {
                options.pseudo = false;
                continue;
            }
            if (args[i].toLowerCase().equals("we")) { // added 14-July-2008 DPS
                options.warningsAreErrors = true;
                continue;
            }
            if (args[i].toLowerCase().equals("sm")) { // added 17-Dec-2009 DPS
                options.startAtMain = true;
                continue;
            }
            if (args[i].toLowerCase().equals("smc")) { // added 5-Jul-2013 DPS
                options.selfModifyingCode = true;
                continue;
            }
            if (args[i].toLowerCase().equals("rv64")) {
                rv64 = true;
                continue;
            }
            if (args[i].toLowerCase().equals("ic")) { // added 19-Jul-2012 DPS
                countInstructions = true;
                continue;
            }

            if (new File(args[i]).exists()) {  // is it a file name?
                filenameList.add(args[i]);
                continue;
            }

            if (args[i].indexOf("x") == 0) {
                if (RegisterFile.getRegister(args[i]) == null &&
                        FloatingPointRegisterFile.getRegister(args[i]) == null) {
                    Launch.out.println("Invalid Register Name: " + args[i]);
                } else {
                    registerDisplayList.add(args[i]);
                }
                continue;
            }
            // check for register name w/o $.  added 14-July-2008 DPS
            if (RegisterFile.getRegister(args[i]) != null ||
                    FloatingPointRegisterFile.getRegister(args[i]) != null ||
                    ControlAndStatusRegisterFile.getRegister(args[i]) != null) {
                registerDisplayList.add(args[i]);
                continue;
            }
            // Check for stand-alone integer, which is the max execution steps option
            try {
                Integer.decode(args[i]);
                options.maxSteps = Integer.decode(args[i]); // if we got here, it has to be OK
                continue;
            } catch (NumberFormatException nfe) {
            }
            // Check for integer address range (m-n)
            try {
                String[] memoryRange = checkMemoryAddressRange(args[i]);
                memoryDisplayList.add(memoryRange[0]); // low end of range
                memoryDisplayList.add(memoryRange[1]); // high end of range
                continue;
            } catch (NumberFormatException nfe) {
                throw new Exception("Invalid/unaligned address or invalid range: " + args[i]);
            } catch (NullPointerException npe) {
                // Do nothing.  next statement will handle it
            }
            throw new Exception("Invalid Command Argument: " + args[i]);
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

    ///////////////////////////////////////////////////////////////////////
    //  If option to display RARS messages to standard err (System.err) is
    //  present, it must be processed before all others.  Since messages may
    //  be output as early as during the command parse.
    private void processDisplayMessagesToErrSwitch(String[] args, String displayMessagesToErrSwitch) {
        for (String arg : args) {
            if (arg.toLowerCase().equals(displayMessagesToErrSwitch)) {
                Launch.out = System.err;
                return;
            }
        }
    }

    private void processDisplayCopyright(String[] args, String noCopyrightSwitch) {
        for (String arg : args) {
            if (arg.toLowerCase().equals(noCopyrightSwitch)) {
                displayCopyright = false;
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////
    //  Display command line help text
    private void displayHelp() {
        String[] segmentNames = MemoryDump.getSegmentNames();
        StringBuilder segments = new StringBuilder();
        for (int i = 0; i < segmentNames.length; i++) {
            segments.append(segmentNames[i]);
            if (i < segmentNames.length - 1) {
                segments.append(", ");
            }
        }
        ArrayList<DumpFormat> dumpFormats = DumpFormatLoader.getDumpFormats();
        StringBuilder formats = new StringBuilder();
        for (int i = 0; i < dumpFormats.size(); i++) {
            formats.append(dumpFormats.get(i).getCommandDescriptor());
            if (i < dumpFormats.size() - 1) {
                formats.append(", ");
            }
        }
        Launch.out.println("Usage:  Rars  [options] filename [additional filenames]");
        Launch.out.println("  Valid options (not case sensitive, separate by spaces) are:");
        Launch.out.println("      a  -- assemble only, do not simulate");
        Launch.out.println("  ae<n>  -- terminate RARS with integer exit code <n> if an assemble error occurs.");
        Launch.out.println("  ascii  -- display memory or register contents interpreted as ASCII codes.");
        Launch.out.println("      b  -- brief - do not display register/memory address along with contents");
        Launch.out.println("      d  -- display RARS debugging statements");
        Launch.out.println("    dec  -- display memory or register contents in decimal.");
        Launch.out.println("   dump <segment> <format> <file> -- memory dump of specified memory segment");
        Launch.out.println("            in specified format to specified file.  Option may be repeated.");
        Launch.out.println("            Dump occurs at the end of simulation unless 'a' option is used.");
        Launch.out.println("            Segment and format are case-sensitive and possible values are:");
        Launch.out.println("            <segment> = " + segments+", or a range like 0x400000-0x10000000");
        Launch.out.println("            <format> = " + formats);
        Launch.out.println("      g  -- force GUI mode");
        Launch.out.println("      h  -- display this help.  Use by itself with no filename.");
        Launch.out.println("    hex  -- display memory or register contents in hexadecimal (default)");
        Launch.out.println("     ic  -- display count of basic instructions 'executed'");
        Launch.out.println("     mc <config>  -- set memory configuration.  Argument <config> is");
        Launch.out.println("            case-sensitive and possible values are: Default for the default");
        Launch.out.println("            32-bit address space, CompactDataAtZero for a 32KB memory with");
        Launch.out.println("            data segment at address 0, or CompactTextAtZero for a 32KB");
        Launch.out.println("            memory with text segment at address 0.");
        Launch.out.println("     me  -- display RARS messages to standard err instead of standard out. ");
        Launch.out.println("            Can separate messages from program output using redirection");
        Launch.out.println("     nc  -- do not display copyright notice (for cleaner redirected/piped output).");
        Launch.out.println("     np  -- use of pseudo instructions and formats not permitted");
        Launch.out.println("      p  -- Project mode - assemble all files in the same directory as given file.");
        Launch.out.println("  se<n>  -- terminate RARS with integer exit code <n> if a simulation (run) error occurs.");
        Launch.out.println("     sm  -- start execution at statement with global label main, if defined");
        Launch.out.println("    smc  -- Self Modifying Code - Program can write and branch to either text or data segment");
        Launch.out.println("    rv64 -- Enables 64 bit assembly and executables (Not fully compatible with rv32)");
        Launch.out.println("    <n>  -- where <n> is an integer maximum count of steps to simulate.");
        Launch.out.println("            If 0, negative or not specified, there is no maximum.");
        Launch.out.println(" x<reg>  -- where <reg> is number or name (e.g. 5, t3, f10) of register whose ");
        Launch.out.println("            content to display at end of run.  Option may be repeated.");
        Launch.out.println("<reg_name>  -- where <reg_name> is name (e.g. t3, f10) of register whose");
        Launch.out.println("            content to display at end of run.  Option may be repeated. ");
        Launch.out.println("<m>-<n>  -- memory address range from <m> to <n> whose contents to");
        Launch.out.println("            display at end of run. <m> and <n> may be hex or decimal,");
        Launch.out.println("            must be on word boundary, <m> <= <n>.  Option may be repeated.");
        Launch.out.println("     pa  -- Program Arguments follow in a space-separated list.  This");
        Launch.out.println("            option must be placed AFTER ALL FILE NAMES, because everything");
        Launch.out.println("            that follows it is interpreted as a program argument to be");
        Launch.out.println("            made available to the program at runtime.");
        Launch.out.println("If more than one filename is listed, the first is assumed to be the main");
        Launch.out.println("unless the global statement label 'main' is defined in one of the files.");
        Launch.out.println("Exception handler not automatically assembled.  Add it to the file list.");
        Launch.out.println("Options used here do not affect RARS Settings menu values and vice versa.");
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

    @Override
    public void run() {
        return;
    }
}
