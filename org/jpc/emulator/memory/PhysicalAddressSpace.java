/*
    JPC: A x86 PC Hardware Emulator for a pure Java Virtual Machine
    Release Version 2.0

    A project from the Physics Dept, The University of Oxford

    Copyright (C) 2007 Isis Innovation Limited

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

    www.physics.ox.ac.uk/jpc
*/

package org.jpc.emulator.memory;

import java.util.*;
import java.io.*;

import org.jpc.emulator.*;
import org.jpc.emulator.memory.codeblock.*;
import org.jpc.emulator.processor.Processor;

public final class PhysicalAddressSpace extends AddressSpace implements HardwareComponent
{
    private static final int GATEA20_MASK = 0xffefffff;
    private static final int GATEA20_PAGEMASK = GATEA20_MASK >> INDEX_SHIFT;
    private static final int GATEA20_PAGEOFFSET = (~GATEA20_MASK) >> INDEX_SHIFT;

    private int sysRamSize;
    private int quickIndexSize;

    private static final int TOP_INDEX_BITS = (32 - INDEX_SHIFT) / 2;
    private static final int BOTTOM_INDEX_BITS = 32 - INDEX_SHIFT - TOP_INDEX_BITS;

    private static final int TOP_INDEX_SHIFT = 32 - TOP_INDEX_BITS;
    private static final int TOP_INDEX_SIZE = 1 << TOP_INDEX_BITS;
    private static final int TOP_INDEX_MASK = TOP_INDEX_SIZE - 1;

    private static final int BOTTOM_INDEX_SHIFT = 32 - TOP_INDEX_BITS - BOTTOM_INDEX_BITS;
    private static final int BOTTOM_INDEX_SIZE = 1 << BOTTOM_INDEX_BITS;
    private static final int BOTTOM_INDEX_MASK = BOTTOM_INDEX_SIZE - 1;

    private boolean gateA20MaskState;
    private int mappedRegionCount;

    private Memory[] quickNonA20MaskedIndex, quickA20MaskedIndex, quickIndex;
    private Memory[][] nonA20MaskedIndex, a20MaskedIndex, index;

    public static final Memory UNCONNECTED = new UnconnectedMemoryBlock();

    private LinearAddressSpace linearAddr;

    public PhysicalAddressSpace(int ramSize)
    {
        mappedRegionCount = 0;

        sysRamSize = ramSize;
        quickIndexSize = ramSize >>> INDEX_SHIFT;

        quickNonA20MaskedIndex = new Memory[quickIndexSize];
        clearArray(quickNonA20MaskedIndex, UNCONNECTED);
        quickA20MaskedIndex = new Memory[quickIndexSize];
        clearArray(quickA20MaskedIndex, UNCONNECTED);

        nonA20MaskedIndex = new Memory[TOP_INDEX_SIZE][];
        a20MaskedIndex = new Memory[TOP_INDEX_SIZE][];

        setGateA20State(false);
    }

    public void dumpStatusPartial(org.jpc.support.StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tsysRamSize " + sysRamSize + " quickIndexSize " + quickIndexSize);
        output.println("\tgateA20MaskState " + gateA20MaskState + " mappedRegionCount " + mappedRegionCount);

        dumpMemoryTableStatus(output, quickNonA20MaskedIndex, "quickNonA20MaskedIndex");
        dumpMemoryTableStatus(output, quickA20MaskedIndex, "quickA20MaskedIndex");
        dumpMemoryTableStatus(output, quickIndex, "quickIndex");
        dumpMemoryDTableStatus(output, nonA20MaskedIndex, "nonA20MaskedIndex");
        dumpMemoryDTableStatus(output, a20MaskedIndex, "a20MaskedIndex");
        dumpMemoryDTableStatus(output, index, "index");
        output.println("\tUNCONNECTED <object #" + output.objectNumber(UNCONNECTED) + ">"); if(UNCONNECTED != null) UNCONNECTED.dumpStatus(output);
        output.println("\tlinearAddr <object #" + output.objectNumber(linearAddr) + ">"); if(linearAddr != null) linearAddr.dumpStatus(output);
    }
 
