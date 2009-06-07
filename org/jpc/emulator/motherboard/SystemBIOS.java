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

package org.jpc.emulator.motherboard;

import org.jpc.emulator.*;
import org.jpc.emulator.memory.*;
import java.io.*;

public class SystemBIOS extends AbstractHardwareComponent implements IOPortCapable
{
    private byte[] imageData;
    private boolean ioportRegistered, loaded;

    public SystemBIOS(byte[] image)
    {
        loaded = false;
        ioportRegistered = false;

        imageData = new byte[image.length];
        System.arraycopy(image, 0, imageData, 0, image.length);
    }

    public SystemBIOS(String imagefile) throws IOException
    {
        String fileName = org.jpc.support.DiskImage.getLibrary().searchFileName(imagefile);
        if(fileName == null)
            throw new IOException(imagefile + ": No such image in Library.");
        org.jpc.support.ImageMaker.ParsedImage pimg = new org.jpc.support.ImageMaker.ParsedImage(fileName);
        if(pimg.typeCode != 3)
            throw new IOException(imagefile + ": is not a BIOS image.");
        imageData = pimg.rawImage;
    }

    public void dumpStatusPartial(org.jpc.support.StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tioportRegistered " + ioportRegistered + " loaded " + loaded);
        output.printArray(imageData, "imageData"); 
    }
 
    public void dumpStatus(org.jpc.support.StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": SystemBIOS:");
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
        super.dumpSRPartial(output);
        output.dumpBoolean(ioportRegistered);
        output.dumpBoolean(loaded);
        output.dumpArray(imageData);
    }

    public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        org.jpc.SRDumpable x = new SystemBIOS(input);
        input.endObject();
        return x;
    }

    public SystemBIOS(org.jpc.support.SRLoader input) throws IOException
    {
        super(input);
        ioportRegistered = input.loadBoolean();
        loaded = input.loadBoolean();
        imageData = input.loadArrayByte();
    }

    public int[] ioPortsRequested()
    {
        return new int[]{0x400, 0x401, 0x402, 0x403, 0x8900};
    }

    public int ioPortReadByte(int address) { return 0xff; }
    public int ioPortReadWord(int address) { return 0xffff; }
    public int ioPortReadLong(int address) { return (int)0xffffffff; }

    public void ioPortWriteByte(int address, int data)
    {
        switch(address)
        {
            /* Bochs BIOS Messages */
        case 0x402:
        case 0x403:
            try
            {
                System.out.print(new String(new byte[]{(byte)data},"US-ASCII"));
            }
            catch (Exception e)
            {
                System.out.print(new String(new byte[]{(byte)data}));
            }
            break;
        case 0x8900:
            System.err.println("Attempt to call Shutdown");
            break;
        default:
        }
    }

    public void ioPortWriteWord(int address, int data)
    {
        switch(address) {
            /* Bochs BIOS Messages */
        case 0x400:
        case 0x401:
            System.err.println("BIOS panic at rombios.c, line " + data);
        default:
        }
    }

    public void ioPortWriteLong(int address, int data) {}

    public void load(PhysicalAddressSpace physicalAddress)
    {
        int blockSize = AddressSpace.BLOCK_SIZE;
        int len = ((imageData.length-1)/ blockSize + 1)*blockSize;
        int fraction = len - imageData.length;
        int imageOffset = blockSize - fraction;

        EPROMMemory ep = new EPROMMemory(blockSize, fraction, imageData, 0, imageOffset);
        physicalAddress.allocateMemory(0x100000 - len, ep);

        for (int i=1; i<len/blockSize; i++)
        {
            ep = new EPROMMemory(blockSize, 0, imageData, imageOffset, blockSize);
            physicalAddress.allocateMemory(0x100000 - len + i*blockSize, ep);
            imageOffset += blockSize;
        }
    }

    public byte[] getImage()
    {
        return (byte[]) imageData.clone();
    }

    public boolean updated()
    {
        return (loaded && ioportRegistered);
    }

    public void updateComponent(HardwareComponent component)
    {
        if ((component instanceof PhysicalAddressSpace) && component.updated())
        {
            this.load((PhysicalAddressSpace)component);
            loaded = true;
        }

        if ((component instanceof IOPortHandler) && component.updated())
        {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
    }

    public boolean initialised()
    {
        return (loaded && ioportRegistered);
    }

    public void acceptComponent(HardwareComponent component)
    {
        if ((component instanceof PhysicalAddressSpace) && component.initialised())
        {
            this.load((PhysicalAddressSpace)component);
            loaded = true;
        }

        if ((component instanceof IOPortHandler) && component.initialised())
        {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
    }

    public void reset()
    {
        loaded = false;
        ioportRegistered = false;
    }
}
