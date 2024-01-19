package com.github.unaimillan.rars;

import com.formdev.flatlaf.FlatLightLaf;
import com.github.unaimillan.rars.api.Program;
import com.github.unaimillan.rars.riscv.InstructionSet;
import com.github.unaimillan.rars.riscv.dump.DumpFormat;
import com.github.unaimillan.rars.riscv.dump.DumpFormatLoader;
import com.github.unaimillan.rars.riscv.hardware.*;
import com.github.unaimillan.rars.simulator.Simulator;
import com.github.unaimillan.rars.util.Binary;
import com.github.unaimillan.rars.util.FilenameFinder;
import com.github.unaimillan.rars.util.MemoryDump;
import com.github.unaimillan.rars.venus.VenusUI;

import javax.swing.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;

import static java.lang.System.exit;

/*
Copyright (c) 2003-2012,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Launch the application
 *
 * @author Pete Sanderson
 * @version December 2009
 **/

public class Launch {

    /**
     * Main takes a number of command line arguments.<br>
     * Usage:  rars  [options] filename<br>
     * Valid options (not case sensitive, separate by spaces) are:<br>
     * a  -- assemble only, do not simulate<br>
     * ad  -- both a and d<br>
     * ae<n>  -- terminate RARS with integer exit code <n> if an assemble error occurs.<br>
     * ascii  -- display memory or register contents interpreted as ASCII
     * b  -- brief - do not display register/memory address along with contents<br>
     * d  -- print debugging statements<br>
     * da  -- both a and d<br>
     * dec  -- display memory or register contents in decimal.<br>
     * dump  -- dump memory contents to file.  Option has 3 arguments, e.g. <br>
     * <tt>dump &lt;segment&gt; &lt;format&gt; &lt;file&gt;</tt>.  Also supports<br>
     * an address range (see <i>m-n</i> below).  Current supported <br>
     * segments are <tt>.text</tt> and <tt>.data</tt>.  Current supported dump formats <br>
     * are <tt>Binary</tt>, <tt>HexText</tt>, <tt>BinaryText</tt>.<br>
     * g  -- force GUI mode
     * h  -- display help.  Use by itself and with no filename</br>
     * hex  -- display memory or register contents in hexadecimal (default)<br>
     * ic  -- display count of basic instructions 'executed'");
     * mc  -- set memory configuration.  Option has 1 argument, e.g.<br>
     * <tt>mc &lt;config$gt;</tt>, where &lt;config$gt; is <tt>Default</tt><br>
     * for the RARS default 32-bit address space, <tt>CompactDataAtZero</tt> for<br>
     * a 32KB address space with data segment at address 0, or <tt>CompactTextAtZero</tt><br>
     * for a 32KB address space with text segment at address 0.<br>
     * me  -- display RARS messages to standard err instead of standard out. Can separate via redirection.</br>
     * nc  -- do not display copyright notice (for cleaner redirected/piped output).</br>
     * np  -- No Pseudo-instructions allowed ("ne" will work also).<br>
     * p  -- Project mode - assemble all files in the same directory as given file.<br>
     * se<n>  -- terminate RARS with integer exit code <n> if a simulation (run) error occurs.<br>
     * sm  -- Start execution at Main - Execution will start at program statement globally labeled main.<br>
     * smc  -- Self Modifying Code - Program can write and branch to either text or data segment<br>
     * we  -- assembler Warnings will be considered Errors<br>
     * <n>  -- where <n> is an integer maximum count of steps to simulate.<br>
     * If 0, negative or not specified, there is no maximum.<br>
     * $<reg>  -- where <reg> is number or name (e.g. 5, t3, f10) of register whose <br>
     * content to display at end of run.  Option may be repeated.<br>
     * <reg_name>  -- where <reg_name> is name (e.g. t3, f10) of register whose <br>
     * content to display at end of run.  Option may be repeated. $ not required.<br>
     * <m>-<n>  -- memory address range from <m> to <n> whose contents to<br>
     * display at end of run. <m> and <n> may be hex or decimal,<br>
     * <m> <= <n>, both must be on word boundary.  Option may be repeated.<br>
     * pa  -- Program Arguments follow in a space-separated list.  This<br>
     * option must be placed AFTER ALL FILE NAMES, because everything<br>
     * that follows it is interpreted as a program argument to be<br>
     * made available to the program at runtime.<br>
     **/
    private static Parser parser;
    private static final int memoryWordsPerLine = 4; // display 4 memory words, tab separated, per line
    public static PrintStream out = System.out; // stream for display of command line output // Default: System.out // "me": System.err
    public static void main(String[] args){
        parser = new Parser(args);
        new Launch();
    }
    private Launch() {
        FlatLightLaf.setup();
        Globals.initialize();
        MemoryConfigurations.setCurrentConfiguration(MemoryConfigurations.getDefaultConfiguration());

        try {
            parser.parseCommandArgs();
        }catch (Exception e){ //ParameterException
            out.println(e.getMessage());
            exit(Globals.exitCode);
        }
        displayCopyright();
        
        if (parser.isGui()) {
            launchIDE();
        } else { // running from command line.
            // assure command mode works in headless environment (generates exception if not)
            System.setProperty("java.awt.headless", "true");
            
            dumpSegments(runCommand());
            exit(Globals.exitCode);
        }
    }

