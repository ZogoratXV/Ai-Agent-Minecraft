package dev.tessera.scheduler;

import dev.tessera.stub.StubBukkitScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

/**
 * Scheduler Bukkit guidato dai tick del server ({@link #tick()} viene chiamato a fine tick).
 * I task sincroni girano sul thread del server; quelli asincroni su un pool di thread quando
 * scade il loro ritardo in tick.
 *
 * <p>Le firme usano tipi "raw" (Consumer, Class...) perché devono coincidere con quelle degli
 * stub generati.
 */
public final class TesseraScheduler extends StubBukkitScheduler {

    private final AtomicInteger ids = new AtomicInteger(1);
    private final AtomicLong currentTick = new AtomicLong();
    private final Map<Integer, TesseraTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService async = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Tessera Scheduler Async");
        t.setDaemon(true);
        return t;
    });

    @Override
    protected String tesseraStubName() {
        return "BukkitScheduler";
    }

    public void tick() {
        long now = currentTick.incrementAndGet();
        List<TesseraTask> due = new ArrayList<>();
        for (TesseraTask task : tasks.values()) {
            if (task.isCancelled()) {
                tasks.remove(task.getTaskId());
            } else if (task.nextRun <= now && !task.running) {
                due.add(task);
            }
        }
        due.sort((a, b) -> Integer.compare(a.getTaskId(), b.getTaskId()));
        for (TesseraTask task : due) {
            if (task.period > 0) task.nextRun = now + task.period;
            else tasks.remove(task.getTaskId());
            if (task.isSync()) execute(task);
            else async.execute(() -> execute(task));
        }
    }

    private void execute(TesseraTask task) {
        if (task.isCancelled() || !task.getOwner().isEnabled()) {
            tasks.remove(task.getTaskId());
            return;
        }
        try {
            task.run();
        } catch (Throwable t) {
            task.getOwner().getLogger().log(Level.WARNING,
                    "Il task #" + task.getTaskId() + " di " + task.getOwner().getName() + " ha generato un'eccezione", t);
        }
    }

    public void cancelAll() {
        tasks.values().forEach(TesseraTask::cancel);
        tasks.clear();
    }

    private TesseraTask schedule(Plugin plugin, Object body, boolean sync, long delay, long period) {
        if (plugin == null || !plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Il plugin " + (plugin == null ? "null" : plugin.getName())
                    + " ha provato a registrare un task mentre è disabilitato");
        }
        // Ritardo 0 = "al prossimo tick", come in Bukkit.
        long start = currentTick.get() + Math.max(1, delay);
        TesseraTask task = new TesseraTask(ids.getAndIncrement(), plugin, body, sync, start, period <= 0 ? 0 : period);
        tasks.put(task.getTaskId(), task);
        return task;
    }

    // ---------------------------------------------------------------- runTask*

    @Override
    public BukkitTask runTask(Plugin plugin, Runnable task) {
        return schedule(plugin, task, true, 0, 0);
    }

    @Override
    public void runTask(Plugin plugin, Consumer task) {
        schedule(plugin, task, true, 0, 0);
    }

    @Override
    public BukkitTask runTask(Plugin plugin, BukkitRunnable task) {
        return runTask(plugin, (Runnable) task);
    }

    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        return schedule(plugin, task, false, 0, 0);
    }

    @Override
    public void runTaskAsynchronously(Plugin plugin, Consumer task) {
        schedule(plugin, task, false, 0, 0);
    }

    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, BukkitRunnable task) {
        return runTaskAsynchronously(plugin, (Runnable) task);
    }

    @Override
    public BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        return schedule(plugin, task, true, delay, 0);
    }

    @Override
    public void runTaskLater(Plugin plugin, Consumer task, long delay) {
        schedule(plugin, task, true, delay, 0);
    }

    @Override
    public BukkitTask runTaskLater(Plugin plugin, BukkitRunnable task, long delay) {
        return runTaskLater(plugin, (Runnable) task, delay);
    }

    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        return schedule(plugin, task, false, delay, 0);
    }

    @Override
    public void runTaskLaterAsynchronously(Plugin plugin, Consumer task, long delay) {
        schedule(plugin, task, false, delay, 0);
    }

    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, BukkitRunnable task, long delay) {
        return runTaskLaterAsynchronously(plugin, (Runnable) task, delay);
    }

    @Override
    public BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        return schedule(plugin, task, true, delay, Math.max(1, period));
    }

    @Override
    public void runTaskTimer(Plugin plugin, Consumer task, long delay, long period) {
        schedule(plugin, task, true, delay, Math.max(1, period));
    }

    @Override
    public BukkitTask runTaskTimer(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return runTaskTimer(plugin, (Runnable) task, delay, period);
    }

    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        return schedule(plugin, task, false, delay, Math.max(1, period));
    }

    @Override
    public void runTaskTimerAsynchronously(Plugin plugin, Consumer task, long delay, long period) {
        schedule(plugin, task, false, delay, Math.max(1, period));
    }

    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, (Runnable) task, delay, period);
    }

    // ---------------------------------------------------------------- API "schedule*" (deprecate ma molto usate)

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        return runTaskLater(plugin, task, delay).getTaskId();
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, Runnable task) {
        return runTask(plugin, task).getTaskId();
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task, long delay) {
        return scheduleSyncDelayedTask(plugin, (Runnable) task, delay);
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task) {
        return scheduleSyncDelayedTask(plugin, (Runnable) task);
    }

    @Override
    public int scheduleSyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
        return runTaskTimer(plugin, task, delay, period).getTaskId();
    }

    @Override
    public int scheduleSyncRepeatingTask(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return scheduleSyncRepeatingTask(plugin, (Runnable) task, delay, period);
    }

    @Override
    public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        return runTaskLaterAsynchronously(plugin, task, delay).getTaskId();
    }

    @Override
    public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task) {
        return runTaskAsynchronously(plugin, task).getTaskId();
    }

    @Override
    public int scheduleAsyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, task, delay, period).getTaskId();
    }

    @Override
    public Future callSyncMethod(Plugin plugin, Callable task) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        runTask(plugin, () -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public Executor getMainThreadExecutor(Plugin plugin) {
        return command -> runTask(plugin, command);
    }

    // ---------------------------------------------------------------- gestione

    @Override
    public void cancelTask(int taskId) {
        TesseraTask task = tasks.remove(taskId);
        if (task != null) task.cancel();
    }

    @Override
    public void cancelTasks(Plugin plugin) {
        tasks.values().removeIf(t -> {
            if (t.getOwner() == plugin) {
                t.cancel();
                return true;
            }
            return false;
        });
    }

    @Override
    public boolean isCurrentlyRunning(int taskId) {
        TesseraTask task = tasks.get(taskId);
        return task != null && task.running;
    }

    @Override
    public boolean isQueued(int taskId) {
        TesseraTask task = tasks.get(taskId);
        return task != null && !task.isCancelled();
    }

    @Override
    public List<BukkitWorker> getActiveWorkers() {
        return List.of();
    }

    @Override
    public List<BukkitTask> getPendingTasks() {
        return new ArrayList<>(tasks.values());
    }
}
