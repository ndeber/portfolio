package name.abuchen.portfolio.updates.bondallocation;

import static org.junit.Assert.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.Test;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Family;

public class BondAllocationSourcesTest
{
    @Test public void maturityBoundariesUseCalendarMonthsAndRollForward()
    {
        var date = LocalDate.of(2026, 8, 31);
        assertEquals("Cash", BondAllocationSources.maturityBucket(date, date, true));
        assertEquals("< 6 mois", BondAllocationSources.maturityBucket(LocalDate.of(2027, 2, 28), date, true));
        assertEquals("< 1 an", BondAllocationSources.maturityBucket(LocalDate.of(2027, 3, 1), date, true));
        assertEquals("< 5 ans", BondAllocationSources.maturityBucket(date.plusYears(5), date, true));
        assertEquals("5-7 ans", BondAllocationSources.maturityBucket(date.plusYears(5).plusDays(1), date, true));
        assertEquals("Plus de 25 ans", BondAllocationSources.maturityBucket(date.plusYears(25).plusDays(1), date, true));
        assertEquals("< 6 mois", BondAllocationSources.maturityBucket(date, date, false));
    }
    @Test public void fixedProfilesDiscloseLackOfPublicationDateAndAlwaysSumToOneHundred()
    {
        var date = LocalDate.of(2026, 9, 24);
        for (String isin : java.util.List.of("LU1041599405", "FR001400UP68", "FR001400S425", "FR001400TS84", "LU1165644672",
                        "FR001400S0P1", "FR001400U4U9", "FR001400UG93", "LU1995645790", "DE000BU2Z072", "FR0013327491"))
            for (var f : Family.values())
            {
                var data = BondAllocationSources.staticSlice(isin, f, date);
                if (data == null) { assertEquals(Family.REGIONS, f); continue; }
                assertEquals(10000, data.weights().values().stream().mapToInt(Integer::intValue).sum());
                assertNull(data.date()); assertFalse(data.note().isBlank());
            }
        assertEquals(Map.of("5-7 ans", 10000), BondAllocationSources.staticSlice("FR001400UP68", Family.MATURITY, date).weights());
        assertEquals(Map.of("< 5 ans", 10000), BondAllocationSources.staticSlice("FR001400UP68", Family.MATURITY, date.plusYears(1)).weights());
        assertNull(BondAllocationSources.staticSlice("UNKNOWN", Family.RATING, date));
    }
    @Test public void roundingResidualAndExplicitPositiveExposureConvention()
    {
        assertEquals(Map.of("France", 2500, "Autres", 7500), BondAllocationSources.percentages(Map.of("France", new BigDecimal("25")), "Autres", false));
        assertThrows(IllegalArgumentException.class, () -> BondAllocationSources.percentages(Map.of("A", new BigDecimal("101")), "Other", false));
        assertThrows(IllegalArgumentException.class, () -> BondAllocationSources.percentages(Map.of("A", new BigDecimal("-1")), "Other", false));
        var weights = BondAllocationSources.percentages(Map.of("A", BigDecimal.ONE, "B", BigDecimal.ONE, "C", BigDecimal.ONE, "Other", new BigDecimal("-0.1")), "Other", true);
        assertEquals(10000, weights.values().stream().mapToInt(Integer::intValue).sum());
        assertFalse(weights.containsKey("Other")); assertEquals(Integer.valueOf(3334), weights.get("A"));
        assertEquals("Europe", BondAllocationSources.region("Norway"));
        assertEquals("États-Unis", BondAllocationSources.region("United States of America"));
        assertEquals("Autres", BondAllocationSources.region("Unknown"));
    }
    private String page(int count, String cursor, String items)
    {
        return "{\"data\":{\"borHoldings\":[{\"holdings\":{\"items\":[" + items + "],\"totalHoldings\":" + count + ",\"lastItemKey\":" + cursor + "}}]}}";
    }
    private String item(String date, String weight)
    {
        return "{\"finalMaturity\":" + date + ",\"marketValuePercentage\":" + weight + "}";
    }
    @Test public void completeMaturityPaginationAndMissingDateCash() throws Exception
    {
        var pages = new BondAllocationSources.MaturityPages(LocalDate.of(2026, 9, 24));
        assertEquals("next", pages.accept(page(3, "\"next\"", item("\"2027-03-24\"", "0.25") + "," + item("null", "0.25"))));
        assertThrows(IllegalStateException.class, pages::result);
        assertEquals("", pages.accept(page(3, "null", item("\"2030-09-24T00:00:00\"", "0.50"))));
        assertEquals(Map.of("< 6 mois", 2500, "Cash", 2500, "< 4 ans", 5000), pages.result().weights());
        assertNull(pages.result().date());
    }
    @Test public void rejectsIncompleteRepeatedOrChangingPagination() throws Exception
    {
        var pages = new BondAllocationSources.MaturityPages(LocalDate.now());
        assertThrows(IOException.class, () -> pages.accept(page(2, "null", item("null", "1"))));
        var repeating = new BondAllocationSources.MaturityPages(LocalDate.now());
        repeating.accept(page(3, "\"x\"", item("null", "1")));
        assertThrows(IOException.class, () -> repeating.accept(page(3, "\"x\"", item("null", "1"))));
        var changing = new BondAllocationSources.MaturityPages(LocalDate.now());
        changing.accept(page(3, "\"x\"", item("null", "1")));
        assertThrows(IOException.class, () -> changing.accept(page(4, "null", item("null", "1"))));
        assertThrows(IOException.class, () -> new BondAllocationSources.MaturityPages(LocalDate.now()).accept("{\"errors\":[]}"));
    }
    @Test public void ratingUsesCorrectDateAndNeverConfusesBbbWithBelowBbb() throws Exception
    {
        String html = "Market allocation As at 31 Jul 2026 Distribution by credit quality (% of funds) As at 31 Aug 2026 AAA 10% AA 40% A 25% Less than BBB 0% BBB 20% Not Rated 5% Distribution by credit issuer (% of funds)";
        var slice = BondAllocationSources.parseVanguard(html, Family.RATING);
        assertEquals(LocalDate.of(2026, 8, 31), slice.date()); assertEquals(Integer.valueOf(2000), slice.weights().get("BBB"));
        assertThrows(IOException.class, () -> BondAllocationSources.parseVanguard(html.replace("Less than BBB 0%", "Less than BBB 1%"), Family.RATING));
        assertThrows(IOException.class, () -> BondAllocationSources.parseVanguard("No table", Family.RATING));
    }
    @Test public void issuerTableRequiresCompleteLabelsAndConsistentTotal() throws Exception
    {
        String html = "Market allocation As at 31 Aug 2026 Distribution by credit issuer (% of funds) As at 31 Aug 2026 "
                        + "Treasury/Federal 60% Gov-Related-Provincials/Municipals 0% Gov-Related-Local Authority 0% Gov-Related-Sovereign 0% "
                        + "Gov-Related-Agencies 0% Gov-Related-Supranational 0% Corporate-Financial Institutions 20% Corporate-Industrials 20% Corporate-Utilities 0% "
                        + "Securitized-Mortgage Backed Security Pass-through 0% Securitized-Asset Backed Security 0% Securitized-Commercial Mortgage Backed Security 0% "
                        + "Cash 0% Other 0% Distribution by credit maturity (% of funds)";
        assertEquals(Map.of("Gouvernement", 6000, "Banques & institutions financières", 2000, "Entreprises", 2000),
                        BondAllocationSources.parseVanguard(html, Family.ISSUER).weights());
        assertThrows(IOException.class, () -> BondAllocationSources.parseVanguard(html.replace("Cash 0%", ""), Family.ISSUER));
        assertThrows(IOException.class, () -> BondAllocationSources.parseVanguard(html.replace("Treasury/Federal 60%", "Treasury/Federal 50%"), Family.ISSUER));
    }
}
