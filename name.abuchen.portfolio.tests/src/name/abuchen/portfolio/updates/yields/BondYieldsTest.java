package name.abuchen.portfolio.updates.yields;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;

public class BondYieldsTest
{
    private final Client client = new Client();
    private final TestCurrencyConverter converter = new TestCurrencyConverter();
    private final LocalDate date = LocalDate.of(2026, 9, 28);
    private final Security security = new Security("Fund", "EUR");
    public BondYieldsTest()
    {
        client.setBaseCurrency("EUR"); security.setIsin("IE00BG47KH54"); client.addSecurity(security);
        security.addPrice(new SecurityPrice(date.minusYears(1), 100L * Values.Quote.factor()));
        var portfolio = new Portfolio(); client.addPortfolio(portfolio);
        var tx = new PortfolioTransaction(); tx.setType(PortfolioTransaction.Type.DELIVERY_INBOUND); tx.setDateTime(date.minusYears(1).atStartOfDay());
        tx.setSecurity(security); tx.setCurrencyCode("EUR"); tx.setShares(10 * Values.Share.factor()); tx.setAmount(100000); portfolio.addTransaction(tx);
    }
    private BondYields.Observation observation(LocalDate asOf, double rate)
    { return new BondYields.Observation(security.getUUID(), asOf, rate, .005, false, false, "https://example.org/factsheet", "YTM"); }
    @Test public void historyUsesLatestNotFutureAndRoundTrips() throws Exception
    {
        BondYields.apply(client, List.of(observation(date.minusDays(28), .04), observation(date, .05)), converter, date);
        assertEquals(.04, BondYields.calculate(client, converter, date.minusDays(1)).average().gross(), 1e-10);
        assertEquals(.045, BondYields.calculate(ClientFactory.duplicate(client), converter, date).average().net(), 1e-10);
        assertEquals(100000, BondYields.calculate(client, converter, date).average().total(), 0);
    }
    @Test public void unheldFundDoesNotChangeAverage()
    {
        var other = new Security("Future fund", "EUR"); other.setIsin("LU0234688595"); client.addSecurity(other);
        BondYields.apply(client, List.of(observation(date, .04), new BondYields.Observation(other.getUUID(), date, .09, .01, false, false, "source", "YTM")), converter, date);
        assertEquals(.04, BondYields.calculate(client, converter, date).average().gross(), 1e-10);
    }
    @Test public void unavailableInflationExcludesRealYield()
    {
        BondYields.apply(client, List.of(new BondYields.Observation(security.getUUID(), date, .02, 0d, false, true, "source", "réel")), converter, date);
        var result = BondYields.calculate(client, converter, date);
        assertNull(result.average().gross()); assertNotNull(result.inflationError()); assertEquals(100000, result.average().total(), 0);
    }
    @Test public void futureObservationRejectedWithoutMutation()
    {
        assertThrows(IllegalArgumentException.class, () -> BondYields.apply(client, List.of(observation(date.plusDays(1), .03)), converter, date));
        assertNull(client.getProperty(BondYields.HISTORY));
    }
    @Test public void incompatibleAttributesLeaveModelUntouched()
    {
        var wrong = new AttributeType("wrong"); wrong.setName("YTM-Frais"); wrong.setType(String.class); wrong.setTarget(Security.class); wrong.setConverter(AttributeType.StringConverter.class); client.getSettings().addAttributeType(wrong);
        assertThrows(IllegalArgumentException.class, () -> BondYields.apply(client, List.of(observation(date, .03)), converter, date));
        assertNull(client.getProperty(BondYields.HISTORY)); assertNull(BondYields.attribute(client, security, "YTM"));
        assertEquals(0, client.getSettings().getAttributeTypes().filter(a -> a.getName().equals("YTM")).count());
    }
    @Test public void realYieldUsesTrailingInflationAndYtwSurvivesRoundTrip() throws Exception
    {
        var index = new Security("EU (IPCH)", "EUR"); client.addSecurity(index);
        index.addPrice(new SecurityPrice(date.minusYears(1), 1000000L));
        index.addPrice(new SecurityPrice(date, 1030000L));
        index.addPrice(new SecurityPrice(date.plusDays(1), 1200000L));
        BondYields.apply(client, List.of(new BondYields.Observation(security.getUUID(), date, .02, .005, false, true, "source", "YTW réel")), converter, date);
        var result = BondYields.calculate(ClientFactory.duplicate(client), converter, date);
        assertEquals(1.02 * 1.03 - 1, result.average().gross(), 1e-10);
        assertEquals(1.02 * 1.03 - 1 - .005, result.average().net(), 1e-10);
        assertTrue(result.rows().getFirst().observation().ytw());
        assertTrue(result.quality().contains("YTW inclus : 1"));
    }

    @Test public void appliedFeesRemainAvailableForNextRefresh()
    {
        BondYields.apply(client, List.of(observation(date, .04)), converter, date);
        assertEquals(.005, BondYields.attribute(client, security, "Frais"), 1e-12);
    }

}
