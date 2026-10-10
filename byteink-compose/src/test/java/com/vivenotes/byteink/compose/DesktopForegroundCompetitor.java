package com.vivenotes.byteink.compose;

import java.awt.EventQueue;
import java.io.*;
import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;

/** A separate GUI process that attempts the same foreground change as an activating console. */
final class DesktopForegroundCompetitor implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final NativeCalls api = new NativeCalls(arena, "user32.dll");
    private final Process process;
    private final BufferedReader replies;
    private final PrintWriter commands;

    DesktopForegroundCompetitor() throws Exception {
        String classpath = new File(DesktopForegroundCompetitor.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + new File(NativeCalls.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", classpath, getClass().getName())
                .redirectErrorStream(true).start();
        replies = process.inputReader(StandardCharsets.UTF_8);
        commands = new PrintWriter(process.outputWriter(StandardCharsets.UTF_8), true);
        try {
            if (!"ready".equals(reply())) throw new IllegalStateException("Foreground competitor did not start");
        } catch (Exception failure) { close(); throw failure; }
    }

    boolean activate() throws Exception {
        if (api.integer("AllowSetForegroundWindow", (int) process.pid()) == 0)
            throw new IllegalStateException("Cannot grant the foreground competitor activation permission");
        commands.println("activate");
        String result = reply();
        if (!"true".equals(result) && !"false".equals(result)) throw new IllegalStateException("Unexpected activation response: " + result);
        return Boolean.parseBoolean(result);
    }

    private String reply() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!replies.ready() && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        if (!replies.ready()) throw new IllegalStateException("No response from foreground competitor");
        return replies.readLine();
    }

    @Override public void close() {
        try {
            commands.println("close");
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        finally { commands.close(); arena.close(); }
    }

    public static void main(String[] args) throws Exception {
        String title = "ByteInk foreground competitor " + UUID.randomUUID();
        JFrame frame = new JFrame(title);
        EventQueue.invokeAndWait(() -> {
            frame.setAutoRequestFocus(false);
            frame.setBounds(500, 400, 160, 100);
            frame.setVisible(true);
        });
        try (Arena arena = Arena.ofConfined(); BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            NativeCalls api = new NativeCalls(arena, "user32.dll");
            MemorySegment hwnd = api.pointer("FindWindowW", MemorySegment.NULL, arena.allocateFrom(title, StandardCharsets.UTF_16LE));
            if (hwnd.address() == 0) throw new IllegalStateException("Competitor HWND is missing");
            System.out.println("ready");
            for (String command; (command = commands.readLine()) != null && !command.equals("close"); ) {
                if (!command.equals("activate")) throw new IllegalArgumentException(command);
                boolean activated = api.integer("SetForegroundWindow", hwnd) != 0;
                System.out.println(activated && api.pointer("GetForegroundWindow").address() == hwnd.address());
            }
        } finally { EventQueue.invokeAndWait(frame::dispose); }
    }
}
