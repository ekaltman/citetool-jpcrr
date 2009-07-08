/*
    JPC: A x86 PC Hardware Emulator for a pure Java Virtual Machine
    Release Version 2.0

    A project from the Physics Dept, The University of Oxford

    Copyright (C) 2007-2009 Isis Innovation Limited

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License version 2 as published by
    the Free Software Foundation.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

    Details (including contact information) can be found at:

    www-jpc.physics.ox.ac.uk
*/

package org.jpc.emulator;

import org.jpc.emulator.motherboard.*;
import org.jpc.emulator.memory.*;
import org.jpc.emulator.pci.peripheral.*;
import org.jpc.emulator.pci.*;
import org.jpc.emulator.peripheral.*;
import org.jpc.emulator.processor.*;
import org.jpc.support.*;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.logging.*;
import java.util.zip.*;
import org.jpc.emulator.memory.codeblock.CodeBlockManager;
import org.jpc.j2se.VirtualClock;

/**
 * This class represents the emulated PC as a whole, and holds references
 * to its main hardware components.
 * @author Chris Dennis
 * @author Ian Preston
 */
public class PC implements org.jpc.SRDumpable
{
    public static class PCHardwareInfo implements org.jpc.SRDumpable
    {
        byte[] biosID;
        byte[] vgaBIOSID;
        byte[] hdaID;
        byte[] hdbID;
        byte[] hdcID;
        byte[] hddID;
        DiskImageSet images;
        int initFDAIndex;
        int initFDBIndex;
        int initCDROMIndex;
        long initRTCTime;
        int cpuDivider;
        int memoryPages;
        DriveSet.BootType bootType;

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
        }    

        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": PCHardwareInfo:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            output.dumpArray(biosID);
            output.dumpArray(vgaBIOSID);
            output.dumpArray(hdaID);
            output.dumpArray(hdbID);
            output.dumpArray(hdcID);
            output.dumpArray(hddID);
            output.dumpObject(images);
            output.dumpInt(initFDAIndex);
            output.dumpInt(initFDBIndex);
            output.dumpInt(initCDROMIndex);
            output.dumpLong(initRTCTime);
            output.dumpInt(cpuDivider);
            output.dumpInt(memoryPages);
            output.dumpByte(DriveSet.BootType.toNumeric(bootType));
        }

        public PCHardwareInfo()
        {
            images = new DiskImageSet();
        }

        public PCHardwareInfo(org.jpc.support.SRLoader input) throws IOException
        {
            input.objectCreated(this);
            biosID = input.loadArrayByte();
            vgaBIOSID = input.loadArrayByte();
            hdaID = input.loadArrayByte();
            hdbID = input.loadArrayByte();
            hdcID = input.loadArrayByte();
            hddID = input.loadArrayByte();
            images = (DiskImageSet)input.loadObject();
            initFDAIndex = input.loadInt();
            initFDBIndex = input.loadInt();
            initCDROMIndex = input.loadInt();
            initRTCTime = input.loadLong();
            cpuDivider = input.loadInt();
            memoryPages = input.loadInt();
            bootType = DriveSet.BootType.fromNumeric(input.loadByte());
        }

        public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input, Integer id) throws IOException
        {
            org.jpc.SRDumpable x = new PCHardwareInfo(input);
            input.endObject();
            return x;
        }

        public void makeHWInfoSegment(org.jpc.support.SRDumper output) throws IOException
        {
            output.dumpArray(biosID);
            output.dumpArray(vgaBIOSID);
            output.dumpArray(hdaID);
            output.dumpArray(hdbID);
            output.dumpArray(hdcID);
            output.dumpArray(hddID);
            //TODO: When event recording becomes available, only save the disk images needed.
            int disks = 1 + images.highestDiskIndex();
            output.dumpInt(disks);
            for(int i = 0; i < disks; i++) {
                DiskImage disk = images.lookupDisk(i);
                if(disk == null)
                    output.dumpArray((byte[])null);
                else
                    output.dumpArray(disk.getImageID());
            }
            output.dumpInt(initFDAIndex);
            output.dumpInt(initFDBIndex);
            output.dumpInt(initCDROMIndex);
            output.dumpLong(initRTCTime);
            output.dumpByte((byte)(cpuDivider - 1));
            output.dumpInt(memoryPages);
            output.dumpByte(DriveSet.BootType.toNumeric(bootType));
            output.dumpInt(0);
        }

        public static PCHardwareInfo parseHWInfoSegment(org.jpc.support.SRLoader input) throws IOException
        {
            PCHardwareInfo hw = new PCHardwareInfo();
            hw.biosID = input.loadArrayByte();
            hw.vgaBIOSID = input.loadArrayByte();
            hw.hdaID = input.loadArrayByte();
            hw.hdbID = input.loadArrayByte();
            hw.hdcID = input.loadArrayByte();
            hw.hddID = input.loadArrayByte();
            hw.images = new DiskImageSet();
            int disks = input.loadInt();
            for(int i = 0; i < disks; i++) {
                String disk = new ImageLibrary.ByteArray(input.loadArrayByte()).toString();
                if(disk != null)
                    hw.images.addDisk(i, new DiskImage(disk, false));
            }
            hw.initFDAIndex = input.loadInt();
            hw.initFDBIndex = input.loadInt(); 
            hw.initCDROMIndex = input.loadInt();
            hw.initRTCTime = input.loadLong();
            hw.cpuDivider = 1 + ((int)input.loadByte() & 0xFF);
            hw.memoryPages = input.loadInt();
            hw.bootType = DriveSet.BootType.fromNumeric(input.loadByte());
            if(input.loadInt() != 0)
                throw new IOException("Unknown extension flags present.");
            return hw;            
        }
    }


    public int sysRAMSize;
    public int cpuClockDivider;
    private PCHardwareInfo hwInfo;
    public static final int INSTRUCTIONS_BETWEEN_INTERRUPTS = 1;

    public static volatile boolean compile = true;

    private static final Logger LOGGING = Logger.getLogger(PC.class.getName());

    private final Processor processor;
    private final PhysicalAddressSpace physicalAddr;
    private final LinearAddressSpace linearAddr;
    private final Clock vmClock;
    private final Set<HardwareComponent> parts;
    private final CodeBlockManager manager;
    private DiskImageSet images;

    private TraceTrap traceTrap;
    private boolean hitTraceTrap;
    private boolean tripleFaulted;

    /**
     * Constructs a new <code>PC</code> instance with the specified external time-source and
     * drive set.
     * @param clock <code>Clock</code> object used as a time source
     * @param drives drive set for this instance.
     * @throws java.io.IOException propogated from bios resource loading
     */
    public PC(Clock clock, DriveSet drives, int ramPages, int clockDivide, String sysBIOSImg, String vgaBIOSImg,
        long initTime, DiskImageSet images) throws IOException 
    {
        parts = new LinkedHashSet<HardwareComponent>();

        cpuClockDivider = clockDivide;
        sysRAMSize = ramPages * 4096;

        vmClock = clock;
        parts.add(vmClock);
        System.out.println("Creating CPU...");
        processor = new Processor(vmClock, cpuClockDivider);
        parts.add(processor);
        manager = new CodeBlockManager();

        System.out.println("Creating physical address space...");
        physicalAddr = new PhysicalAddressSpace(manager, sysRAMSize);
        parts.add(physicalAddr);

        System.out.println("Creating linear address space...");
        linearAddr = new LinearAddressSpace();
        parts.add(linearAddr);

        parts.add(drives);

        //Motherboard
        System.out.println("Creating I/O port handler...");
        parts.add(new IOPortHandler());
        System.out.println("Creating IRQ controller...");
        parts.add(new InterruptController());

        System.out.println("Creating primary DMA controller...");
        parts.add(new DMAController(false, true));
        System.out.println("Creating secondary DMA controller...");
        parts.add(new DMAController(false, false));

        System.out.println("Creating real time clock...");
        parts.add(new RTC(0x70, 8, sysRAMSize, initTime));
        System.out.println("Creating interval timer...");
        parts.add(new IntervalTimer(0x40, 0));
        System.out.println("Creating A20 Handler...");
        parts.add(new GateA20Handler());
        this.images = images;

        //Peripherals
        System.out.println("Creating IDE interface...");
        parts.add(new PIIX3IDEInterface());
        System.out.println("Creating VGA card...");
        parts.add(new DefaultVGACard());

        System.out.println("Creating Keyboard...");
        parts.add(new Keyboard());
        System.out.println("Creating floppy disk controller...");
        parts.add(new FloppyController());
        System.out.println("Creating PC speaker...");
        parts.add(new PCSpeaker());

        //PCI Stuff
        System.out.println("Creating PCI Host Bridge...");
        parts.add(new PCIHostBridge());
        System.out.println("Creating PCI-to-ISA Bridge...");
        parts.add(new PCIISABridge());
        System.out.println("Creating PCI Bus...");
        parts.add(new PCIBus());

        //BIOSes
        System.out.println("Creating system BIOS...");
        parts.add(new SystemBIOS(sysBIOSImg));
        System.out.println("Creating VGA BIOS...");
        parts.add(new VGABIOS(vgaBIOSImg));
        System.out.println("Creating trace trap...");
        parts.add(traceTrap = new TraceTrap());

        System.out.println("Creating hardware info...");
        hwInfo = new PCHardwareInfo();

        System.out.println("Configuring components...");
        if (!configure()) {
            throw new IllegalStateException("PC Configuration failed");
        }
        System.out.println("PC initialization done.");
    }

    public void dumpStatusPartial(org.jpc.support.StatusDumper output)
    {
        output.println("\tsysRAMSize " + sysRAMSize + " cpuClockDivider " + cpuClockDivider);
        output.println("\ttripleFaulted " + tripleFaulted);
        //hitTraceTrap not printed here.
        output.println("\tprocessor <object #" + output.objectNumber(processor) + ">"); if(processor != null) processor.dumpStatus(output);
        output.println("\tphysicalAddr <object #" + output.objectNumber(physicalAddr) + ">"); if(physicalAddr != null) physicalAddr.dumpStatus(output);
        output.println("\tlinearAddr <object #" + output.objectNumber(linearAddr) + ">"); if(linearAddr != null) linearAddr.dumpStatus(output);
        output.println("\tvmClock <object #" + output.objectNumber(vmClock) + ">"); if(vmClock != null) vmClock.dumpStatus(output);
        output.println("\timages <object #" + output.objectNumber(images) + ">"); if(images != null) images.dumpStatus(output);
        output.println("\ttraceTrap <object #" + output.objectNumber(traceTrap) + ">"); if(traceTrap != null) traceTrap.dumpStatus(output);
        output.println("\thwInfo <object #" + output.objectNumber(hwInfo) + ">"); if(hwInfo != null) hwInfo.dumpStatus(output);

        int i = 0;
        for (HardwareComponent part : parts) {
            output.println("\tparts[" + i + "] <object #" + output.objectNumber(part) + ">"); if(part != null) part.dumpStatus(output);
            i++;
        }
    }

    public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        org.jpc.SRDumpable x = new PC(input);
        input.endObject();
        return x;
    }

    public PC(org.jpc.support.SRLoader input) throws IOException
    {
        input.objectCreated(this);
        sysRAMSize = input.loadInt();
        cpuClockDivider = input.loadInt();
        processor = (Processor)input.loadObject();
        physicalAddr = (PhysicalAddressSpace)input.loadObject();
        linearAddr = (LinearAddressSpace)input.loadObject();
        vmClock = (Clock)input.loadObject();
        images = (DiskImageSet)(input.loadObject());
        traceTrap = (TraceTrap)input.loadObject();
        manager = (CodeBlockManager)input.loadObject();
        hwInfo = (PCHardwareInfo)(input.loadObject());
        hitTraceTrap = input.loadBoolean();
        tripleFaulted = input.loadBoolean();

        boolean present = input.loadBoolean();
        parts = new LinkedHashSet<HardwareComponent>();
        while(present) {
            parts.add((HardwareComponent)input.loadObject());
            present = input.loadBoolean();
        }
    }

    public void dumpStatus(org.jpc.support.StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": PC:");
        dumpStatusPartial(output);
        output.endObject();
    }

    public void dumpSR(org.jpc.support.SRDumper output) throws IOException
    {
        if(output.dumped(this))
            return;
        dumpSRPartial(output);
        output.endObject();
    }

    public PCHardwareInfo getHardwareInfo()
    {
        return hwInfo;
    }

    public boolean getAndClearTripleFaulted()
    {
        boolean flag = tripleFaulted;
        tripleFaulted = false;
        return flag;
    }


    public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
    {
        output.dumpInt(sysRAMSize);
        output.dumpInt(cpuClockDivider);
        output.dumpObject(processor);
        output.dumpObject(physicalAddr);
        output.dumpObject(linearAddr);
        output.dumpObject(vmClock);
        output.dumpObject(images);
        output.dumpObject(traceTrap);
        output.dumpObject(manager);
        output.dumpObject(hwInfo);
        output.dumpBoolean(hitTraceTrap);
        output.dumpBoolean(tripleFaulted);
        for (HardwareComponent part : parts) {
            output.dumpBoolean(true);
            output.dumpObject(part);
        }
        output.dumpBoolean(false);
    }

    public static PCHardwareInfo parseArgs(String[] args) throws IOException
    {
        PCHardwareInfo hw = new PCHardwareInfo();

        String sysBIOSImg = ArgProcessor.findVariable(args, "sysbios", "BIOS");
        hw.biosID = DiskImage.getLibrary().canonicalNameFor(sysBIOSImg);
        if(hw.biosID == null)
            throw new IOException("Can't find image \"" + sysBIOSImg + "\".");

        String vgaBIOSImg = ArgProcessor.findVariable(args, "vgabios", "VGABIOS");
        hw.vgaBIOSID = DiskImage.getLibrary().canonicalNameFor(vgaBIOSImg);
        if(hw.vgaBIOSID == null)
            throw new IOException("Can't find image \"" + vgaBIOSImg + "\".");

        String hdaImg = ArgProcessor.findVariable(args, "hda", null);
        hw.hdaID = DiskImage.getLibrary().canonicalNameFor(hdaImg);
        if(hw.hdaID == null && hdaImg != null)
            throw new IOException("Can't find image \"" + hdaImg + "\".");

        String hdbImg = ArgProcessor.findVariable(args, "hdb", null);
        hw.hdbID = DiskImage.getLibrary().canonicalNameFor(hdbImg);
        if(hw.hdbID == null && hdbImg != null)
            throw new IOException("Can't find image \"" + hdbImg + "\".");

        String hdcImg = ArgProcessor.findVariable(args, "hdc", null);
        hw.hdcID = DiskImage.getLibrary().canonicalNameFor(hdcImg);
        if(hw.hdcID == null && hdcImg != null)
            throw new IOException("Can't find image \"" + hdcImg + "\".");

        String hddImg = ArgProcessor.findVariable(args, "hdd", null);
        hw.hddID = DiskImage.getLibrary().canonicalNameFor(hddImg);
        if(hw.hddID == null && hddImg != null)
            throw new IOException("Can't find image \"" + hddImg + "\".");

        String cdRomFileName = ArgProcessor.findVariable(args, "-cdrom", null);
        if (cdRomFileName != null) {
            hw.initCDROMIndex = hw.images.addDisk(new DiskImage(cdRomFileName, false));
        } else
            hw.initCDROMIndex = -1;

        String fdaFileName = ArgProcessor.findVariable(args, "-fda", null);
        if(fdaFileName != null) {
            hw.initFDAIndex = hw.images.addDisk(new DiskImage(fdaFileName, false));
        } else
            hw.initFDAIndex = -1;

        String fdbFileName = ArgProcessor.findVariable(args, "-fdb", null);
        if(fdbFileName != null) {
            hw.initFDBIndex = hw.images.addDisk(new DiskImage(fdbFileName, false));
        } else
            hw.initFDBIndex = -1;

        String initTimeS = ArgProcessor.findVariable(args, "inittime", null);
        long initTime;
        try {
            hw.initRTCTime = Long.parseLong(initTimeS, 10);
            if(hw.initRTCTime < 0 || hw.initRTCTime > 4102444799999L)
               throw new Exception("Invalid time value.");
        } catch(Exception e) { 
            if(initTimeS != null)
                System.err.println("Invalid -inittime. Using default value of 1 000 000 000 000.");
            hw.initRTCTime = 1000000000000L;
        }

        String cpuDividerS = ArgProcessor.findVariable(args, "cpudivider", "25");
        try {
            hw.cpuDivider = Integer.parseInt(cpuDividerS, 10);
            if(hw.cpuDivider < 1 || hw.cpuDivider > 256)
               throw new Exception("Invalid CPU divider value.");
        } catch(Exception e) { 
            if(cpuDividerS != null)
                System.err.println("Invalid -cpudivider. Using default value of 25.");
            hw.cpuDivider = 25;
        }

        String memoryPagesS = ArgProcessor.findVariable(args, "memsize", "4096");
        try {
            hw.memoryPages = Integer.parseInt(memoryPagesS, 10);
            if(hw.memoryPages < 256 || hw.memoryPages > 262144)
               throw new Exception("Invalid memory size value.");
        } catch(Exception e) { 
            if(memoryPagesS != null)
                System.err.println("Invalid -memsize. Using default value of 4096.");
            hw.memoryPages = 4096;
        }

        String bootArg = ArgProcessor.findVariable(args, "-boot", "fda");
        bootArg = bootArg.toLowerCase();
        if (bootArg.equals("fda"))
            hw.bootType = DriveSet.BootType.FLOPPY;
        else if (bootArg.equals("hda"))
            hw.bootType = DriveSet.BootType.HARD_DRIVE;
        else if (bootArg.equals("cdrom"))
            hw.bootType = DriveSet.BootType.CDROM;

        return hw;
    }

    private static GenericBlockDevice blockdeviceFor(String name) throws IOException
    {
        if(name == null)
            return null;
        return new GenericBlockDevice(new DiskImage(name, false));
    }

    private static String arrayToString(byte[] array) throws IOException
    {
        if(array == null)
            return null;
        return (new ImageLibrary.ByteArray(array)).toString();
    }

    public static PC createPC(PCHardwareInfo hw, Clock clock) throws IOException
    {
        PC pc;
        String biosID = arrayToString(hw.biosID);
        String vgaBIOSID = arrayToString(hw.vgaBIOSID);
        BlockDevice hda = blockdeviceFor(arrayToString(hw.hdaID));
        BlockDevice hdb = blockdeviceFor(arrayToString(hw.hdbID));
        BlockDevice hdc = blockdeviceFor(arrayToString(hw.hdcID));
        BlockDevice hdd = blockdeviceFor(arrayToString(hw.hddID));

        DriveSet drives = new DriveSet(hw.bootType, hda, hdb, hdc, hdd);
        pc = new PC(clock, drives, hw.memoryPages, hw.cpuDivider, biosID, vgaBIOSID, hw.initRTCTime, hw.images);
        FloppyController fdc = (FloppyController)pc.getComponent(FloppyController.class);

        DiskImage img1 = pc.getDisks().lookupDisk(hw.initFDAIndex);
        BlockDevice device1 = new GenericBlockDevice(img1, BlockDevice.Type.FLOPPY);
        fdc.changeDisk(device1, 0);

        DiskImage img2 = pc.getDisks().lookupDisk(hw.initFDBIndex);
        BlockDevice device2 = new GenericBlockDevice(img2, BlockDevice.Type.FLOPPY);
        fdc.changeDisk(device2, 1);

        PCHardwareInfo hw2 = pc.getHardwareInfo();
        hw2.biosID = hw.biosID;
        hw2.vgaBIOSID = hw.vgaBIOSID;
        hw2.hdaID = hw.hdaID;
        hw2.hdbID = hw.hdbID;
        hw2.hdcID = hw.hdcID;
        hw2.hddID = hw.hddID;
        hw2.images = hw.images;
        hw2.initFDAIndex = hw.initFDAIndex;
        hw2.initFDBIndex = hw.initFDBIndex;
        hw2.initCDROMIndex = hw.initCDROMIndex;
        hw2.initRTCTime = hw.initRTCTime;
        hw2.cpuDivider = hw.cpuDivider;
        hw2.memoryPages = hw.memoryPages;
        hw2.bootType = hw.bootType;

        return pc;
    }

    /**
     * Starts this PC's attached clock instance.
     */
    public void start() {
        vmClock.resume();
    }

    /**
     * Stops this PC's attached clock instance
     */
    public void stop() {
        vmClock.pause();
    }

    /**
     * Inserts the specified floppy disk into the drive identified.
     * @param disk new floppy disk to be inserted.
     * @param index drive which the disk is inserted into.
     */
    public void changeFloppyDisk(org.jpc.support.BlockDevice disk, int index) {
        ((FloppyController) getComponent(FloppyController.class)).changeDisk(disk, index);
    }

    public DiskImageSet getDisks()
    {
        return images;
    }

    private boolean configure() {
        boolean fullyInitialised;
        int count = 0;
        do {
            fullyInitialised = true;
            for (HardwareComponent outer : parts) {
                if (outer.initialised()) {
                    continue;
                }

                for (HardwareComponent inner : parts) {
                    outer.acceptComponent(inner);
                }

                fullyInitialised &= outer.initialised();
            }
            count++;
        } while ((fullyInitialised == false) && (count < 100));

        if (!fullyInitialised) {
            StringBuilder sb = new StringBuilder("pc >> component configuration errors\n");
            List<HardwareComponent> args = new ArrayList<HardwareComponent>();
            for (HardwareComponent hwc : parts) {
                if (!hwc.initialised()) {
                    sb.append("component {" + args.size() + "} not configured");
                    args.add(hwc);
                }
            }

            LOGGING.log(Level.WARNING, sb.toString(), args.toArray());
            return false;
        }

        for (HardwareComponent hwc : parts) {
            if (hwc instanceof PCIBus) {
                ((PCIBus) hwc).biosInit();
            }
        }

        return true;
    }

    /**
     * Reset this PC back to its initial state.
     * <p>
     * This is roughly equivalent to a hard-reset (power down-up cycle).
     */
    public void reset() {
        for (HardwareComponent hwc : parts) {
            hwc.reset();
        }
        configure();
    }

    /**
     * Get an subclass of <code>cls</code> from this instance's parts list.
     * <p>
     * If <code>cls</code> is not assignment compatible with <code>HardwareComponent</code>
     * then this method will return null immediately.
     * @param cls component type required.
     * @return an instance of class <code>cls</code>, or <code>null</code> on failure
     */
    public HardwareComponent getComponent(Class<? extends HardwareComponent> cls) {
        if (!HardwareComponent.class.isAssignableFrom(cls)) {
            return null;
        }

        for (HardwareComponent hwc : parts) {
            if (cls.isInstance(hwc)) {
                return hwc;
            }
        }
        return null;
    }

    /**
     * Gets the processor instance associated with this PC.
     * @return associated processor instance.
     */
    public Processor getProcessor() {
        return processor;
    }

    /**
     * Execute an arbitrarily large amount of code on this instance.
     * <p>
     * This method will execute continuously until there is either a mode switch,
     * or a unspecified large number of instructions have completed.  It should
     * never run indefinitely.
     * @return total number of x86 instructions executed.
     */
    public final int execute() {

        if (processor.isProtectedMode()) {
            if (processor.isVirtual8086Mode()) {
                return executeVirtual8086();
            } else {
                return executeProtected();
            }
        } else {
            return executeReal();
        }
    }

    public final int executeReal()
    {
        int x86Count = 0;
        int clockx86Count = 0;
        int nextClockCheck = INSTRUCTIONS_BETWEEN_INTERRUPTS;
        try
        {
            for (int i = 0; i < 100; i++)
            {
                int block;
                try {
                    block = physicalAddr.executeReal(processor, processor.getInstructionPointer());
                } catch(org.jpc.emulator.processor.Processor.TripleFault e) {
                    reset();      //Reboot the system to get the CPU back online.
                    hitTraceTrap = true;
                    tripleFaulted = true;
                    break;
                } 
                x86Count += block;
                clockx86Count += block;
                processor.instructionsExecuted += block;
                if (clockx86Count > nextClockCheck)
                {
                    nextClockCheck = x86Count + INSTRUCTIONS_BETWEEN_INTERRUPTS;
                    processor.processRealModeInterrupts(clockx86Count);
                    clockx86Count = 0;
                }
                if(traceTrap.getAndClearTrapActive()) {
                    hitTraceTrap = true;
                    break;
                }
            }
        } catch (ProcessorException p) {
             processor.handleRealModeException(p);
        }
        catch (ModeSwitchException e)
        {
            LOGGING.log(Level.FINE, "Switching mode", e);
        }
        return x86Count;
    }

    public TraceTrap getTraceTrap()
    {
        return traceTrap;
    }

    public boolean getHitTraceTrap()
    {
        boolean tmp = hitTraceTrap;
        hitTraceTrap = false;
        return tmp;
    }

    public final int executeProtected() {
        int x86Count = 0;
        int clockx86Count = 0;
        int nextClockCheck = INSTRUCTIONS_BETWEEN_INTERRUPTS;
        try
        {
            for (int i = 0; i < 100; i++)
            {
                int block;
                try {
                    block= linearAddr.executeProtected(processor, processor.getInstructionPointer());
                } catch(org.jpc.emulator.processor.Processor.TripleFault e) {
                    reset();      //Reboot the system to get the CPU back online.
                    hitTraceTrap = true;
                    tripleFaulted = true;
                    break;
                } 
                x86Count += block;
                clockx86Count += block;
                processor.instructionsExecuted += block;
                if (clockx86Count > nextClockCheck)
                {
                    nextClockCheck = x86Count + INSTRUCTIONS_BETWEEN_INTERRUPTS;
                    processor.processProtectedModeInterrupts(clockx86Count);
                    clockx86Count = 0;
                }
                if(traceTrap.getAndClearTrapActive()) {
                    hitTraceTrap = true;
                    break;
                }
            }
        } catch (ProcessorException p) {
                processor.handleProtectedModeException(p);
        }
        catch (ModeSwitchException e)
        {
            LOGGING.log(Level.FINE, "Switching mode", e);
        }
        return x86Count;
    }

    public final int executeVirtual8086() {
        int x86Count = 0;
        int clockx86Count = 0;
        int nextClockCheck = INSTRUCTIONS_BETWEEN_INTERRUPTS;
        try
        {
            for (int i = 0; i < 100; i++)
            {
                int block;
                try {
                    block = linearAddr.executeVirtual8086(processor, processor.getInstructionPointer());
                } catch(org.jpc.emulator.processor.Processor.TripleFault e) {
                    reset();      //Reboot the system to get the CPU back online.
                    hitTraceTrap = true;
                    tripleFaulted = true;
                    break;
                } 
                x86Count += block;
                clockx86Count += block;
                processor.instructionsExecuted += block;
                if (clockx86Count > nextClockCheck)
                {
                    nextClockCheck = x86Count + INSTRUCTIONS_BETWEEN_INTERRUPTS;
                    processor.processVirtual8086ModeInterrupts(clockx86Count);
                    clockx86Count = 0;
                }
                if(traceTrap.getAndClearTrapActive()) {
                    hitTraceTrap = true;
                    break;
                }
            }
        }
        catch (ProcessorException p)
        {
            processor.handleVirtual8086ModeException(p);
        }
        catch (ModeSwitchException e)
        {
            LOGGING.log(Level.FINE, "Switching mode", e);
        }
        return x86Count;
    }
}
