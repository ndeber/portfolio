package name.abuchen.portfolio.updates.equity;

import static org.junit.Assert.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class EquityTaskTest
{
    @Test(timeout = 3000)
    public void cancelStopsWaitingAndInterruptsWorker() throws Exception
    {
        var cancel = new AtomicBoolean();
        var interrupted = new CountDownLatch(1);
        assertThrows(InterruptedException.class, () -> EquityTask.run(() -> {
            cancel.set(true);
            try { new CountDownLatch(1).await(); }
            finally { interrupted.countDown(); }
            return "unreachable";
        }, cancel::get, Duration.ofSeconds(30)));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    @Test(timeout = 3000)
    public void timeoutReturnsEvenIfParserIgnoresInterrupts() throws Exception
    {
        var release = new CountDownLatch(1);
        try
        {
            var error = assertThrows(IOException.class, () -> EquityTask.run(() -> {
                while (release.getCount() > 0)
                    try { release.await(); }
                    catch (InterruptedException ignored) { /* simulate a non-interruptible parser */ }
                return "late result";
            }, () -> false, Duration.ofMillis(150)));
            assertTrue(error.getMessage().contains("Délai"));
        }
        finally { release.countDown(); }
    }

    @Test(timeout = 3000)
    public void preCancelledTaskDoesNotRunAndLateCancelledResultIsDiscarded() throws Exception
    {
        var ran = new AtomicBoolean();
        assertThrows(InterruptedException.class, () -> EquityTask.run(() -> { ran.set(true);return 1; }, () -> true, Duration.ofSeconds(1)));
        assertFalse(ran.get());
        var cancel = new AtomicBoolean();
        assertThrows(InterruptedException.class, () -> EquityTask.run(() -> { cancel.set(true);return 1; }, cancel::get, Duration.ofSeconds(1)));
    }

    @Test(timeout = 3000)
    public void deliversResultAndReportsParserFailure() throws Exception
    {
        assertEquals("done", EquityTask.run(() -> "done", () -> false, Duration.ofSeconds(1)));
        var error = assertThrows(IOException.class, () -> EquityTask.run(() -> { throw new IllegalArgumentException("Invalid PDF"); }, () -> false, Duration.ofSeconds(1)));
        assertEquals("Invalid PDF", error.getMessage());
    }
}
