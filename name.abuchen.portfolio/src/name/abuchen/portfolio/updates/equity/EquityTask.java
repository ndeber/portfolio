package name.abuchen.portfolio.updates.equity;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Bounds both download and parsing; cancellation never waits for a third-party parser. */
public final class EquityTask
{
    private EquityTask()
    {
    }

    public static <T> T run(Callable<T> operation, BooleanSupplier cancelled, Duration timeout)
                    throws IOException, InterruptedException
    {
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Le délai doit être positif.");
        if (cancelled.getAsBoolean())
            throw new InterruptedException();
        var task = new FutureTask<T>(operation);
        // Never hold the UI/modal thread, even if a library ignores interrupts.
        // The task only reads public sources; model mutation happens after preview.
        var worker = new Thread(task, "Actions composition");
        worker.setDaemon(true);
        long started = System.nanoTime();
        worker.start();
        try
        {
            while (true)
            {
                if (cancelled.getAsBoolean())
                    throw new InterruptedException();
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                if (remaining <= 0)
                    throw new IOException("Délai de lecture dépassé (" + timeout.toSeconds() + " s) : affectations conservées.");
                try
                {
                    T result = task.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                    if (cancelled.getAsBoolean())
                        throw new InterruptedException();
                    return result;
                }
                catch (TimeoutException e)
                {
                    // Poll cancellation while the download or parser is still running.
                }
                catch (ExecutionException e)
                {
                    var cause = e.getCause();
                    if (cause instanceof InterruptedException)
                        throw new InterruptedException();
                    throw new IOException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
                }
            }
        }
        finally
        {
            task.cancel(true);
        }
    }
}
