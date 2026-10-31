import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.Collections;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Swing bileşenlerine yalnız EDT erişir; butonlar model yerine komut kuyruğuna yazar. */
public final class SimulationWindow {
    private static final Color BG = new Color(18, 25, 35);
    private static final Color PANEL = new Color(27, 37, 49);
    private static final Color TEXT = new Color(225, 233, 241);
    private static final Color MUTED = new Color(155, 174, 191);
    private static final Color PLAYER = new Color(75, 165, 255);
    private static final Color TARGET = new Color(248, 105, 105);
    private static final Color BULLET = new Color(255, 214, 94);

    private final Queue<DisplayFederate.UiCommand> commands;
    private final AtomicReference<RenderSnapshot> pending = new AtomicReference<>();
    private final JFrame frame = new JFrame("Tank Simülasyonu — Portico / HLA");
    private final JLabel phase = label("BAĞLANIYOR", 13, TEXT);
    private final JLabel time = label("t = 0.00 s", 21, TEXT);
    private final JLabel info = label("Dört federe bekleniyor…", 12, MUTED);
    private final JLabel counts = label("0 tank · 0 mermi", 12, MUTED);
    private final JLabel position = label("Oyuncu konumu: —", 13, TEXT);
    private final JLabel velocity = label("Oyuncu hızı: —", 13, TEXT);
    private final JSpinner steerX = new JSpinner(new SpinnerNumberModel(1.0, -1.0, 1.0, 0.1));
    private final JSpinner steerY = new JSpinner(new SpinnerNumberModel(0.0, -1.0, 1.0, 0.1));
    private final JButton apply = new JButton("Yönü uygula");
    private final JButton moveStop = new JButton("Tankı durdur");
    private final JButton fire = new JButton("ATEŞ ET (Space)");
    private final JButton stop = new JButton("Simülasyonu bitir");
    private final JButton restart = new JButton("Yeniden başlat");
    private final Arena arena = new Arena();
    private final Timer timer;
    private RenderSnapshot snapshot = new RenderSnapshot(SimulationStateData.empty(),
        Collections.emptyList(), "Bağlanılıyor…", true, false);
    private boolean closing, spaceHeld, restarting;
    private long noticeUntil;
    private String notice;

