package name.abuchen.portfolio.factors;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class RateSensitivityTest
{
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

    private RateSettings.Mapping mapping(RateSettings.Curve curve, double duration)
    {
        return new RateSettings.Mapping(DATE.toString(), "Donnée vérifiée", curve, 0d, duration, 0d);
    }

    private FactorMap.Position position(String name, String isin, Long value, boolean dbi)
    {
        var security = new Security(name, "EUR");
        security.setIsin(isin);
        return new FactorMap.Position(security, value, DATE, dbi, Map.of(), Map.of(), "");
    }

    private FactorMap.Result portfolio(FactorMap.Position... positions)
    {
        return new FactorMap.Result(List.of(positions), List.of(), 10000000, true, 0, 0d, "Test", List.of());
    }

    private void save(Client client, FactorMap.Position position, RateSettings.Mapping mapping)
    {
        RateSettings.save(client, RateSettings.load(client).with(position.vehicle().getUUID(), mapping), client.getProperty(RateSettings.PROPERTY));
    }

    private RateSensitivity.Row row(RateSensitivity.Result result, RateSensitivity.Family family)
    {
        return result.rows().stream().filter(r -> r.family() == family && r.segment() == -1).findFirst().orElseThrow();
    }

    @Test public void durationMatchesZeroCouponAndPreservesTotalAcrossSegments()
    {
        var maturity = DATE.plusYears(10);
        var bond = new RateSensitivity.Bond(maturity, 0, null, null);
        double price = 100 / Math.pow(1.04, 10);
        double[] d = RateSensitivity.durations(bond, DATE, price);
        assertEquals(10 / 1.04, Arrays.stream(d).sum(), 1e-10);
        assertEquals(0, d[0], 1e-10);
        assertEquals(0, d[2], 1e-10);
        double bump = (100 / Math.pow(1.040001, 10) - 100 / Math.pow(1.039999, 10)) / .000002;
        assertEquals(-bump / price, Arrays.stream(d).sum(), 1e-7);
        assertThrows(IllegalArgumentException.class, () -> RateSensitivity.durations(bond, maturity, price));
        assertThrows(IllegalArgumentException.class, () -> RateSensitivity.durations(bond, DATE, Double.NaN));
    }

    @Test public void couponCashFlowsContributeToMoreThanOneSegment()
    {
        var bond = new RateSensitivity.Bond(DATE.plusYears(20), .04, null, null);
        var d = RateSensitivity.durations(bond, DATE, 100);
        assertTrue(d[0] > 0); assertTrue(d[1] > 0); assertTrue(d[2] > 0);
        double expected = (1 - Math.pow(1.04, -20)) / .04;
        assertEquals(expected, Arrays.stream(d).sum(), 1e-9);
    }

    @Test public void firstLongCouponIsIncluded()
    {
        LocalDate first = DATE.plusYears(1);
        var bond = new RateSensitivity.Bond(first, .04, DATE.minusDays(50), first);
        double coupon = 4 * (1 + 50d / 365);
        var d = RateSensitivity.durations(bond, DATE, (100 + coupon) / 1.03);
        assertEquals(1 / 1.03, Arrays.stream(d).sum(), 1e-10);
    }

    @Test public void nominalDecompositionDoesNotDoubleCountAndRealRatesStaySeparate()
    {
        var fr = mapping(RateSettings.Curve.FR, 6);
        assertEquals(6, RateSensitivity.exposure(fr, RateSensitivity.Family.COMMON, -1), 0);
        assertEquals(6, RateSensitivity.exposure(fr, RateSensitivity.Family.DE_US, -1), 0);
        assertEquals(6, RateSensitivity.exposure(fr, RateSensitivity.Family.FR_DE, -1), 0);
        assertEquals(0, RateSensitivity.exposure(fr, RateSensitivity.Family.REAL, -1), 0);
        var real = mapping(RateSettings.Curve.REAL_EUR, 6);
        assertEquals(0, RateSensitivity.exposure(real, RateSensitivity.Family.COMMON, -1), 0);
        assertEquals(6, RateSensitivity.exposure(real, RateSensitivity.Family.REAL, -1), 0);
    }

    @Test public void matchingUsShortOffsetsCommonButLeavesGermanSpread()
    {
        var client = new Client();
        var bund = position("Bund", "DETEST", 10000000L, false);
        var dbi = position("DBMF", FactorMap.DBMF, 5000000L, true);
        save(client, bund, mapping(RateSettings.Curve.DE, 5));
        save(client, dbi, mapping(RateSettings.Curve.US, -10));
        var result = RateSensitivity.calculate(client, portfolio(bund, dbi), DATE, DbiRateData.bundled());
        var common = row(result, RateSensitivity.Family.COMMON);
        assertEquals(50, common.baseline(), 1e-10);
        assertEquals(-50, common.overlay(), 1e-10);
        assertEquals(0, common.after(), 1e-10);
        assertEquals(50, row(result, RateSensitivity.Family.DE_US).after(), 1e-10);
        assertEquals(0, row(result, RateSensitivity.Family.FR_DE).after(), 1e-10);
    }

    @Test public void publishedUcitsDurationHasCorrectScaleAndNoInventedGeographicSpreads()
    {
        var dbi = position("DBi EUR", FactorMap.UCITS_HEDGED, 10000000L, true);
        var result = RateSensitivity.calculate(new Client(), portfolio(dbi), DATE, DbiRateData.bundled());
        assertEquals(-69, row(result, RateSensitivity.Family.COMMON).overlay(), 1e-10);
        assertNull(row(result, RateSensitivity.Family.DE_US).overlay());
        assertNull(row(result, RateSensitivity.Family.FR_DE).after());
        var us = position("DBMF", FactorMap.DBMF, 10000000L, true);
        assertNull(row(RateSensitivity.calculate(new Client(), portfolio(us), DATE, DbiRateData.bundled()), RateSensitivity.Family.COMMON).overlay());
        assertNull(row(RateSensitivity.calculate(new Client(), portfolio(dbi), LocalDate.of(2026, 8, 1), DbiRateData.bundled()), RateSensitivity.Family.COMMON).overlay());
    }

    @Test public void unknownsRemainUnknownAndCoverageIsNotRenormalized()
    {
        var client = new Client();
        var bund = position("Bund", "DETEST", 10000000L, false);
        var unknown = position("Fonds EUR hedgé", null, 10000000L, false);
        save(client, bund, mapping(RateSettings.Curve.DE, 5));
        var result = RateSensitivity.calculate(client, portfolio(bund, unknown), DATE, DbiRateData.bundled());
        assertEquals(.5, row(result, RateSensitivity.Family.COMMON).coverage(), 0);
        assertEquals(50, row(result, RateSensitivity.Family.COMMON).after(), 1e-10);
        assertNull(result.details().get(1).dv01());
        assertEquals(10000000, result.documentedValue());
    }

    @Test public void automaticBundUsesDirtyConventionAndDatedQuoteWithoutChangingPortfolio()
    {
        var bund = position("Bund 2029", "DE0001102622", 10000000L, false);
        var security = (Security) bund.vehicle();
        security.setFeed("MANUAL");
        security.setNote("[XAPA-BOND-PRICES-BEGIN]\nConvention Portfolio Performance : prix dirty\n[XAPA-BOND-PRICES-END]");
        security.addPrice(new SecurityPrice(DATE, 98L * Values.Quote.factor()));
        var client = new Client();
        var result = RateSensitivity.calculate(client, portfolio(bund), DATE, DbiRateData.bundled());
        assertTrue(result.details().getFirst().dv01() > 20);
        assertEquals(row(result, RateSensitivity.Family.COMMON).baseline(), row(result, RateSensitivity.Family.DE_US).baseline());
        assertNull(client.getProperty(RateSettings.PROPERTY));
        security.setFeed("YAHOO");
        assertNull(RateSensitivity.calculate(client, portfolio(bund), DATE, DbiRateData.bundled()).details().getFirst().dv01());
    }

    @Test public void oatEiIsRecognizedWithoutAssumingMissingIndexationCoefficient()
    {
        var oat = position("OAT€i 2036", "FR0013327491", 10000000L, false);
        var security = (Security) oat.vehicle();
        security.setFeed("MANUAL");
        security.setNote("[XAPA-BOND-PRICES-BEGIN]\nConvention Portfolio Performance : prix dirty\nDate de cotation : " + DATE + "\nCoefficient AFT : 1.3\n[XAPA-BOND-PRICES-END]");
        security.addPrice(new SecurityPrice(DATE, 130L * Values.Quote.factor()));
        var r = RateSensitivity.calculate(new Client(), portfolio(oat), DATE, DbiRateData.bundled());
        assertEquals(0, row(r, RateSensitivity.Family.COMMON).baseline(), 0);
        assertTrue(row(r, RateSensitivity.Family.REAL).baseline() > 90);
        security.setNote(security.getNote().replace("Coefficient AFT : 1.3", ""));
        r = RateSensitivity.calculate(new Client(), portfolio(oat), DATE, DbiRateData.bundled());
        assertNull(row(r, RateSensitivity.Family.REAL).baseline());
        assertEquals(0, row(r, RateSensitivity.Family.COMMON).baseline(), 0);
    }

    @Test public void parserRejectsMissingSegmentsWrongShareAndFutureDate()
    {
        String text = "Fact Sheet – August 31, 2026 " + FactorMap.UCITS_HEDGED + " Fixed Income Derivatives Duration "
                        + "Long Maturities -2.3 Intermediate -2.7 Maturities Short Maturities -1.9 Holdings and sector allocations";
        assertEquals(DbiRateData.bundled(), DbiRateData.parse(text, DATE));
        String columns = text.replace("Short Maturities", "Short\nMarkets Maturities")
                        .replace("Intermediate -2.7 Maturities", "Intermediate -2.7\nEquities Maturities");
        assertEquals(DbiRateData.bundled(), DbiRateData.parse(columns, DATE));
        assertThrows(IllegalArgumentException.class, () -> DbiRateData.parse(text.replace("Short Maturities -1.9", ""), DATE));
        assertThrows(IllegalArgumentException.class, () -> DbiRateData.parse(text.replace(FactorMap.UCITS_HEDGED, FactorMap.DBMF), DATE));
        assertThrows(IllegalArgumentException.class, () -> DbiRateData.parse(text, DATE.minusMonths(2)));
    }

    @Test public void settingsRoundTripConcurrencyAndFiniteValidation()
    {
        var client = new Client();
        var p = position("Support", null, 1L, false);
        var mapping = mapping(RateSettings.Curve.DE, 5);
        save(client, p, mapping);
        assertEquals(mapping, RateSettings.load(client).mappings().get(p.vehicle().getUUID()));
        assertThrows(IllegalArgumentException.class, () -> RateSettings.save(client, new RateSettings.Settings(1, Map.of()), null));
        assertThrows(IllegalArgumentException.class, () -> new RateSettings.Mapping(DATE.toString(), "Test", RateSettings.Curve.US, Double.NaN, 0d, 0d));
        assertThrows(IllegalArgumentException.class, () -> new RateSettings.Mapping(DATE.toString(), "Test", RateSettings.Curve.NONE, null, 0d, 0d));
    }
}
