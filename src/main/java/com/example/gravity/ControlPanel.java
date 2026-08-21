package com.example.gravity;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.IntConsumer;

public final class ControlPanel extends JScrollPane {

    private static final int CONTENT_WIDTH = 224;

    public ControlPanel(SimulationPanel simulation) {
        super(buildContent(simulation));
        setBorder(BorderFactory.createEmptyBorder());
        setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        getViewport().setBackground(Palette.PANEL_BG);
        setPreferredSize(new Dimension(300, 750));
        getVerticalScrollBar().setUnitIncrement(16);
        getVerticalScrollBar().setUI(new FlatScrollBarUI());
        SwingUtilities.invokeLater(() -> getVerticalScrollBar().setValue(0));
    }

    private static JPanel buildContent(SimulationPanel simulation) {
        JPanel root = new JPanel();
        root.setBackground(Palette.PANEL_BG);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(20, 16, 20, 16));

        root.add(title("Bac a sable gravitationnel"));
        root.add(Box.createVerticalStrut(20));

        LogSlider gSlider = new LogSlider("Constante G", 1e-10, 100000, simulation.getG(), simulation::setG);
        LogSlider speedSlider = new LogSlider("Vitesse simulation", 1e-6, 2_000_000_000,
                simulation.getSimulationSpeed(), simulation::setSimulationSpeed);
        LogSlider rotationSpeedSlider = new LogSlider("Vitesse de rotation (astres)", 0.001, 10_000,
                simulation.getRotationSpeedMultiplier(), simulation::setRotationSpeedMultiplier);

        root.add(sectionCard("Parametres",
                gSlider,
                massSlider("Masse (nouveau)", 1, 1.0e21, 2000, simulation::setSpawnMass),
                new LogSlider("Taille (nouveau, km)", 0.1, 2_000_000, 2.0, simulation::setSpawnRadius),
                speedSlider,
                rotationSpeedSlider,
                new LinearSlider("Adoucissement gravitationnel", 0, 40, (int) simulation.getSoftening(),
                        value -> simulation.setSoftening(value)),
                checkBox("Lancer un photon (masse nulle, vitesse = c)", false, simulation::setSpawnAsPhoton)));
        root.add(Box.createVerticalStrut(14));

        LinearSlider trailLengthSlider = new LinearSlider("Longueur des trainees", 0, 500,
                simulation.getTrailLength(), simulation::setTrailLength);
        JCheckBox mergeCheckBox = checkBox("Fusionner les corps en collision",
                simulation.isCollisionMergingEnabled(), simulation::setCollisionMergingEnabled);

        root.add(sectionCard("Affichage",
                checkBox("Afficher la trainee des nouveaux corps", false, simulation::setShowTrails),
                trailLengthSlider,
                checkBox("Afficher les noms de tous les astres", false, simulation::setShowAllNames),
                mergeCheckBox));
        root.add(Box.createVerticalStrut(14));

        simulation.setOnPresetLoaded(() -> {
            gSlider.setValue(simulation.getG());
            speedSlider.setValue(simulation.getSimulationSpeed());
            trailLengthSlider.setValue(simulation.getTrailLength());
            mergeCheckBox.setSelected(simulation.isCollisionMergingEnabled());
        });

        root.add(sectionCard("Corps selectionne", selectionCardBody(simulation)));
        root.add(Box.createVerticalStrut(14));

        root.add(sectionCard("Inserer un astre predefini",
                button("Soleil", () -> simulation.armPresetSpawn(PresetBodyKind.SOLEIL)),
                button("Terre", () -> simulation.armPresetSpawn(PresetBodyKind.TERRE)),
                button("Lune", () -> simulation.armPresetSpawn(PresetBodyKind.LUNE)),
                button("ISS", () -> simulation.armPresetSpawn(PresetBodyKind.ISS)),
                button("Satellite", () -> simulation.armPresetSpawn(PresetBodyKind.SATELLITE)),
                button("Planete", () -> simulation.armPresetSpawn(PresetBodyKind.PLANETE)),
                button("Asteroide", () -> simulation.armPresetSpawn(PresetBodyKind.ASTEROIDE)),
                helpText("Puis clic = pose immobile, glisser = pose avec vitesse.")));
        root.add(Box.createVerticalStrut(14));