    private void displayAllPostMortem(Program program) {
        displayMiscellaneousPostMortem(program);
        displayRegistersPostMortem(program);
        displayMemoryPostMortem(program.getMemory());
    }
    /////////////////////////////////////////////////////////////
    // Perform any specified dump operations.  See "dump" option.
    //

    private void dumpSegments(Program program) {
        ArrayList<String[]> dumpTriples = parser.getDumpTriples();
        if (dumpTriples == null || program == null)
            return;

        for (String[] triple : dumpTriples) {
            File file = new File(triple[2]);
            Integer[] segInfo = MemoryDump.getSegmentBounds(triple[0]);
            // If not segment name, see if it is address range instead.  DPS 14-July-2008
            if (segInfo == null) {
                try {
                    String[] memoryRange = parser.checkMemoryAddressRange(triple[0]); //why checking again???
                    segInfo = new Integer[2];
                    segInfo[0] = Binary.stringToInt(memoryRange[0]); // low end of range
                    segInfo[1] = Binary.stringToInt(memoryRange[1]); // high end of range
                } catch (NumberFormatException | NullPointerException nfe) {
                    segInfo = null;
                }
            }
            if (segInfo == null) {
                out.println("Error while attempting to save dump, segment/address-range " + triple[0] + " is invalid!");
                continue;
            }
            DumpFormat format = DumpFormatLoader.findDumpFormatGivenCommandDescriptor(triple[1]);
            if (format == null) {
                out.println("Error while attempting to save dump, format " + triple[1] + " was not found!");
                continue;
            }
            try {
                int highAddress = program.getMemory().getAddressOfFirstNull(segInfo[0], segInfo[1]) - Memory.WORD_LENGTH_BYTES;
                if (highAddress < segInfo[0]) {
                    out.println("This segment has not been written to, there is nothing to dump.");
                    continue;
                }
                format.dumpMemoryRange(file, segInfo[0], highAddress, program.getMemory());
            } catch (FileNotFoundException e) {
                out.println("Error while attempting to save dump, file " + file + " was not found!");
            } catch (AddressErrorException e) {
                out.println("Error while attempting to save dump, file " + file + "!  Could not access address: " + e.getAddress() + "!");
            } catch (IOException e) {
                out.println("Error while attempting to save dump, file " + file + "!  Disk IO failed!");
            }
        }
    }


    /////////////////////////////////////////////////////////////////
    // There are no command arguments, so run in interactive mode by
    // launching the GUI-fronted integrated development environment.

    private void launchIDE() {
        // System.setProperty("apple.laf.useScreenMenuBar", "true"); // Puts RARS menu on Mac OS menu bar
        SwingUtilities.invokeLater(
                () -> {
                    //Turn off metal's use of bold fonts
                    //UIManager.put("swing.boldMetal", Boolean.FALSE);
                    new VenusUI("RARS " + Globals.version, parser.getFilenameList());
                });
    }