    public void dumpStatus(org.jpc.support.StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": PhysicalAddressSpace:");
        dumpStatusPartial(output);
        output.endObject();
    }

    public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        org.jpc.SRDumpable x = new PhysicalAddressSpace(input);
        input.endObject();
        return x;
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
        super.dumpSRPartial(output);
        output.specialObject(UNCONNECTED);
        output.dumpInt(sysRamSize);
        output.dumpInt(quickIndexSize);
        output.dumpBoolean(gateA20MaskState);
        output.dumpInt(mappedRegionCount);
        dumpMemoryTableSR(output, quickNonA20MaskedIndex);
        dumpMemoryDTableSR(output, nonA20MaskedIndex);
        output.dumpObject(linearAddr);
    }

    private void reconstructA20MaskedTables()
    {
        a20MaskedIndex = new Memory[TOP_INDEX_SIZE][];
        quickA20MaskedIndex = new Memory[quickIndexSize];
        clearArray(quickA20MaskedIndex, UNCONNECTED);
        for(int i = 0; i < quickIndexSize; i++)
            quickA20MaskedIndex[i] = quickNonA20MaskedIndex[i & GATEA20_PAGEMASK];
        for(int i = 0; i < TOP_INDEX_SIZE; i++) {
            if(nonA20MaskedIndex[i] == null)
                continue;
            for(int j = 0; j < BOTTOM_INDEX_SIZE; j++) {
                if(nonA20MaskedIndex[i][j] == null)
                    continue;
                int pageNo1 = i * BOTTOM_INDEX_SIZE + j;
                int pageNo2 = i * BOTTOM_INDEX_SIZE + j + GATEA20_PAGEOFFSET;
                Memory[] group1;
                Memory[] group2;
                if((pageNo1 & GATEA20_PAGEMASK) != pageNo1)
                    continue;

                if((group1 = a20MaskedIndex[pageNo1 >>> BOTTOM_INDEX_BITS]) == null)
                    group1 = a20MaskedIndex[pageNo1 >>> BOTTOM_INDEX_BITS] = new Memory[BOTTOM_INDEX_SIZE];
                if((group2 = a20MaskedIndex[pageNo2 >>> BOTTOM_INDEX_BITS]) == null)
                    group2 = a20MaskedIndex[pageNo2 >>> BOTTOM_INDEX_BITS] = new Memory[BOTTOM_INDEX_SIZE];
                    
                group1[pageNo1 & BOTTOM_INDEX_MASK] = nonA20MaskedIndex[i][j];
                group2[pageNo2 & BOTTOM_INDEX_MASK] = nonA20MaskedIndex[i][j];
            }
        }
    }

    public PhysicalAddressSpace(org.jpc.support.SRLoader input) throws IOException
    {
        super(input);
        input.specialObject(UNCONNECTED);
        sysRamSize = input.loadInt();
        quickIndexSize = input.loadInt();
        gateA20MaskState = input.loadBoolean();
        mappedRegionCount = input.loadInt();
        quickNonA20MaskedIndex = loadMemoryTableSR(input);
        nonA20MaskedIndex = loadMemoryDTableSR(input);
        reconstructA20MaskedTables();
        if (gateA20MaskState) {
            quickIndex = quickNonA20MaskedIndex;
            index = nonA20MaskedIndex;
        } else {
            quickIndex = quickA20MaskedIndex;
            index = a20MaskedIndex;
        }

        linearAddr = (LinearAddressSpace)(input.loadObject());
    }

    private Memory[][] loadMemoryDTableSR(org.jpc.support.SRLoader input) throws IOException
    {
        boolean dTablePresent = input.loadBoolean();
        if(!dTablePresent)
            return null;

        Memory[][] mem = new Memory[input.loadInt()][];
        for(int i = 0; i < mem.length; i++)
            mem[i] = loadMemoryTableSR(input);            
        return mem;
    }

    private Memory[] loadMemoryTableSR(org.jpc.support.SRLoader input) throws IOException
    {
        boolean dTablePresent = input.loadBoolean();
        if(!dTablePresent)
            return null;

        Memory[] mem = new Memory[input.loadInt()];
        for(int i = 0; i < mem.length; i++)
            mem[i] = (Memory)(input.loadObject());
        return mem;
    }

    private void dumpMemoryDTableSR(org.jpc.support.SRDumper output, Memory[][] mem) throws IOException
    {
        if(mem == null) {
            output.dumpBoolean(false);
        } else {
            output.dumpBoolean(true);
            output.dumpInt(mem.length);
            for(int i = 0; i < mem.length; i++)
                dumpMemoryTableSR(output, mem[i]);            
        }
    }

    private void dumpMemoryTableSR(org.jpc.support.SRDumper output, Memory[] mem) throws IOException
    {
        if(mem == null) {
            output.dumpBoolean(false);
        } else {
            output.dumpBoolean(true);
            output.dumpInt(mem.length);
            for(int i = 0; i < mem.length; i++)
                output.dumpObject(mem[i]);            
        }
    }

    private void dumpMemoryTableStatus(org.jpc.support.StatusDumper output, Memory[] mem, String name)
    {
        if(mem == null) {
            output.println("\t" + name +" null");
        } else {
            for(int i = 0; i < mem.length; i++) {
                output.println("\t" + name + "[" + i + "] <object #" + output.objectNumber(mem[i]) + ">"); if(mem[i] != null) mem[i].dumpStatus(output);
            }
        }
    }

    private void dumpMemoryDTableStatus(org.jpc.support.StatusDumper output, Memory[][] mem, String name)
    {
        if(mem == null) {
            output.println("\t" + name +": null");
        } else {
            for(int i = 0; i < mem.length; i++)
                if(mem[i] != null)
                    for(int j = 0; j < mem.length; j++) {
                        output.println("\t" + name + "[" + i + "][" + j + "] <object #" + output.objectNumber(mem[i][j]) + ">"); if(mem[i][j] != null) mem[i][j].dumpStatus(output);
                    }
                else
                        output.println("\t" + name + "[" + i + "] null");
        }
    }

    public void setGateA20State(boolean value)
    {
        gateA20MaskState = value;
        if (value) {
            quickIndex = quickNonA20MaskedIndex;
            index = nonA20MaskedIndex;
        } else {
            quickIndex = quickA20MaskedIndex;
            index = a20MaskedIndex;
        }

        if ((linearAddr != null) && !linearAddr.pagingDisabled())
            linearAddr.flush();
    }

    public boolean getGateA20State()
    {
        return gateA20MaskState;
    }

    public int getAllocatedBufferSize()
    {
        return mappedRegionCount * BLOCK_SIZE;
    }

    public Memory getReadMemoryBlockAt(int offset)
    {
        return getMemoryBlockAt(offset);
    }

    public Memory getWriteMemoryBlockAt(int offset)
    {
        return getMemoryBlockAt(offset);
    }

    public int execute(Processor cpu, int offset)
    {
        return getReadMemoryBlockAt(offset).execute(cpu, offset & AddressSpace.BLOCK_MASK);
    }

    public CodeBlock decodeCodeBlockAt(Processor cpu, int offset)
    {
        CodeBlock block=getReadMemoryBlockAt(offset).decodeCodeBlockAt(cpu, offset & AddressSpace.BLOCK_MASK);
        return block;

    }

    void replaceBlocks(Memory oldBlock, Memory newBlock)
    {
        for (int i = 0; i < quickA20MaskedIndex.length; i++)
            if (quickA20MaskedIndex[i] == oldBlock) quickA20MaskedIndex[i] = newBlock;

        for (int i = 0; i < quickNonA20MaskedIndex.length; i++)
            if (quickNonA20MaskedIndex[i] == oldBlock) quickNonA20MaskedIndex[i] = newBlock;

        for (int i = 0; i < a20MaskedIndex.length; i++) {
            Memory[] subArray = a20MaskedIndex[i];
            try {
                for (int j = 0; j < subArray.length; j++)
                    if (subArray[j] == oldBlock) subArray[j] = newBlock;
            } catch (NullPointerException e) {}
        }

        for (int i = 0; i < nonA20MaskedIndex.length; i++) {
            Memory[] subArray = nonA20MaskedIndex[i];
            try {
                for (int j = 0; j < subArray.length; j++)
                    if (subArray[j] == oldBlock) subArray[j] = newBlock;
            } catch (NullPointerException e) {}
        }
    }

    public static class MapWrapper extends Memory
    {
        private Memory memory;
        private int baseAddress;

        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
            output.dumpObject(memory);
            output.dumpInt(baseAddress);
        }

        public MapWrapper(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
            memory = (Memory)(input.loadObject());
            baseAddress = input.loadInt();
        }

        public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input, Integer id) throws IOException
        {
            org.jpc.SRDumpable x = new MapWrapper(input);
            input.endObject();
            return x;
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
            output.println("\tbaseAddress " + baseAddress);
            output.println("\tmemory <object #" + output.objectNumber(memory) + ">"); if(memory != null) memory.dumpStatus(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": MapWrapper:");
            dumpStatusPartial(output);
            output.endObject();
        }

        MapWrapper(Memory mem, int base)
        {
            baseAddress = base;
            memory = mem;
        }

        public long getSize()
        {
            return BLOCK_SIZE;
        }

        public void clear()
        {
            memory.clear(baseAddress, (int)getSize());
        }

        public void clear(int start, int length)
        {
            if (start + length > getSize()) throw new ArrayIndexOutOfBoundsException("Attempt to clear outside of memory bounds");
            start = baseAddress | start;
            memory.clear(start, length);
        }

        public void copyContentsInto(int offset, byte[] buffer, int off, int len)
        {
            offset = baseAddress | offset;
            memory.copyContentsInto(offset, buffer, off, len);
        }

        public void copyContentsFrom(int offset, byte[] buffer, int off, int len)
        {
            offset = baseAddress | offset;
            memory.copyContentsFrom(offset, buffer, off, len);
        }

        public byte getByte(int offset)
        {
            offset = baseAddress | offset;
            return memory.getByte(offset);
        }

        public short getWord(int offset)
        {
            offset = baseAddress | offset;
            return memory.getWord(offset);
        }

        public int getDoubleWord(int offset)
        {
            offset = baseAddress | offset;
            return memory.getDoubleWord(offset);
        }

        public long getQuadWord(int offset)
        {
            offset = baseAddress | offset;
            return memory.getQuadWord(offset);
        }

        public long getLowerDoubleQuadWord(int offset)
        {
            offset = baseAddress | offset;
            return memory.getQuadWord(offset);
        }

        public long getUpperDoubleQuadWord(int offset)
        {
            offset += 8;
            offset = baseAddress | offset;
            return memory.getQuadWord(offset);
        }

        public void setByte(int offset, byte data)
        {
            offset = baseAddress | offset;
            memory.setByte(offset, data);
        }

        public void setWord(int offset, short data)
        {
            offset = baseAddress | offset;
            memory.setWord(offset, data);
        }

        public void setDoubleWord(int offset, int data)
        {
            offset = baseAddress | offset;
            memory.setDoubleWord(offset, data);
        }

        public void setQuadWord(int offset, long data)
        {
            offset = baseAddress | offset;
            memory.setQuadWord(offset, data);
        }

        public void setLowerDoubleQuadWord(int offset, long data)
        {
            offset = baseAddress | offset;
            memory.setQuadWord(offset, data);
        }

        public void setUpperDoubleQuadWord(int offset, long data)
        {
            offset += 8;
            offset = baseAddress | offset;
            memory.setQuadWord(offset, data);
        }

        public int execute(Processor cpu, int offset)
        {
            offset = baseAddress | offset;
            return memory.execute(cpu, offset);
        }

        public CodeBlock decodeCodeBlockAt(Processor cpu, int offset)
        {
            offset = baseAddress | offset;
            CodeBlock block= memory.decodeCodeBlockAt(cpu, offset);
            if(block!=null)
                System.out.println(getClass().getName()+":1");
            else
                System.out.println(getClass().getName()+":0");
            return block;
        }
    }

    public void clear()
    {
        for (int i=0; i<quickNonA20MaskedIndex.length; i++)
            quickNonA20MaskedIndex[i].clear();

        for (int i = 0; i < nonA20MaskedIndex.length; i++) {
            Memory[] subArray = nonA20MaskedIndex[i];
            try {
                for (int j = 0; j < subArray.length; j++) {
                    try {
                        subArray[j].clear();
                    } catch (NullPointerException e) {}
                }
            } catch (NullPointerException e) {}
        }
    }

    public void unmap(int start, int length)
    {
        if ((start % BLOCK_SIZE) != 0)
            throw new IllegalStateException("Cannot deallocate memory starting at "+Integer.toHexString(start)+"; this is not block aligned at "+BLOCK_SIZE+" boundaries");
        if ((length % BLOCK_SIZE) != 0)
            throw new IllegalStateException("Cannot deallocate memory in partial blocks. "+length+" is not a multiple of "+BLOCK_SIZE);

        for (int i=start; i<start+length; i+=BLOCK_SIZE)
        {
            if (getMemoryBlockAt(i) != UNCONNECTED)
                mappedRegionCount--;
            setMemoryBlockAt(i, UNCONNECTED);
        }
    }

    public void mapMemoryRegion(Memory underlying, int start, int length)
    {
        if (underlying.getSize() < length)
            throw new IllegalStateException("Underlying memory (length="+underlying.getSize()+") is too short for mapping into region "+length+" bytes long");
        if ((start % BLOCK_SIZE) != 0)
            throw new IllegalStateException("Cannot map memory starting at "+Integer.toHexString(start)+"; this is not aligned to "+BLOCK_SIZE+" blocks");
        if ((length % BLOCK_SIZE) != 0)
            throw new IllegalStateException("Cannot map memory in partial blocks: "+length+" is not a multiple of "+BLOCK_SIZE);

        unmap(start, length);

        long s = 0xFFFFFFFFl & start;
        for (long i=s; i<s+length; i += BLOCK_SIZE)
        {
            Memory w = new MapWrapper(underlying, (int)(i-s));
            setMemoryBlockAt((int)i, w);
            mappedRegionCount++;
        }
    }

    public void allocateMemory(int start, Memory block)
    {
        if ((start % BLOCK_SIZE) != 0)
            throw new IllegalStateException("Cannot allocate memory starting at "+Integer.toHexString(start)+"; this is not aligned to "+BLOCK_SIZE+" blocks");
        if (block.getSize() != BLOCK_SIZE)
            throw new IllegalStateException("Can only allocate memory in blocks of "+BLOCK_SIZE);

        unmap(start, BLOCK_SIZE);

        long s = 0xFFFFFFFFl & start;
        setMemoryBlockAt((int)s, block);
        mappedRegionCount++;
    }


    public static final class UnconnectedMemoryBlock extends Memory
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public UnconnectedMemoryBlock()
        {
        }

        public UnconnectedMemoryBlock(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input) throws IOException
        {
            org.jpc.SRDumpable x = new UnconnectedMemoryBlock(input);
            input.endObject();
            return x;
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": UnconnectedMemoryBlock:");
            dumpStatusPartial(output);
            output.endObject();
        }


        public void clear() {}

        public void clear(int start, int length) {}

        public void copyContentsInto(int address, byte[] buffer, int off, int len) {}

        public void copyContentsFrom(int address, byte[] buffer, int off, int len)
        {
            len = Math.min(BLOCK_SIZE - address, Math.min(buffer.length - off, len));
            for (int i=off; i<len; i++)
                buffer[i] = getByte(0);
        }

        public long getSize()
        {
            return BLOCK_SIZE;
        }

        public byte getByte(int offset)
        {
            return (byte) 0xFF;
        }

        public short getWord(int offset)
        {
            return (short) 0xFFFF;
        }

        public int getDoubleWord(int offset)
        {
            return 0xFFFFFFFF;
        }

        public long getQuadWord(int offset)
        {
            return -1l;
        }

        public long getLowerDoubleQuadWord(int offset)
        {
            return -1l;
        }

        public long getUpperDoubleQuadWord(int offset)
        {
            return -1l;
        }

        public void setByte(int offset, byte data) {}

        public void setWord(int offset, short data) {}

        public void setDoubleWord(int offset, int data) {}

        public void setQuadWord(int offset, long data) {}

        public void setLowerDoubleQuadWord(int offset, long data) {}

        public void setUpperDoubleQuadWord(int offset, long data) {}

        public int execute(Processor cpu, int offset)
        {
            throw new IllegalStateException("Trying to execute in Unconnected Block @ 0x" + Integer.toHexString(offset));
        }

        public CodeBlock decodeCodeBlockAt(Processor cpu, int offset)
        {
            throw new IllegalStateException("Trying to execute in Unconnected Block @ 0x" + Integer.toHexString(offset));
        }
    }

    public void reset()
    {
        clear();
        setGateA20State(false);
        linearAddr = null;
    }

    public boolean updated()
    {
        return true;
    }

    public void updateComponent(HardwareComponent component)
    {    }

    public boolean initialised()
    {
        return (linearAddr != null);
    }

    public void acceptComponent(HardwareComponent component)
    {
        if (component instanceof LinearAddressSpace)
            linearAddr = (LinearAddressSpace) component;
    }

    public String toString()
    {
        return "Physical Address Bus";
    }

    private Memory getMemoryBlockAt(int i)
    {
        try {
            return quickIndex[i >>> INDEX_SHIFT];
        } catch (ArrayIndexOutOfBoundsException e) {
            try {
                return index[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK];
            } catch (NullPointerException n) {
                return UNCONNECTED;
            }
        }
    }

    private void setMemoryBlockAt(int i, Memory b)
    {
        try {
            int idx = i >>> INDEX_SHIFT;
            quickNonA20MaskedIndex[idx] = b;
            if ((idx & (GATEA20_MASK >>> INDEX_SHIFT)) == idx) {
                quickA20MaskedIndex[idx] = b;
                quickA20MaskedIndex[idx | ((~GATEA20_MASK) >>> INDEX_SHIFT)] = b;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            try {
                nonA20MaskedIndex[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
            } catch (NullPointerException n) {
                nonA20MaskedIndex[i >>> TOP_INDEX_SHIFT] = new Memory[BOTTOM_INDEX_SIZE];
                nonA20MaskedIndex[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
            }

            if ((i & GATEA20_MASK) == i) {
                try {
                    a20MaskedIndex[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
                } catch (NullPointerException n) {
                    a20MaskedIndex[i >>> TOP_INDEX_SHIFT] = new Memory[BOTTOM_INDEX_SIZE];
                    a20MaskedIndex[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
                }

                int modi = i | ~GATEA20_MASK;
                try {
                    a20MaskedIndex[modi >>> TOP_INDEX_SHIFT][(modi >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
                } catch (NullPointerException n) {
                    a20MaskedIndex[modi >>> TOP_INDEX_SHIFT] = new Memory[BOTTOM_INDEX_SIZE];
                    a20MaskedIndex[modi >>> TOP_INDEX_SHIFT][(modi >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
                }
            }
        }
    }

    public void timerCallback() {}
}