        JLabel blackHoleDensityLabel = valueLabel();
        blackHoleDensityLabel.setForeground(Palette.TEXT_MUTED);
        blackHoleDensityLabel.setText(formatDensity(simulation.getBlackHoleDensityKgPerM3()));
        LogSlider blackHoleMassSlider = new LogSlider("Masse trou noir (kg)", 1e28, 1e42,
                simulation.getBlackHoleMassKg(),
                kg -> {
                    simulation.setBlackHoleMassKg(kg);
                    blackHoleDensityLabel.setText(formatDensity(simulation.getBlackHoleDensityKgPerM3()));
                },
                Units::formatKg);
        LogSlider blackHoleRadiusSlider = new LogSlider("Rayon trou noir (km)", 0.001, 1_000_000,
                simulation.getBlackHoleRadiusKm(),
                km -> {
                    simulation.setBlackHoleRadiusKm(km);
                    blackHoleDensityLabel.setText(formatDensity(simulation.getBlackHoleDensityKgPerM3()));
                });

        root.add(sectionCard("Trou noir (parametrable)",
                blackHoleMassSlider,
                blackHoleRadiusSlider,
                blackHoleDensityLabel,
                button("Inserer un trou noir", simulation::armBlackHoleSpawn),
                helpText("Puis clic = pose immobile, glisser = pose avec vitesse.")));
        root.add(Box.createVerticalStrut(14));

        root.add(sectionCard("Action",
                button("Pause / Lecture", simulation::togglePause),
                button("Effacer tout", simulation::clearBodies),
                button("Reinitialiser le systeme", simulation::resetSystem),
                button("Remettre le temps a 0", simulation::resetTime)));
        root.add(Box.createVerticalStrut(14));

        root.add(sectionCard("Systemes predefinis",
                button("Binaire circulaire", simulation::loadBinaryPreset),
                button("Triangle de Lagrange (3 corps)", simulation::loadThreeBodyPreset),
                button("Systeme solaire (reel)", simulation::loadStarSystemPreset),
                button("Deviation de la lumiere (photon)", simulation::loadPhotonDeflectionPreset),
                button("Nuage gravitationnel", simulation::loadGravityCloudPreset)));
        root.add(Box.createVerticalStrut(14));

