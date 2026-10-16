package name.abuchen.portfolio.updates.bondallocation;

import static org.junit.Assert.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.updates.bondallocation.BondComposition.Family;

public class ElmBondAllocationTest
{
    private static final LocalDate DATE = LocalDate.of(2026, 10, 16);

    private String resource(String name) throws IOException
    {
        try (var stream = getClass().getResourceAsStream(name)) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private String schwab() throws IOException
    {
        try (var stream = ElmBondSources.class.getResourceAsStream("elm-schp-2026-10-15.csv"))
        { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test public void identifiesElmWithoutConfusingOtherIsinsOrSimilarNames()
    {
        var s = new Security("ELM unrelated company", "USD");
        assertFalse(ElmBondAllocation.matches(s));
        s.setTickerSymbol("ELM"); assertTrue(ElmBondAllocation.matches(s));
        s.setIsin("OTHER"); assertFalse(ElmBondAllocation.matches(s));
        s.setIsin(ElmBondAllocation.ISIN); assertTrue(ElmBondAllocation.matches(s));
        s.setIsin(null); s.setTickerSymbol(null); s.setName("Elm Market Navigator ETF"); assertTrue(ElmBondAllocation.matches(s));
    }

    @Test public void cancellationStopsBeforeAnySourceIsRead()
    {
        var security = new Security("ELM Market Navigator ETF", "USD"); security.setIsin(ElmBondAllocation.ISIN);
        var progress = new java.util.ArrayList<Family>();
        assertThrows(InterruptedException.class, () -> new BondAllocationSources().fetch(security, DATE, () -> true, progress::add));
        assertTrue(progress.isEmpty());
    }

    @Test public void fullActualPositionsIncludeSmallAggAndExcludeTreasuryBillsMoneyMarketAndEquities() throws Exception
    {
        var holdings = ElmBondAllocation.parseHoldings(resource("elm-holdings-2026-10-16.csv"), DATE);
        assertEquals(DATE, holdings.date()); assertEquals(java.util.Set.of("AGG", "BND", "SCHP"), holdings.bonds().keySet());
        assertEquals(14.290036487, holdings.fundPercent().doubleValue(), 0.000001);
        assertTrue(holdings.bonds().get("AGG").signum() > 0);
    }

    @Test public void invalidParentFilesNeverBecomeAnIncompleteBondAllocation() throws Exception
    {
        String csv = resource("elm-holdings-2026-10-16.csv");
        for (String invalid : List.of(csv.replace(",ELM,", ",OTHER,"), csv.replace("464287226", "WRONG"),
                        csv.replace("10/16/2026,ELM,AGG", "10/15/2026,ELM,AGG"), csv.replace("0.03%", "5.00%"),
                        csv.replace("596979480", "696979480"), csv + csv.lines().skip(1).findFirst().orElseThrow(),
                        csv.replaceFirst("(?m)^.*ELM,AGG,.*(?:\\R|$)", "")))
            assertThrows(IOException.class, () -> ElmBondAllocation.parseHoldings(invalid, DATE));
        assertThrows(IOException.class, () -> ElmBondAllocation.parseHoldings(csv, DATE.minusDays(1)));
        assertThrows(IOException.class, () -> ElmBondAllocation.parseHoldings(csv, DATE.plusDays(46)));
    }

    @Test public void schwabUsesEveryBondMaturityAndExcludesItsMoneyMarketFund() throws Exception
    {
        for (var family : Family.values())
        {
            var fund = ElmBondAllocation.parseSchwab(schwab(), family, DATE, true);
            assertEquals(99.7316403315, fund.bondPercent().doubleValue(), 0.0000001);
            assertTrue(fund.note().contains("COPIE OFFICIELLE EMBARQUÉE")); assertTrue(fund.note().contains("49 lignes"));
            assertFalse(fund.weights().containsKey("Cash"));
            if (family == Family.MATURITY) { assertTrue(fund.weights().containsKey("Plus de 25 ans")); assertFalse(fund.weights().containsKey("Non déterminée")); }
            if (family == Family.RATING) { assertEquals(java.util.Set.of("AA"), fund.weights().keySet()); assertTrue(fund.note().contains("Aa1")); }
        }
        assertThrows(IOException.class, () -> ElmBondAllocation.parseSchwab(schwab(), Family.MATURITY, DATE.plusDays(45), true));
        assertThrows(IOException.class, () -> ElmBondAllocation.parseSchwab(schwab().replace("3.9334795578", "-3.9334795578"), Family.MATURITY, DATE, true));
    }

    @Test public void vanguardUsGovernmentUsesUsRatingAndKeepsTinySubInvestmentGradeExposures() throws Exception
    {
        var fund = ElmBondAllocation.parseVanguard(resource("elm-bnd-holdings.json"), resource("elm-bnd-credit.json"), Family.RATING, DATE);
        assertEquals(72.640178126, fund.weights().get("AA").doubleValue(), 0.0000001);
        assertTrue(fund.weights().containsKey("BB")); assertTrue(fund.weights().containsKey("CCC et inférieur"));
        assertTrue(fund.note().contains("note souveraine US")); assertEquals(99, fund.bondPercent().doubleValue(), 0.000001);
        assertThrows(IOException.class, () -> ElmBondAllocation.parseVanguard(resource("elm-bnd-holdings.json"), "[]", Family.RATING, DATE));
    }

    @Test public void vanguardTbaSettlementNeverBecomesShortTermMaturityAndIssuerSeparationIsExplicit() throws Exception
    {
        var maturity = ElmBondAllocation.parseVanguard(resource("elm-bnd-holdings.json"), "", Family.MATURITY, DATE);
        assertEquals(new BigDecimal("0.5"), maturity.weights().get("Non déterminée"));
        assertFalse(maturity.weights().containsKey("< 6 mois"));
        var issuer = ElmBondAllocation.parseVanguard(resource("elm-bnd-holdings.json"), resource("elm-bnd-issuers.json"), Family.ISSUER, DATE);
        assertEquals(new BigDecimal("48.47675"), issuer.weights().get("Gouvernement"));
        assertEquals(new BigDecimal("0.94325"), issuer.weights().get("Agences publiques & supranationaux"));
        assertEquals(new BigDecimal("3.94"), issuer.weights().get("Autres"));
        assertThrows(IOException.class, () -> ElmBondAllocation.parseVanguard(resource("elm-bnd-holdings.json"),
                        resource("elm-bnd-issuers.json").replace("2026-09-30", "2026-08-31"), Family.ISSUER, DATE));
    }

    @Test public void vanguardRegionsStayRegionsInsteadOfInventingUsCountryForUsdBonds() throws Exception
    {
        var fund = ElmBondAllocation.parseVanguard(resource("elm-bnd-holdings.json"), resource("elm-bnd-regions.json"), Family.REGIONS, DATE);
        assertEquals(LocalDate.of(2026, 9, 30), fund.date()); assertTrue(fund.weights().containsKey("Amérique du Nord"));
        assertFalse(fund.weights().containsKey("États-Unis")); assertTrue(fund.weights().get("Autres").signum() > 0);
    }

    private String agg()
    {
        return "iShares Core U.S. Aggregate Bond ETF\nFund Holdings as of,\"Oct 15, 2026\"\n\n"
                        + "Name,Sector,Asset Class,Market Value,Location,Maturity\n"
                        + "TREASURY NOTE,Treasury,Fixed Income,600,United States,\"Oct 16, 2028\"\n"
                        + "BANK,Financial Institutions,Fixed Income,300,France,\"Oct 16, 2036\"\n"
                        + "USD CASH,Cash and/or Derivatives,Cash,100,United States,-\n";
    }

    @Test public void isharesUsesMarketValuesForTinyWeightsAndFiltersCashBeforeLookThrough() throws Exception
    {
        var maturity = ElmBondAllocation.parseIshares(agg(), "", Family.MATURITY, DATE);
        assertEquals(90, maturity.bondPercent().doubleValue(), 0.000001);
        assertEquals(Map.of("< 2 ans", new BigDecimal("600"), "7-10 ans", new BigDecimal("300")), maturity.weights());
        var rating = ElmBondAllocation.parseIshares(agg(), resource("elm-agg-rating.html"), Family.RATING, DATE);
        assertTrue(rating.weights().containsKey("AA")); assertFalse(rating.weights().containsKey("Cash and/or Derivatives"));
        var issuer = ElmBondAllocation.parseIshares(agg(), "", Family.ISSUER, DATE);
        assertEquals(new BigDecimal("300"), issuer.weights().get("Banques & institutions financières"));
        assertThrows(IOException.class, () -> ElmBondAllocation.parseIshares(agg().replace("Financial Institutions", "Unknown sector"), "", Family.ISSUER, DATE));
        assertThrows(IOException.class, () -> ElmBondAllocation.parseIshares(agg(), "", Family.RATING, DATE));
    }

    @Test public void finalRoundingPreservesOnlyNetBondExposureInsteadOfScalingElmToOneHundredPercent()
    {
        var h = new ElmBondAllocation.Holdings(DATE, Map.of("SCHP", new BigDecimal("10"), "BND", new BigDecimal("5")), new BigDecimal("15"));
        var a = new ElmBondAllocation.Fund(new BigDecimal("99"), Map.of("AA", BigDecimal.ONE), DATE, "source", "note");
        var b = new ElmBondAllocation.Fund(new BigDecimal("98"), Map.of("AAA", BigDecimal.ONE, "AA", BigDecimal.ONE), DATE, "source", "note");
        var slice = ElmBondAllocation.combine(h, Map.of("SCHP", a, "BND", b), Family.RATING);
        assertEquals(1480, slice.coverage()); assertEquals(Map.of("AA", 1235, "AAA", 245), slice.weights());
        assertThrows(IllegalArgumentException.class, () -> ElmBondAllocation.combine(h, Map.of("SCHP", a), Family.RATING));
        var zero = ElmBondAllocation.combine(new ElmBondAllocation.Holdings(DATE, Map.of(), BigDecimal.ZERO), Map.of(), Family.RATING);
        assertEquals(0, zero.coverage()); assertTrue(zero.weights().isEmpty());
    }
}
