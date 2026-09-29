package name.abuchen.portfolio.updates.equity;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.updates.equity.EquityComposition.Family;

public class VanguardWorldCountriesTest
{
    private JsonObject fixture() throws Exception
    {
        try (var input = getClass().getResourceAsStream("vanguard-world.json"))
        {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test public void completeIssuerDataUsesFundFtseWeightsAndExactPpTotal() throws Exception
    {
        var slice = VanguardWorldCountries.parse(fixture().toString());
        var weights = EquityComposition.weights(slice, Family.REGIONS, "Other");
        assertEquals(LocalDate.of(2026, 8, 31), slice.date());
        assertFalse(slice.staticProfile());
        assertEquals(50, slice.items().size());
        assertEquals(10000, weights.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(Integer.valueOf(6168), weights.get("États-Unis"));
        assertEquals(Integer.valueOf(250), weights.get("Corée du Sud"));
        assertEquals(Integer.valueOf(600), weights.get("Japon"));
        assertTrue(weights.containsKey("Arabie Saoudite"));
        assertFalse(weights.containsKey("Other"));
        assertEquals(VanguardWorldCountries.SOURCE, slice.source());
        assertTrue(JsonParser.parseString(VanguardWorldCountries.REQUEST).getAsJsonObject()
                        .get("query").getAsString().contains("[\"9679\"]"));
    }

    @Test public void rejectsTruncatedDuplicateWrongIdentityAndInconsistentDates() throws Exception
    {
        for (String mutation : java.util.List.of("truncated", "duplicate", "identity", "date", "unknown", "missing", "excess"))
        {
            var json = fixture();
            var fund = json.getAsJsonObject("data").getAsJsonArray("funds").get(0).getAsJsonObject();
            var rows = fund.getAsJsonArray("marketAllocation");
            switch (mutation)
            {
                case "truncated" -> { while (rows.size() > 15) rows.remove(rows.size() - 1); }
                case "duplicate" -> rows.add(rows.get(0).deepCopy());
                case "identity" -> rows.get(0).getAsJsonObject().addProperty("portId", "9507");
                case "date" -> rows.get(0).getAsJsonObject().addProperty("date", "2026-07-31");
                case "unknown" -> rows.get(0).getAsJsonObject().addProperty("countryCode", "ZZ");
                case "missing" -> rows.remove(0);
                case "excess" -> rows.get(0).getAsJsonObject().addProperty("fundMktPercent", 5);
            }
            assertThrows(mutation, IOException.class, () -> VanguardWorldCountries.parse(json.toString()));
        }
        assertThrows(IOException.class, () -> VanguardWorldCountries.parse("{\"errors\":[]}"));
        assertThrows(IOException.class, () -> VanguardWorldCountries.parse("<html>Unavailable</html>"));
    }
}