        root.add(instructionsCard());
        root.add(Box.createVerticalGlue());
        return root;
    }

    private static RoundedPanel sectionCard(String titleText, Component... children) {
        RoundedPanel card = new RoundedPanel(14);
        card.setBackground(Palette.CARD);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        card.setMaximumSize(new Dimension(CONTENT_WIDTH + 28, Short.MAX_VALUE));

        card.add(sectionLabel(titleText));
        card.add(Box.createVerticalStrut(10));
        for (int i = 0; i < children.length; i++) {
            card.add(children[i]);
            if (i < children.length - 1) {
                card.add(Box.createVerticalStrut(10));
            }
        }
        return card;
    }

    private static JPanel selectionCardBody(SimulationPanel simulation) {
        JPanel card = new JPanel(new CardLayout());
        card.setOpaque(false);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(CONTENT_WIDTH, 410));
        card.setPreferredSize(new Dimension(CONTENT_WIDTH, 410));

        JLabel none = new JLabel(
                "<html>Clique sur un corps dans la simulation pour modifier son nom, sa masse, sa taille et sa vitesse.</html>");
        none.setForeground(Palette.TEXT_MUTED);
        none.setFont(none.getFont().deriveFont(12f));

        JTextField nameField = editableField();
        nameField.setForeground(Palette.ACCENT);
        nameField.setFont(nameField.getFont().deriveFont(Font.BOLD, 13f));

        Runnable commitName = () -> {
            String trimmed = nameField.getText().trim();
            Body current = simulation.getSelectedBody();
            if (current == null) {
                return;
            }
            if (!trimmed.isEmpty()) {
                simulation.setSelectedBodyName(trimmed);
            } else {
                nameField.setText(current.name);
            }
        };
        nameField.addActionListener(e -> commitName.run());
        nameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitName.run();
            }
        });

        JLabel speedLabel = valueLabel();
        speedLabel.setForeground(Palette.TEXT_MUTED);
        speedLabel.setText("Vitesse actuelle : -");

        LogSlider speedSlider = speedSlider("Vitesse", 1, 50_000_000, 100000, simulation::setSelectedBodySpeed);
        LogSlider massSlider = massSlider("Masse", 1, 1.0e21, 1000, simulation::setSelectedBodyMass);
        LogSlider sizeSlider = new LogSlider("Taille (km)", 0.1, 2_000_000, 2.0, simulation::setSelectedBodySize);
        JCheckBox trailCheckBox = checkBox("Afficher la trainee de ce corps", false, simulation::setSelectedBodyShowTrail);

        JPanel selected = new JPanel();
        selected.setOpaque(false);
        selected.setLayout(new BoxLayout(selected, BoxLayout.Y_AXIS));
        selected.add(nameField);
        selected.add(Box.createVerticalStrut(8));
        selected.add(speedLabel);
        selected.add(speedSlider);
        selected.add(massSlider);
        selected.add(sizeSlider);
        selected.add(trailCheckBox);
        selected.add(Box.createVerticalStrut(8));
        selected.add(button("Definir direction et vitesse (glisser)", simulation::armVelocityAssignment));

        card.add(none, "none");
        card.add(selected, "selected");

        simulation.setOnSelectionChanged(body -> {
            CardLayout layout = (CardLayout) card.getLayout();
            if (body == null) {
                layout.show(card, "none");
            } else {
                nameField.setText(body.name);
                speedSlider.setValue(Units.kmPerSecondToKmPerHour(body.speed()));
                massSlider.setValue(Units.internalMassToKg(body.mass));
                sizeSlider.setValue(body.radius);
                trailCheckBox.setSelected(body.showTrail);
                layout.show(card, "selected");
            }
        });

        Timer speedRefreshTimer = new Timer(120, e -> {
            Body current = simulation.getSelectedBody();
            if (current != null) {
                double kmh = Units.kmPerSecondToKmPerHour(current.speed());
                speedLabel.setText(String.format(Locale.US, "Vitesse actuelle : %,.0f km/h", kmh));
            }
        });
        speedRefreshTimer.start();

        return card;
    }

    private static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Palette.TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 17f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static String formatDensity(double kgPerM3) {
        return String.format(Locale.US, "Densite : %.3e kg/m³", kgPerM3);
    }

    private static JLabel helpText(String text) {
        JLabel label = new JLabel("<html>" + text + "</html>");
        label.setForeground(Palette.TEXT_MUTED);
        label.setFont(label.getFont().deriveFont(11f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(CONTENT_WIDTH, Short.MAX_VALUE));
        return label;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text.toUpperCase(Locale.FRENCH));
        label.setForeground(Palette.ACCENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel();
        label.setForeground(Palette.TEXT);
        label.setFont(label.getFont().deriveFont(12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JCheckBox checkBox(String label, boolean initial, Consumer<Boolean> onChange) {
        JCheckBox box = new JCheckBox(label, initial);
        box.setOpaque(false);
        box.setForeground(Palette.TEXT);
        box.setFont(box.getFont().deriveFont(12f));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.setFocusPainted(false);
        box.setBorderPainted(false);
        box.setIconTextGap(8);
        box.setMaximumSize(new Dimension(CONTENT_WIDTH, 24));
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        box.setIcon(flatCheckIcon(false));
        box.setSelectedIcon(flatCheckIcon(true));
        box.addActionListener(e -> onChange.accept(box.isSelected()));
        return box;
    }

    private static Icon flatCheckIcon(boolean checked) {
        int size = 16;
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (checked) {
                    g2.setColor(Palette.ACCENT);
                    g2.fillRoundRect(x, y, size, size, 5, 5);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(x + 4, y + 8, x + 7, y + 11);
                    g2.drawLine(x + 7, y + 11, x + 12, y + 5);
                } else {
                    g2.setColor(Palette.SURFACE);
                    g2.fillRoundRect(x, y, size, size, 5, 5);
                    g2.setColor(Palette.TEXT_MUTED);
                    g2.drawRoundRect(x, y, size - 1, size - 1, 5, 5);
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    /**
     * A LogSlider that operates on real kilograms while feeding the (unchanged)
     * internal mass unit to the simulation.
     */
    private static LogSlider massSlider(String label, double minInternal, double maxInternal, double initialInternal,
            DoubleConsumer onInternalChange) {
        return new LogSlider(label,
                Units.internalMassToKg(minInternal), Units.internalMassToKg(maxInternal),
                Units.internalMassToKg(initialInternal),
                kg -> onInternalChange.accept(Units.kgToInternalMass(kg)),
                Units::formatKg);
    }

    /**
     * A LogSlider that operates on km/h while feeding km/s (the simulation's native
     * velocity unit) to the callback.
     */
    private static LogSlider speedSlider(String label, double minKmh, double maxKmh, double initialKmh,
            DoubleConsumer onKmPerSecondChange) {
        return new LogSlider(label, minKmh, maxKmh, initialKmh,
                kmh -> onKmPerSecondChange.accept(Units.kmPerHourToKmPerSecond(kmh)),
                kmh -> String.format(Locale.US, "%,.0f km/h", kmh));
    }

    private static JButton button(String label, Runnable action) {
        FlatButton button = new FlatButton(label);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static RoundedPanel instructionsCard() {
        JTextArea area = new JTextArea(
                "Chaque curseur a un champ de texte : tape une valeur exacte (notation "
                        + "scientifique acceptee, ex. 5.972e24) puis Entree.\n\n"
                        + "Echelle : molette pour zoomer, de l'asteroide au systeme solaire complet. "
                        + "Tailles en km, vitesses en km/h.\n\n"
                        + "Glisser un corps existant : le deplacer. Glisser le vide : en lancer un "
                        + "nouveau (longueur/direction = vitesse). Clic simple : le selectionner.\n\n"
                        + "Shift + clic sur un second corps : mesurer la distance entre les deux "
                        + "(ligne pointillee et valeur en km, dans la simulation et le HUD).\n\n"
                        + "Clic droit + glisser : deplacer la vue (panoramique) pour atteindre les "
                        + "astres hors du champ, ex. les planetes exterieures. C : recentrer la camera.\n\n"
                        + "Systeme solaire (reel) : masses, distances et vitesses reelles ; "
                        + "vitesse de simulation acceleree par defaut sinon une annee prendrait "
                        + "une annee a regarder.\n\n"
                        + "Photon : coche la case dans Parametres puis glisse pour en lancer un "
                        + "(masse nulle, vitesse fixee a c, seule la direction du glisse compte). "
                        + "Un photon n'exerce aucune gravite mais en subit : le preset 'Deviation "
                        + "de la lumiere' montre sa trajectoire courber pres d'une masse, en "
                        + "gravite newtonienne classique (pas relativiste : sa vitesse peut donc "
                        + "varier legerement, contrairement a un vrai photon).\n\n"
                        + "Nuage gravitationnel : une quarantaine de corps en rotation collective "
                        + "sous-circulaire, qui s'attirent tous mutuellement (pas d'astre central) "
                        + "et fusionnent en s'entrechoquant -- observe le nuage former des paires "
                        + "et des corps de plus en plus gros au fil du temps.\n\n"
                        + "Espace : pause. R : recharger le systeme actuel. Suppr : retirer la selection.");
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(Palette.TEXT_MUTED);
        area.setFont(area.getFont().deriveFont(11.5f));
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        area.setMaximumSize(new Dimension(CONTENT_WIDTH, 700));

        RoundedPanel card = new RoundedPanel(14);
        card.setBackground(Palette.CARD);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        card.setMaximumSize(new Dimension(CONTENT_WIDTH + 28, Short.MAX_VALUE));
        card.add(sectionLabel("Aide"));
        card.add(Box.createVerticalStrut(10));
        card.add(area);
        return card;
    }

    private static JTextField editableField() {
        JTextField field = new JTextField();
        field.setBackground(Palette.SURFACE);
        field.setForeground(Palette.TEXT);
        field.setCaretColor(Palette.ACCENT);
        field.setFont(field.getFont().deriveFont(11f));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(CONTENT_WIDTH, 26));
        styleFocusBorder(field);
        return field;
    }

    private static void styleFocusBorder(JTextField field) {
        Border unfocused = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, Palette.CARD),
                BorderFactory.createEmptyBorder(4, 8, 2, 8));
        Border focused = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, Palette.ACCENT),
                BorderFactory.createEmptyBorder(4, 8, 2, 8));
        field.setBorder(unfocused);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBorder(focused);
            }

            @Override
            public void focusLost(FocusEvent e) {
                field.setBorder(unfocused);
            }
        });
    }

    private static JSlider baseSlider(int min, int max, int initial) {
        JSlider slider = new JSlider(min, max, initial);
        slider.setOpaque(false);
        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        slider.setMaximumSize(new Dimension(CONTENT_WIDTH, 24));
        slider.setUI(new FlatSliderUI(slider));
        return slider;
    }

    /** A rounded, flat-colored panel — plain JPanel is always a hard rectangle. */
    private static class RoundedPanel extends JPanel {
        private final int radius;

        RoundedPanel(int radius) {
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * A flat, rounded, hover-highlighted button — plain Swing has no such look
     * built in.
     */
    private static final class FlatButton extends JButton {
        private boolean hover = false;

        FlatButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setForeground(Palette.TEXT);
            setFont(getFont().deriveFont(12f));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(CONTENT_WIDTH, 34));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hover ? Palette.CARD_HOVER : Palette.SURFACE);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * A flat slider UI matching the dark theme: rounded filled track and a plain
     * circular thumb.
     */
    private static final class FlatSliderUI extends BasicSliderUI {
        FlatSliderUI(JSlider slider) {
            super(slider);
        }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int trackHeight = 4;
            int y = trackRect.y + trackRect.height / 2 - trackHeight / 2;

            g2.setColor(Palette.SURFACE);
            g2.fillRoundRect(trackRect.x, y, trackRect.width, trackHeight, trackHeight, trackHeight);

            int filledWidth = Math.max(trackHeight, thumbRect.x + thumbRect.width / 2 - trackRect.x);
            g2.setColor(Palette.ACCENT);
            g2.fillRoundRect(trackRect.x, y, filledWidth, trackHeight, trackHeight, trackHeight);
            g2.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Palette.ACCENT);
            g2.fillOval(thumbRect.x, thumbRect.y + thumbRect.height / 2 - 7, 14, 14);
            g2.setColor(Color.WHITE);
            g2.fillOval(thumbRect.x + 4, thumbRect.y + thumbRect.height / 2 - 3, 6, 6);
            g2.dispose();
        }

        @Override
        public void paintFocus(Graphics g) {
            // No focus ring — keeps the flat look.
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(14, 14);
        }
    }

    /** A thin flat scrollbar: no arrow buttons, a rounded translucent thumb. */
    private static final class FlatScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = Palette.CARD_HOVER;
            trackColor = Palette.PANEL_BG;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroSizeButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroSizeButton();
        }

        private JButton zeroSizeButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            g.setColor(Palette.PANEL_BG);
            g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Palette.CARD_HOVER);
            int pad = 2;
            g2.fillRoundRect(thumbBounds.x + pad, thumbBounds.y, thumbBounds.width - pad * 2, thumbBounds.height, 6, 6);
            g2.dispose();
        }
    }

    /**
     * A slider with a logarithmic mapping, for parameters that span a wide range
     * (mass, G,
     * speed) — paired with a text field so exact values (e.g. a real astronomical
     * constant)
     * can be typed in directly instead of hunting for them by dragging.
     */
    private static final class LogSlider extends JPanel {
        private static final int STEPS = 1000;

        private final JSlider slider;
        private final JTextField field = editableField();
        private final double min;
        private final double max;
        private final DoubleConsumer onChange;
        private final DoubleFunction<String> tooltip;
        private boolean silent;
        private double lastValue;

        LogSlider(String label, double min, double max, double initial, DoubleConsumer onChange) {
            this(label, min, max, initial, onChange, null);
        }

        LogSlider(String label, double min, double max, double initial, DoubleConsumer onChange,
                DoubleFunction<String> tooltip) {
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            this.tooltip = tooltip;

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(CONTENT_WIDTH, 74));

            JLabel nameLabel = valueLabel();
            nameLabel.setText(label);

            field.addActionListener(e -> commitField());
            field.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    commitField();
                }
            });

            slider = baseSlider(0, STEPS, 0);
            slider.addChangeListener(e -> {
                if (silent) {
                    return;
                }
                double value = valueFromSlider(slider.getValue());
                updateDisplay(value);
                this.onChange.accept(value);
            });

            add(nameLabel);
            add(Box.createVerticalStrut(3));
            add(field);
            add(Box.createVerticalStrut(3));
            add(slider);
            setValue(initial);
        }

        void setValue(double value) {
            double clamped = Math.max(min, Math.min(max, value));
            silent = true;
            slider.setValue(sliderFromValue(clamped));
            silent = false;
            updateDisplay(clamped);
        }

        private void commitField() {
            double typed;
            try {
                typed = Double.parseDouble(field.getText().trim());
            } catch (NumberFormatException ex) {
                updateDisplay(lastValue);
                return;
            }
            double clamped = Math.max(min, Math.min(max, typed));
            silent = true;
            slider.setValue(sliderFromValue(clamped));
            silent = false;
            updateDisplay(clamped);
            onChange.accept(clamped);
        }

        private int sliderFromValue(double value) {
            double t = Math.log(value / min) / Math.log(max / min);
            return (int) Math.round(t * STEPS);
        }

        private double valueFromSlider(int position) {
            double t = position / (double) STEPS;
            return min * Math.pow(max / min, t);
        }

        private void updateDisplay(double value) {
            lastValue = value;
            field.setText(Double.toString(value));
            if (tooltip != null) {
                field.setToolTipText(tooltip.apply(value));
            }
        }
    }

    /**
     * A slider with a plain linear mapping and an editable field, for naturally
     * bounded integer parameters.
     */
    private static final class LinearSlider extends JPanel {
        private final JSlider slider;
        private final JTextField field = editableField();
        private final int min;
        private final int max;
        private final IntConsumer onChange;
        private boolean silent;

        LinearSlider(String label, int min, int max, int initial, IntConsumer onChange) {
            this.min = min;
            this.max = max;
            this.onChange = onChange;

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(CONTENT_WIDTH, 74));

            JLabel nameLabel = valueLabel();
            nameLabel.setText(label);

            field.addActionListener(e -> commitField());
            field.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    commitField();
                }
            });

            slider = baseSlider(min, max, initial);
            slider.addChangeListener(e -> {
                if (silent) {
                    return;
                }
                int value = slider.getValue();
                field.setText(Integer.toString(value));
                onChange.accept(value);
            });

            add(nameLabel);
            add(Box.createVerticalStrut(3));
            add(field);
            add(Box.createVerticalStrut(3));
            add(slider);
            field.setText(Integer.toString(initial));
        }

        void setValue(int value) {
            int clamped = Math.max(min, Math.min(max, value));
            silent = true;
            slider.setValue(clamped);
            silent = false;
            field.setText(Integer.toString(clamped));
        }

        private void commitField() {
            int typed;
            try {
                typed = (int) Math.round(Double.parseDouble(field.getText().trim()));
            } catch (NumberFormatException ex) {
                field.setText(Integer.toString(slider.getValue()));
                return;
            }
            int clamped = Math.max(min, Math.min(max, typed));
            silent = true;
            slider.setValue(clamped);
            silent = false;
            field.setText(Integer.toString(clamped));
            onChange.accept(clamped);
        }
    }
}