    //////////////////////////////////////////////////////////////////////
    // Carry out the rars command: assemble then optionally run
    // Returns false if no simulation (run) occurs, true otherwise.

    private Program runCommand() {
        ArrayList<String> filenameList = parser.getFilenameList();
        if (filenameList.isEmpty()) {
            return null;
        }

        Globals.getSettings().setBooleanSettingNonPersistent(Settings.Bool.RV64_ENABLED, parser.isRv64());
        InstructionSet.rv64 = parser.isRv64();
        Globals.instructionSet.populate();

        File mainFile = new File(filenameList.get(0)).getAbsoluteFile();// First file is "main" file
        ArrayList<String> filesToAssemble;
        if (parser.isAssembleProject()) {
            filesToAssemble = FilenameFinder.getFilenameList(mainFile.getParent(), Globals.fileExtensions);
            if (filenameList.size() > 1) {
                // Using "p" project option PLUS listing more than one filename on command line.
                // Add the additional files, avoiding duplicates.
                filenameList.remove(0); // first one has already been processed
                ArrayList<String> moreFilesToAssemble = FilenameFinder.getFilenameList(filenameList, FilenameFinder.MATCH_ALL_EXTENSIONS);
                // Remove any duplicates then merge the two lists.
                for (int index2 = 0; index2 < moreFilesToAssemble.size(); index2++) {
                    for (String s : filesToAssemble) {
                        if (s.equals(moreFilesToAssemble.get(index2))) {
                            moreFilesToAssemble.remove(index2);
                            index2--; // adjust for left shift in moreFilesToAssemble...
                            break;    // break out of inner loop...
                        }
                    }
                }
                filesToAssemble.addAll(moreFilesToAssemble);
            }
        } else {
            filesToAssemble = FilenameFinder.getFilenameList(filenameList, FilenameFinder.MATCH_ALL_EXTENSIONS);
        }
        Program program = new Program(parser.options);
        try {
            if (Globals.debug) {
                out.println("---  TOKENIZING & ASSEMBLY BEGINS  ---");
            }
            ErrorList warnings = program.assemble(filesToAssemble, mainFile.getAbsolutePath());
            if (warnings != null && warnings.warningsOccurred()) {
                out.println(warnings.generateWarningReport());
            }
        } catch (AssemblyException e) {
            Globals.exitCode = parser.getAssembleErrorExitCode();
            out.println(e.errors().generateErrorAndWarningReport());
            out.println("Processing terminated due to errors.");
            return null;
        }
        // Setup for program simulation even if just assembling to prepare memory dumps
        program.setup(parser.getProgramArgumentList(),null);
        if (parser.isSimulate()) {
            if (Globals.debug) {
                out.println("--------  SIMULATION BEGINS  -----------");
            }
            try {
                while (true) {
                    Simulator.Reason done = program.simulate();
                    if (done == Simulator.Reason.MAX_STEPS) {
                        out.println("\nProgram terminated when maximum step limit " + parser.options.maxSteps + " reached.");
                        break;
                    } else if (done == Simulator.Reason.CLIFF_TERMINATION) {
                        out.println("\nProgram terminated by dropping off the bottom.");
                        break;
                    } else if (done == Simulator.Reason.NORMAL_TERMINATION) {
                        out.println("\nProgram terminated by calling exit");
                        break;
                    }
                    assert done == Simulator.Reason.BREAKPOINT : "Internal error: All cases other than breakpoints should be handled already";
                    displayAllPostMortem(program); // print registers if we hit a breakpoint, then continue
                }

            } catch (SimulationException e) {
                Globals.exitCode = parser.getSimulateErrorExitCode();
                out.println(e.error().generateReport());
                out.println("Simulation terminated due to errors.");
            }
            displayAllPostMortem(program);
        }
        if (Globals.debug) {
            out.println("\n--------  ALL PROCESSING COMPLETE  -----------");
        }
        return program;
    }

    //////////////////////////////////////////////////////////////////////
    // Displays any specified runtime properties. Initially just instruction count
    // DPS 19 July 2012
    private void displayMiscellaneousPostMortem(Program program) {
        if (parser.isCountInstructions()) {
            out.println("\n" + program.getRegisterValue("cycle"));
        }
    }


