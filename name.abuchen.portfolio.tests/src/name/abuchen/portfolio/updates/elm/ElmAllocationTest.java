package name.abuchen.portfolio.updates.elm;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.Test;

public class ElmAllocationTest
{
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    private static String html(String rows)
    {
        return "<script id='alloc-json'>{\"datestamp\":\"2026-09-18T00:00:00Z\",\"rows\":[" + rows + "]}</script>";
    }

    @Test
    public void matchesOfficialFixtureAndRubyLargestRemainderRounding() throws Exception
    {
        try (var stream = getClass().getResourceAsStream("elm-allocation.html"))
        {
            var result = ElmAllocation.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), TODAY);
            assertEquals(new ElmAllocation(LocalDate.of(2026, 9, 18), 2883, 1235, 5882), result);
        }
    }

    @Test
    public void roundsTiesInCashBondsEquitiesOrderAndAllowsOmittedZeroBuckets() throws Exception
    {
        var result = ElmAllocation.parse(html("{\"bucket\":17,\"target\":0.333333333333},"
                        + "{\"bucket\":13,\"target\":0.333333333333},{\"bucket\":1,\"target\":0.333333333333}"), TODAY);
        assertEquals(3334, result.cash());
        assertEquals(3333, result.bonds());
        assertEquals(3333, result.equities());
    }

    @Test
    public void acceptsHtmlEscapedJson() throws Exception
    {
        var value = html("{\"bucket\":17,\"target\":1}").replace("\"", "&quot;");
        assertEquals(10000, ElmAllocation.parse(value, TODAY).cash());
    }

    @Test
    public void rejectsMissingBlockMalformedDataAndFutureOrMixedDates()
    {
        for (String value : new String[] { "<html>temporarily unavailable</html>", "<div id='alloc-json'>{bad}</div>",
                        html("{\"bucket\":17,\"target\":1}").replace("2026-09-18", "2026-09-23"),
                        html("{\"bucket\":17,\"target\":1,\"datestamp\":\"2026-09-17T00:00:00Z\"}") })
            assertThrows(IOException.class, () -> ElmAllocation.parse(value, TODAY));
    }

    @Test
    public void rejectsPartialNegativeDuplicateAndUnknownBuckets()
    {
        for (String rows : new String[] { "{\"bucket\":1,\"target\":1}", "{\"bucket\":17,\"target\":0.99}",
                        "{\"bucket\":17,\"target\":1.1},{\"bucket\":1,\"target\":-0.1}",
                        "{\"bucket\":17,\"target\":0.5},{\"bucket\":17,\"target\":0.5}",
                        "{\"bucket\":17,\"target\":1},{\"bucket\":18,\"target\":0}",
                        "{\"bucket\":17.5,\"target\":1}" })
            assertThrows(IOException.class, () -> ElmAllocation.parse(html(rows), TODAY));
    }
}
