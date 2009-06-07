/*
    JPC: A x86 PC Hardware Emulator for a pure Java Virtual Machine
    Release Version 2.0

    A project from the Physics Dept, The University of Oxford

    Copyright (C) 2007 Isis Innovation Limited
    Copyright (C) 2009 H. Ilari Liusvaara

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

package org.jpc.support;

import java.io.*;

public class DiskImage implements org.jpc.SRDumpable
{
    private boolean readOnly;
    private boolean busy;
    private boolean used;
    private int type;
    private long totalSectors;
    private int heads;
    private int cylinders;
    private int sectors;
    private String imageFileName;
    private int[] sectorOffsetMap;
    private byte[][] copyOnWriteData;
    private byte[] blankPage;
    private byte[] diskID;
    private RandomAccessFile image;
    private static ImageLibrary library;

    public static void setLibrary(ImageLibrary lib) 
    {
        library = lib;
    }
 
    public static ImageLibrary getLibrary()
    {
        return library;
    }

    public void dumpStatusPartial(org.jpc.support.StatusDumper output)
    {
    }
 
    public void dumpStatus(org.jpc.support.StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": DiskImage:");
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
        System.err.println("Dumping disk image...");
        output.dumpArray(diskID);
        int cowEntries = 0;
        for(int i = 0; i < copyOnWriteData.length; i++) {
            if(copyOnWriteData[i] == null)
                continue;
            cowEntries++;
        }
        output.dumpInt(cowEntries);
        for(int i = 0; i < copyOnWriteData.length; i++) {
            if(copyOnWriteData[i] == null)
                continue;
            output.dumpInt(i);
            output.dumpArray(copyOnWriteData[i]);
        }
        System.err.println("Disk image dumped (" + cowEntries + " cow entries).");
    }

    private void commonConstructor(String fileName) throws IOException
    {
        ImageMaker.ParsedImage p = new ImageMaker.ParsedImage(fileName);
        if(p.typeCode == 0) {
            type = BlockDevice.TYPE_FLOPPY;
            readOnly = false;
        } else if(p.typeCode == 1) {
            type = BlockDevice.TYPE_HD;
            readOnly = false;
        } else if(p.typeCode == 2) {
            type = BlockDevice.TYPE_CDROM;
            readOnly = true;
        } else
            throw new IOException("Can't load " + fileName + ": Image of unknown type!");

        busy = false;
        used = false;
        totalSectors = p.totalSectors;
        heads = p.sides;
        cylinders = p.tracks;
        sectors = p.sectors;
        imageFileName = fileName;
        sectorOffsetMap = p.sectorOffsetMap;
        if(type != BlockDevice.TYPE_CDROM)
            copyOnWriteData = new byte[(int)totalSectors][];
        else {
            //Parameters from original JPC code...
            cylinders = 2;
            heads = 16;
            sectors = 63;
        }
        diskID = p.diskID;
        blankPage = new byte[512];
        image = new RandomAccessFile(fileName, "r");
    }

    public DiskImage(org.jpc.support.SRLoader input) throws IOException
    {
        input.objectCreated(this);
        byte[] id = input.loadArrayByte();
        String fileName = library.lookupFileName(id);
        if(fileName == null)
            throw new IOException("No disk with ID " + (new ImageLibrary.ByteArray(id)) + " found.");
        commonConstructor(fileName);
        int cowEntries = input.loadInt();
        for(int i = 0; i < cowEntries; i++) {
            int j = input.loadInt();
            copyOnWriteData[j] = input.loadArrayByte();
        }
    }

    public DiskImage(String diskName, boolean fsPath) throws IOException
    {
        String fileName;
        if(!fsPath) {
            fileName = library.searchFileName(diskName);
            if(fileName == null)
                throw new IOException(diskName + ": No such image in Library.");
        } else
            fileName = diskName;
        commonConstructor(fileName);
    }

    public int read(long sectorNum, byte[] buffer, int size)
    {
        if(sectorNum + size > totalSectors) {
            System.err.println("Trying to read invalid sector range " + sectorNum + "-" + (sectorNum + size - 1) +  ".");
            return -1;
        }

        for(int i = 0; i < size; i++) {
            if(copyOnWriteData[(int)sectorNum] != null) {
                //Copy On Write data takes percedence.
                System.arraycopy(copyOnWriteData[(int)sectorNum], 0, buffer, 512 * i, 512);
            } else if(sectorNum < sectorOffsetMap.length && sectorOffsetMap[(int)sectorNum] > 0) {
                //Found from image.
                try {
                    image.seek(sectorOffsetMap[(int)sectorNum]);
                    image.read(buffer, 512 * i, 512);
                } catch(IOException e) {
                    System.err.println("Failed to read sector " + sectorNum + ".");
                    return -1;
                }
            } else { 
                //Null page.
                System.arraycopy(blankPage, 0, buffer, 512 * i, 512);
            }
            sectorNum++;
        }
        return 0;
    }

    public int write(long sectorNum, byte[] buffer, int size)
    {
        if(readOnly)
            return -1;      //Error, write to write-protected disk.
 
        if(sectorNum + size > totalSectors) {
            System.err.println("Trying to write invalid sector range " + sectorNum + "-" + (sectorNum + size - 1) +  ".");
            return -1;
        }

        for(int i = 0; i < size; i++) {
             if(copyOnWriteData[(int)sectorNum] == null)
                 copyOnWriteData[(int)sectorNum] = new byte[512];
             System.arraycopy(buffer, 512 * i, copyOnWriteData[(int)sectorNum], 0, 512);
             sectorNum++;
        }
        return 512 * size;
    }

    public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        org.jpc.SRDumpable x = new DiskImage(input);
        input.endObject();
        return x;
    }



    public void use() throws IOException
    {
        if(busy)
            throw new IOException("Trying to use busy disk!");
        busy = true;
        used = true;
    }

    public void unuse()
    {
        busy = false;
    }

    public boolean isReadOnly()
    {
        return readOnly;
    }

    public int getType()
    {
        return type;
    }

    public long getTotalSectors()
    {
        return totalSectors;
    }

    public int getHeads()
    {
        return heads;
    }

    public int getCylinders()
    {
        return cylinders;
    }

    public int getSectors()
    {
        return sectors;
    }

    public String getImageFileName()
    {
        return imageFileName;
    }
}