    //////////////////////////////////////////////////////////////////////
    // Displays requested register or registers

    private void displayRegistersPostMortem(Program program) {
        // Display requested register contents
        for (String reg : parser.getRegisterDisplayList()) {
            if(FloatingPointRegisterFile.getRegister(reg) != null){
                //TODO: do something for double vs float
                // It isn't clear to me what the best behaviour is
                // floating point register
                int ivalue = program.getRegisterValue(reg);
                float fvalue = Float.intBitsToFloat(ivalue);
                if (parser.isVerbose()) {
                    out.print(reg + "\t");
                }
                if (parser.getDisplayFormat() == DisplayFormat.HEXADECIMAL) {
                    // display float (and double, if applicable) in hex
                    out.println(Binary.intToHexString(ivalue));

                } else if (parser.getDisplayFormat() == DisplayFormat.DECIMAL) {
                    // display float (and double, if applicable) in decimal
                    out.println(fvalue);

                } else { // displayFormat == ASCII
                    out.println(Binary.intToAscii(ivalue));
                }
            } else if (ControlAndStatusRegisterFile.getRegister(reg) != null){
                out.print(reg + "\t");
                if (formatIntForDisplay((int)ControlAndStatusRegisterFile.getRegister(reg).getValue()) == null) {
                    return;
                }
                out.println(formatIntForDisplay((int)ControlAndStatusRegisterFile.getRegister(reg).getValue()));
            } else if (parser.isVerbose()) {
                out.print(reg + "\t");
                out.println(formatIntForDisplay((int)RegisterFile.getRegister(reg).getValue()));
            }
        }
    }

    //////////////////////////////////////////////////////////////////////
    // Formats int value for display: decimal, hex, ascii
    private String formatIntForDisplay(int value) {
        return switch (parser.getDisplayFormat()) {
            case DECIMAL -> "" + value;
            case HEXADECIMAL -> Binary.intToHexString(value);
            case ASCII -> Binary.intToAscii(value);
        };
    }

    //////////////////////////////////////////////////////////////////////
    // Displays requested memory range or ranges

    private void displayMemoryPostMortem(Memory memory) {
        int value;
        // Display requested memory range contents
        Iterator<String> memIter = parser.getMemoryDisplayList().iterator();
        int addressStart = 0, addressEnd = 0;
        while (memIter.hasNext()) {
            try { // This will succeed; error would have been caught during command arg parse
                addressStart = Binary.stringToInt(memIter.next());
                addressEnd = Binary.stringToInt(memIter.next());
            } catch (NumberFormatException nfe) {
            }
            int valuesDisplayed = 0;
            for (int addr = addressStart; addr <= addressEnd; addr += Memory.WORD_LENGTH_BYTES) {
                if (addr < 0 && addressEnd > 0)
                    break;  // happens only if addressEnd is 0x7ffffffc
                if (valuesDisplayed % memoryWordsPerLine == 0) {
                    out.print((valuesDisplayed > 0) ? "\n" : "");
                    if (parser.isVerbose()) {
                        out.print("Mem[" + Binary.intToHexString(addr) + "]\t");
                    }
                }
                try {
                    // Allow display of binary text segment (machine code) DPS 14-July-2008
                    if (Memory.inTextSegment(addr)) {
                        Integer iValue = memory.getRawWordOrNull(addr);
                        value = (iValue == null) ? 0 : iValue;
                    } else {
                        value = memory.getWord(addr);
                    }
                    out.print(formatIntForDisplay(value) + "\t");
                } catch (AddressErrorException aee) {
                    out.print("Invalid address: " + addr + "\t");
                }
                valuesDisplayed++;
            }
            out.println();
        }
    }

    ///////////////////////////////////////////////////////////////////////
    //  Decide whether copyright should be displayed, and display
    //  if so.

    private void displayCopyright() {
        if (parser.isDisplayCopyright()) {
            out.println("RARS " + Globals.version + "  Copyright " + Globals.copyrightYears + " " + Globals.copyrightHolders + "\n");
        }
    }




}

   	
