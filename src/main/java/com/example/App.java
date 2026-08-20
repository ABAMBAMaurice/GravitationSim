package com.example;

import com.example.gravity.ControlPanel;
import com.example.gravity.SimulationPanel;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

public class App {
    public static void main(String[] args) {
        // Must be set before any AWT/Swing class touches the toolkit, so this has to
        // come before invokeLater — switches Java2D from its default software rasterizer
        // to the OpenGL pipeline (GPU-accelerated) where the driver supports it. If it
        // doesn't (e.g. no GPU passthrough in a remote desktop session), Java2D silently
        // falls back to software rendering rather than failing.
        System.setProperty("sun.java2d.opengl", "true");

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Simulation newtonienne - bac a sable gravitationnel");
            SimulationPanel simulationPanel = new SimulationPanel();
            ControlPanel controlPanel = new ControlPanel(simulationPanel);

            frame.setLayout(new BorderLayout());
            frame.add(simulationPanel, BorderLayout.CENTER);
            frame.add(controlPanel, BorderLayout.EAST);
            frame.pack();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLocationRelativeTo(null);
            frame.setResizable(true);
            frame.setVisible(true);

            simulationPanel.requestFocusInWindow();
        });
    }
}
