package com.emp.predictor;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The player's ticket log, kept in app storage. */
public final class TicketStore {
    private static final String FILE = "tickets.txt";
    private static final Object LOCK = new Object();

    private TicketStore() {}

    /** All tickets, newest draw first. */
    public static List<Ticket> load(Context context) {
        synchronized (LOCK) {
            File f = new File(context.getFilesDir(), FILE);
            if (!f.exists()) return new ArrayList<>();
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                List<Ticket> out = Ticket.decodeAll(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                Collections.sort(out);
                return out;
            } catch (IOException e) {
                return new ArrayList<>();
            }
        }
    }

    public static List<Ticket> forGame(Context context, Game game) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : load(context)) if (t.gameId.equals(game.id)) out.add(t);
        return out;
    }

    /** Adds or replaces (same id) a ticket. */
    public static void save(Context context, Ticket ticket) throws IOException {
        synchronized (LOCK) {
            List<Ticket> all = load(context);
            for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id == ticket.id) all.remove(i);
            all.add(ticket);
            write(context, all);
        }
    }

    public static void delete(Context context, long id) throws IOException {
        synchronized (LOCK) {
            List<Ticket> all = load(context);
            for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).id == id) all.remove(i);
            write(context, all);
        }
    }

    /** The logged ticket with these numbers for that draw, or null. */
    public static Ticket find(Context context, Game game, String drawDate, int[] main, int[] extra) {
        for (Ticket t : load(context)) if (t.sameLine(game.id, drawDate, main, extra)) return t;
        return null;
    }

    private static void write(Context context, List<Ticket> all) throws IOException {
        Collections.sort(all);
        File tmp = new File(context.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(Ticket.encodeAll(all).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(context.getFilesDir(), FILE))) throw new IOException("Could not save tickets");
    }
}