    public SimulationWindow(Queue<DisplayFederate.UiCommand> commands) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("SimulationWindow EDT uzerinde olusturulmali");
        this.commands = commands;
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820, 650));
        frame.setSize(1100, 820);
        frame.setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout(16, 16));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(20, 20, 16, 20));
        frame.setContentPane(root);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JPanel titles = vertical();
        titles.add(label("TANK SİMÜLASYONU", 23, TEXT));
        titles.add(Box.createVerticalStrut(5));
        titles.add(label("Tank  /  Prediction  /  Collision  /  Display", 12, MUTED));
        header.add(titles, BorderLayout.WEST);
        JPanel clock = vertical();
        clock.add(time);
        clock.add(phase);
        header.add(clock, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);
        root.add(arena, BorderLayout.CENTER);

        JPanel controls = vertical();
        controls.setOpaque(true);
        controls.setBackground(PANEL);
        controls.setBorder(BorderFactory.createEmptyBorder(18, 16, 18, 16));
        controls.setPreferredSize(new Dimension(240, 500));
        controls.add(label("OYUNCU KONTROLÜ", 15, TEXT));
        controls.add(Box.createVerticalStrut(16));
        controls.add(label("Yön vektörü  (x, y)", 13, TEXT));
        controls.add(Box.createVerticalStrut(7));
        JPanel steering = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        steering.setOpaque(false);
        steering.add(label("X", 13, TEXT));
        steerX.setPreferredSize(new Dimension(68, 28));
        steerY.setPreferredSize(new Dimension(68, 28));
        steering.add(steerX);
        steering.add(label("Y", 13, TEXT));
        steering.add(steerY);
        steering.setAlignmentX(0);
        steering.setMaximumSize(new Dimension(220, 32));
        controls.add(steering);
        controls.add(Box.createVerticalStrut(10));
        addButton(controls, apply);
        addButton(controls, moveStop);
        controls.add(Box.createVerticalStrut(8));
        addButton(controls, fire);
        controls.add(Box.createVerticalStrut(20));
        controls.add(position);
        controls.add(Box.createVerticalStrut(6));
        controls.add(velocity);
        controls.add(Box.createVerticalStrut(20));
        controls.add(label("●  Oyuncu: 5 birim/s", 13, PLAYER));
        controls.add(Box.createVerticalStrut(8));
        controls.add(label("●  Hedef: 10 birim/s", 13, TARGET));
        controls.add(Box.createVerticalStrut(8));
        controls.add(label("●  Mermi: 50 birim/s", 13, BULLET));
        controls.add(Box.createVerticalStrut(20));
        controls.add(label("Oklar: ↑↓ Y, ←→ X  (adım 0,1)", 12, MUTED));
        controls.add(Box.createVerticalStrut(8));
        controls.add(label("Tank durunca yönü korunur.", 12, MUTED));
        controls.add(Box.createVerticalGlue());
        addButton(controls, restart);
        addButton(controls, stop);
        root.add(controls, BorderLayout.EAST);

        JPanel footer = new JPanel(new BorderLayout(8, 7));
        footer.setOpaque(false);
        footer.add(info, BorderLayout.CENTER);
        footer.add(counts, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        apply.addActionListener(e -> {
            try {
                double[] direction = readDirection();
                sendDirection(direction[0], direction[1], false);
            } catch (ParseException ex) {
                showInputNotice("Yön bileşenlerini -1 ile 1 arasında girin.");
            }
        });
        installArrowControls();
        moveStop.addActionListener(e -> enqueue(DisplayFederate.CommandKind.MOVE_STOP, 0, 0));
        fire.addActionListener(e -> enqueue(DisplayFederate.CommandKind.FIRE, 0, 0));
        installFireControl();
        stop.addActionListener(e -> {
            enqueue(DisplayFederate.CommandKind.STOP, 0, 0);
            stop.setEnabled(false);
        });
        restart.addActionListener(e -> {
            if (!snapshot.restartAvailable || closing || restarting) return;
            restarting = true;
            notice = "Yeni oyun başlatılıyor…";
            noticeUntil = Long.MAX_VALUE;
            info.setText(notice);
            enqueue(DisplayFederate.CommandKind.RESTART, 0, 0);
            updateEnabled();
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (closing) return;
                closing = true;
                // RTI kapalıyken de ana thread son ekranın komutlarını bekliyor olabilir.
                enqueue(DisplayFederate.CommandKind.CLOSE, 0, 0);
                if (!snapshot.connected) {
                    timer.stop();
                    frame.dispose();
                } else {
                    info.setText("Simülasyona durdurma isteği gönderiliyor…");
                    updateEnabled();
                }
            }
        });
        timer = new Timer(33, e -> {
            RenderSnapshot next = pending.getAndSet(null);
            if (next != null) {
                snapshot = next;
                refreshLabels();
                arena.repaint();
            }
        });
        updateEnabled();
    }

    public void showWindow() { frame.setVisible(true); timer.start(); }

    // Main thread yalnız bu değişmez kopyayı bırakır; Swing alanlarını değiştirmez.
    public void render(RenderSnapshot next) { pending.set(next); }

    public void closeWindow() {
        SwingUtilities.invokeLater(() -> { timer.stop(); frame.dispose(); });
    }

    private void enqueue(DisplayFederate.CommandKind kind, double x, double y) {
        commands.add(new DisplayFederate.UiCommand(kind, x, y));
    }

    private void installArrowControls() {
        registerArrow(KeyEvent.VK_UP, steerY, 1);
        registerArrow(KeyEvent.VK_DOWN, steerY, -1);
        registerArrow(KeyEvent.VK_RIGHT, steerX, 1);
        registerArrow(KeyEvent.VK_LEFT, steerX, -1);
    }

    private void installFireControl() {
        bindFireKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false), "fire-pressed",
            new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) {
                    // Basılı tutmak otomatik atış üretmez; her yeni basış tek komuttur.
                    if (spaceHeld) return;
                    spaceHeld = true;
                    if (fire.isEnabled()) fire.doClick(0);
                }
            });
        bindFireKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true), "fire-released",
            new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { spaceHeld = false; }
            });
        bindFireKey(KeyStroke.getKeyStroke(' '), "fire-typed", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                // Sayı alanı seçiliyken Space, metne boşluk eklemesin.
            }
        });
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent event) { spaceHeld = false; }
        });
    }

    private void bindFireKey(KeyStroke key, String name, Action action) {
        bindKey(frame.getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, key, name, action);
        // Odaktaki düğmenin varsayılan Space tıklaması da geçersiz kılınır.
        // Örneğin "Simülasyonu bitir" seçiliyken Space yine yalnız ateş komutudur.
        bindFocusedKey(frame.getContentPane(), key, name, action);
    }

    private static void bindFocusedKey(Component component, KeyStroke key, String name, Action action) {
        if (component instanceof JComponent) {
            bindKey((JComponent) component, JComponent.WHEN_FOCUSED, key, name, action);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                bindFocusedKey(child, key, name, action);
            }
        }
    }

    private void registerArrow(int keyCode, JSpinner axis, int increment) {
        KeyStroke key = KeyStroke.getKeyStroke(keyCode, 0);
        String name = "steer-" + keyCode;
        Action action = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                adjustDirection(axis, increment);
            }
        };
        bindKey(frame.getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, key, name, action);
        // Sayı alanının kendi ok tuşu davranışı pencere kısayolundan önce gelir.
        // Aynı action'ı burada da bağlarız; Swing ilk eşleşmeyi yalnız bir kez çalıştırır.
        for (JSpinner spinner : new JSpinner[] {steerX, steerY}) {
            bindKey(spinner, JComponent.WHEN_FOCUSED, key, name, action);
            bindKey(spinner, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, key, name, action);
            JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) spinner.getEditor();
            bindKey(editor.getTextField(), JComponent.WHEN_FOCUSED, key, name, action);
        }
    }

    private static void bindKey(JComponent component, int condition,
                                  KeyStroke key, String name, Action action) {
        component.getInputMap(condition).put(key, name);
        component.getActionMap().put(name, action);
    }

    private void adjustDirection(JSpinner axis, int increment) {
        // EDT üzerinde çalışır. Başlangıç, kapanış ve bağlantı kaybında komut üretmez.
        if (!apply.isEnabled()) return;
        try {
            double[] direction = readDirection();
            int index = axis == steerX ? 0 : 1;
            SpinnerNumberModel model = (SpinnerNumberModel) axis.getModel();
            // Ondalık 0,1 adımları tam sıfıra ulaşsın; kayan nokta artığıyla hareket başlamasın.
            double next = BigDecimal.valueOf(direction[index])
                .add(BigDecimal.valueOf(model.getStepSize().doubleValue())
                    .multiply(BigDecimal.valueOf(increment)))
                .doubleValue();
            next = Math.max(((Number) model.getMinimum()).doubleValue(),
                Math.min(((Number) model.getMaximum()).doubleValue(), next));
            axis.setValue(next);
            direction[index] = next;
            sendDirection(direction[0], direction[1], true);
        } catch (ParseException ex) {
            showInputNotice("Yön bileşenlerini -1 ile 1 arasında girin.");
        }
    }

    private double[] readDirection() throws ParseException {
        // Kullanıcı henüz Enter'a basmadıysa da iki alandaki yazıyı hesaba kat.
        steerX.commitEdit();
        steerY.commitEdit();
        double x = ((Number) steerX.getValue()).doubleValue();
        double y = ((Number) steerY.getValue()).doubleValue();
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < -1 || x > 1 || y < -1 || y > 1) {
            throw new ParseException("Yön bileşenleri -1 ile 1 arasında olmalı.", 0);
        }
        return new double[] {x, y};
    }

    private void sendDirection(double x, double y, boolean stopAtZero) {
        if (x == 0.0 && y == 0.0) {
            if (!stopAtZero) {
                showInputNotice("Yön sıfır olamaz; durmak için Tankı durdur.");
                return;
            }
            // Oklarla sıfırdan geçerken tank durur; Tank federesi son bakış yönünü korur.
            noticeUntil = 0;
            enqueue(DisplayFederate.CommandKind.MOVE_STOP, 0, 0);
            return;
        }
        noticeUntil = 0;
        // Buton ve klavye aynı komut yolunu kullanır: Display -> RTI -> Tank.
        // Tank bu yönü normalize eder; bileşke hız 5 birim/s olarak kalır.
        enqueue(DisplayFederate.CommandKind.STEER, x, y);
    }

    private void showInputNotice(String text) {
        notice = text;
        noticeUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        info.setText(text);
        info.setToolTipText(text);
    }

    private void refreshLabels() {
        SimulationStateData s = snapshot.state;
        time.setText(String.format(Locale.ROOT, "t = %.2f s", s.simTime == null ? 0 : s.simTime));
        phase.setText(s.phase == null ? "BAĞLANIYOR" : s.phase.name());
        phase.setForeground(s.phase == SimulationStateData.Phase.ERROR ? TARGET
            : s.phase == SimulationStateData.Phase.RUNNING ? new Color(115, 215, 156) : BULLET);
        String visibleDetail = noticeUntil > System.nanoTime() ? notice : snapshot.detail;
        info.setText(visibleDetail);
        info.setToolTipText(visibleDetail);
        int tanks = 0, bullets = 0;
        position.setText("Oyuncu konumu: —");
        velocity.setText("Oyuncu hızı: —");
        for (RenderSnapshot.Entity entity : snapshot.entities) {
            if (entity.bullet) bullets++; else tanks++;
            if (entity.player) {
                position.setText(String.format(Locale.ROOT, "Konum: (%.1f, %.1f)", entity.x, entity.y));
                velocity.setText(String.format(Locale.ROOT, "Hız: %.1f birim/s",
                    Math.hypot(entity.vx, entity.vy)));
            }
        }
        counts.setText(tanks + " tank · " + bullets + " mermi");
        updateEnabled();
    }

    private void updateEnabled() {
        boolean active = snapshot.connected && !snapshot.stopping
            && !closing && !restarting && snapshot.state.runId != null;
        boolean running = active && snapshot.state.phase == SimulationStateData.Phase.RUNNING;
        boolean steering = active && (running
            || snapshot.state.phase == SimulationStateData.Phase.WAITING_FOR_PREDICTION);
        apply.setEnabled(steering);
        moveStop.setEnabled(steering);
        steerX.setEnabled(steering);
        steerY.setEnabled(steering);
        fire.setEnabled(running);
        stop.setEnabled(active && snapshot.state.phase != SimulationStateData.Phase.STOPPING
            && snapshot.state.phase != SimulationStateData.Phase.ERROR);
        restart.setEnabled(snapshot.restartAvailable && !closing && !restarting);
        restart.setToolTipText(!Boolean.getBoolean("tanksim.managedRestart")
            ? "Yeniden başlatma için uygulamayı run.ps1 ile açın."
            : snapshot.restartAvailable
                ? "Süreyi, tankları ve atışları sıfırlayarak yeni oyun açar."
                : "Oyun ve bağlantı kapanışı tamamlandığında kullanılabilir.");
    }

    private static JPanel vertical() {
        JPanel result = new JPanel();
        result.setLayout(new BoxLayout(result, BoxLayout.Y_AXIS));
        result.setOpaque(false);
        return result;
    }

    private static JLabel label(String text, int size, Color color) {
        JLabel result = new JLabel(text);
        result.setForeground(color);
        result.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size));
        result.setAlignmentX(0);
        return result;
    }

    private static void addButton(JPanel panel, JButton button) {
        button.setAlignmentX(0);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        button.setPreferredSize(new Dimension(205, 36));
        button.setFocusPainted(false);
        panel.add(button);
        panel.add(Box.createVerticalStrut(7));
    }

    private final class Arena extends JPanel {
        Arena() { setBackground(BG); setPreferredSize(new Dimension(740, 660)); }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double worldW = validDimension(snapshot.state.mapWidth);
            double worldH = validDimension(snapshot.state.mapHeight);
            double scale = Math.min(Math.max(1, getWidth() - 64) / worldW,
                Math.max(1, getHeight() - 64) / worldH);
            double mapW = worldW * scale, mapH = worldH * scale;
            double left = (getWidth() - mapW) / 2, top = (getHeight() - mapH) / 2;
            g.setColor(new Color(31, 48, 49));
            g.fill(new RoundRectangle2D.Double(left, top, mapW, mapH, 8, 8));
            g.setStroke(new BasicStroke(1));
            g.setColor(new Color(49, 68, 68));
            for (int i = 1; i < 10; i++) {
                double xx = left + mapW * i / 10;
                double yy = top + mapH * i / 10;
                g.draw(new Line2D.Double(xx, top, xx, top + mapH));
                g.draw(new Line2D.Double(left, yy, left + mapW, yy));
            }
            g.setColor(new Color(88, 111, 112));
            g.draw(new RoundRectangle2D.Double(left, top, mapW, mapH, 8, 8));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(MUTED);
            g.drawString("0", (float) (left - 13), (float) (top + mapH + 15));
            g.drawString(String.format(Locale.ROOT, "%.0f", worldW),
                (float) (left + mapW - 15), (float) (top + mapH + 17));
            g.drawString(String.format(Locale.ROOT, "%.0f", worldH),
                (float) (left - 27), (float) (top + 5));
            for (RenderSnapshot.Entity entity : snapshot.entities) {
                if (!Double.isFinite(entity.x) || !Double.isFinite(entity.y)) continue;
                double px = left + entity.x * scale;
                double py = top + (worldH - entity.y) * scale;
                if (entity.bullet) {
                    double r = Math.max(3, 0.3 * scale);
                    g.setColor(BULLET);
                    g.fill(new Ellipse2D.Double(px - r, py - r, r * 2, r * 2));
                } else drawTank(g, entity, px, py, scale);
            }
            if (snapshot.state.phase == SimulationStateData.Phase.WAITING_FOR_PREDICTION) {
                g.setColor(new Color(13, 22, 29, 200));
                g.fillRoundRect((int) left + 12, (int) top + 12, 248, 32, 8, 8);
                g.setColor(BULLET);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                g.drawString("HESAPLANIYOR — simülasyon saati durdu", (int) left + 22, (int) top + 33);
            }
            g.dispose();
        }

        private void drawTank(Graphics2D g, RenderSnapshot.Entity entity,
                double px, double py, double scale) {
            Color color = entity.player ? PLAYER : entity.target ? TARGET : MUTED;
            double size = Math.max(11, 1.8 * scale);
            AffineTransform previous = g.getTransform();
            g.translate(px, py);
            g.rotate(Math.atan2(-entity.headingY, entity.headingX));
            g.setColor(new Color(12, 21, 26));
            g.fill(new RoundRectangle2D.Double(-size, -size * 0.9, size * 2, size * 0.45, 3, 3));
            g.fill(new RoundRectangle2D.Double(-size, size * 0.45, size * 2, size * 0.45, 3, 3));
            g.setColor(color);
            g.fill(new RoundRectangle2D.Double(-size * 0.9, -size * 0.6,
                size * 1.8, size * 1.2, 5, 5));
            g.setColor(color.brighter());
            g.fill(new Ellipse2D.Double(-size * 0.4, -size * 0.4, size * 0.8, size * 0.8));
            g.setStroke(new BasicStroke(Math.max(3, (float) size * 0.23f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(0, 0, size * 1.65, 0));
            g.setTransform(previous);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setColor(color);
            String text = entity.player ? "OYUNCU" : entity.target ? "HEDEF" : entity.label;
            g.drawString(text, (float) (px - g.getFontMetrics().stringWidth(text) / 2.0),
                (float) (py - size - 7));
        }

        private double validDimension(Double value) {
            return value == null || !Double.isFinite(value) || value <= 0 ? 100 : value;
        }
    }
}
