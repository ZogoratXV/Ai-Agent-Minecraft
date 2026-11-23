package dev.tessera.scheduler;

import dev.tessera.stub.StubBukkitTask;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Un task dello scheduler: eseguito al tick {@code nextRun}, poi ogni {@code period} tick se > 0. */
public final class TesseraTask extends StubBukkitTask {

    private final int id;
    private final Plugin owner;
    private final Object body;
    private final boolean sync;
    final long period;
    long nextRun;
    private volatile boolean cancelled;
    volatile boolean running;

    TesseraTask(int id, Plugin owner, Object body, boolean sync, long nextRun, long period) {
        this.id = id;
        this.owner = owner;
        this.body = body;
        this.sync = sync;
        this.nextRun = nextRun;
        this.period = period;
    }

    @Override
    protected String tesseraStubName() {
        return "BukkitTask";
    }

    @SuppressWarnings("unchecked")
    void run() {
        running = true;
        try {
            if (body instanceof Runnable r) r.run();
            else ((Consumer<BukkitTask>) body).accept(this);
        } finally {
            running = false;
        }
    }

    @Override
    public int getTaskId() {
        return id;
    }

    @Override
    public Plugin getOwner() {
        return owner;
    }

    @Override
    public boolean isSync() {
        return sync;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }
}
