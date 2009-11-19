/*
    JPC-RR: A x86 PC Hardware Emulator
    Release 1

    Copyright (C) 2007-2009 Isis Innovation Limited
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

    Based on JPC x86 PC Hardware emulator,
    A project from the Physics Dept, The University of Oxford

    Details about original JPC can be found at:

    www-jpc.physics.ox.ac.uk

*/

package org.jpc.pluginsaux;

import org.jpc.pluginsaux.ConstantTableLayout;
import org.jpc.emulator.PC;
import org.jpc.emulator.processor.fpu64.FpuState;
import org.jpc.diskimages.DiskImage;
import org.jpc.emulator.DriveSet;
import static org.jpc.Misc.errorDialog;

import javax.swing.*;
import java.util.*;
import java.io.*;
import java.awt.event.*;
import java.awt.*;

public class NewDiskDialog implements ActionListener, WindowListener
{
    private JFrame window;
    private JPanel panel;
    private Response response;
    private boolean answerReady;
    private Map<String, JTextField> settings;

    public class Response
    {
        public String diskName;
        public String diskFile;
    }

    public void addOption(String name, String id, String deflt)
    {
        JLabel label = new JLabel(name);
        JTextField text = new JTextField(deflt, 40);
        settings.put(id, text);
        panel.add(label);
        panel.add(text);
    }

    public NewDiskDialog()
    {
        response = null;
        answerReady = false;
        window = new JFrame("Add disk");
        settings = new HashMap<String, JTextField>();
        GridLayout layout = new GridLayout(0, 2);
        panel = new JPanel(layout);
        window.add(panel);
        window.addWindowListener(this);

        addOption("Image name", "IMGNAME", "");
        addOption("Image file", "IMGFILE", "");

        JButton ass = new JButton("Add");
        ass.setActionCommand("ADD");
        ass.addActionListener(this);
        JButton cancl = new JButton("Cancel");
        cancl.setActionCommand("CANCEL");
        cancl.addActionListener(this);
        panel.add(ass);
        panel.add(cancl);

        window.pack();
        window.setVisible(true);
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    }

    public synchronized Response waitClose()
    {
        if(answerReady) {
            answerReady = false;
            return response;
        }
        while(!answerReady) {
            try {
                wait();
            } catch(InterruptedException e) {
            }
        }
        answerReady = false;
        return response;
    }

    private String textFor(String field)
    {
        String x = settings.get(field).getText();
        if(!("".equals(x))) {
            return x;
        } else
            return null;
    }

    public void actionPerformed(ActionEvent evt)
    {
        String command = evt.getActionCommand();
        if(command == "ADD") {
            response = new Response();
            response.diskFile = textFor("IMGFILE");
            response.diskName = textFor("IMGNAME");
            window.setVisible(false);
            window.dispose();
            synchronized(this) {
                answerReady = true;
                notifyAll();
            }
        } else if(command == "CANCEL") {
            window.setVisible(false);
            window.dispose();
            synchronized(this) {
                response = null;
                answerReady = true;
                notifyAll();
            }
        }
    }

    public void windowActivated(WindowEvent e) { /* Not interested. */ }
    public void windowClosed(WindowEvent e) { /* Not interested. */ }
    public void windowDeactivated(WindowEvent e) { /* Not interested. */ }
    public void windowDeiconified(WindowEvent e) { /* Not interested. */ }
    public void windowIconified(WindowEvent e) { /* Not interested. */ }
    public void windowOpened(WindowEvent e) { /* Not interested. */ }

    public void windowClosing(WindowEvent e)
    {
        window.setVisible(false);
        synchronized(this) {
            response = null;
            answerReady = true;
            notifyAll();
        }
    }

}