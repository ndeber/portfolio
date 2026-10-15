package name.abuchen.portfolio.tax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.EnumSet;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.SaveFlag;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.tax.PositionTaxEstimates.Status;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Basis;
import name.abuchen.portfolio.tax.TaxEstimateSettings.LossTreatment;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Profile;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Settings;

@SuppressWarnings("nls")
public class PositionTaxEstimatesTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final LocalDate date = LocalDate.of(2026, 9, 29);
    private final Client client = new Client();
    private final Security security = new Security("Test", "EUR");
    private final Portfolio portfolio = new Portfolio();
    private final Account account = new Account("Cash");
    private final CurrencyConverter converter = new TestCurrencyConverter();

    public PositionTaxEstimatesTest()
    {
        client.setBaseCurrency("EUR");
        account.setCurrencyCode("EUR");
        client.addAccount(account);
        portfolio.setName("CTO");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);
        client.addSecurity(security);
        transaction(portfolio, PortfolioTransaction.Type.DELIVERY_INBOUND, 100, date.minusDays(10));
        security.setUpdatedAt(java.time.Instant.now());
        quote(120);
    }

    private void quote(long price)
    {
        security.addPrice(new SecurityPrice(date, price * Values.Quote.factor()));
    }

    private void transaction(Portfolio target, PortfolioTransaction.Type type, long shares, LocalDate day)
    {
        var t = new PortfolioTransaction();
        t.setType(type);
        t.setSecurity(security);
        t.setDateTime(day.atStartOfDay());
        t.setCurrencyCode("EUR");
        t.setAmount(shares * 100_00);
        t.setShares(shares * Values.Share.factor());
        target.addTransaction(t);
    }

    private Basis basis(Portfolio target, long amount, int rate, Profile profile, String review)
    {
        return new Basis(profile, rate, LossTreatment.POTENTIAL_SAVING, amount,
                        PositionTaxEstimates.sharesAt(target, security, date), date.toString(), review,
                        PositionTaxEstimates.movements(client, target, security, date), "Test");
    }

    private Settings settings()
    {
        return new Settings(1, Map.of(key(), basis(portfolio, 10_000_00, 2500, Profile.CTO, null)));
    }

    private String key()
    {
        return TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());
    }

    @Test
    public void quotesRecalculateSymmetricallyWithoutChangingBasisOrPayments()
    {
        var settings = settings();
        var first = PositionTaxEstimates.rows(client, settings, converter, date).getFirst();
        assertEquals(500_00, first.estimate().contribution());
        assertEquals(11_500_00, first.estimate().netValue());
        quote(90);
        var second = PositionTaxEstimates.rows(client, settings, converter, date).getFirst();
        assertEquals(-250_00, second.estimate().contribution());
        assertEquals(9_250_00, second.estimate().netValue());
        assertEquals(10_000_00, second.basis().amount());
        assertEquals(1, portfolio.getTransactions().size());
        assertTrue(account.getTransactions().isEmpty());
    }

    @Test
    public void lossFloorZeroRateAndSymmetricRounding()
    {
        assertEquals(0, PositionTaxEstimates.calculate(90_00, 100_00, 2500, LossTreatment.ZERO_FLOOR).contribution());
        assertEquals(0, PositionTaxEstimates.calculate(120_00, 100_00, 0, LossTreatment.POTENTIAL_SAVING).contribution());
        assertEquals(1, PositionTaxEstimates.calculate(2, 0, 2500, LossTreatment.POTENTIAL_SAVING).contribution());
        assertEquals(-1, PositionTaxEstimates.calculate(0, 2, 2500, LossTreatment.POTENTIAL_SAVING).contribution());
        assertThrows(IllegalArgumentException.class,
                        () -> PositionTaxEstimates.calculate(1, 0, 10001, LossTreatment.POTENTIAL_SAVING));
        assertThrows(IllegalArgumentException.class,
                        () -> PositionTaxEstimates.calculate(-1, 0, 2500, LossTreatment.POTENTIAL_SAVING));
        assertEquals(Long.MAX_VALUE, PositionTaxEstimates.calculate(Long.MAX_VALUE, 0, 10000,
                        LossTreatment.POTENTIAL_SAVING).contribution());
    }

    @Test
    public void sameSecurityInTwoAccountsHasIndependentParametersAndNoCompensation()
    {
        var holding = new Portfolio();
        holding.setName("Holding");
        holding.setReferenceAccount(account);
        client.addPortfolio(holding);
        transaction(holding, PortfolioTransaction.Type.BUY, 100, date.minusDays(5));
        var settings = settings().with(TaxEstimateSettings.key(holding.getUUID(), security.getUUID()),
                        basis(holding, 13_000_00, 1000, Profile.HOLDING_DISPOSAL, null));
        var rows = PositionTaxEstimates.rows(client, settings, converter, date);
        assertEquals(2, rows.size());
        assertEquals(500_00, rows.get(0).estimate().contribution());
        assertEquals(-100_00, rows.get(1).estimate().contribution());
    }

    @Test
    public void adjustedHoldingBasisDoesNotRetaxPriorYearGains()
    {
        quote(130);
        var settings = new Settings(1, Map.of(key(), basis(portfolio, 12_000_00, 2500,
                        Profile.HOLDING_ANNUAL, "2026-12-31")));
        assertEquals(250_00, PositionTaxEstimates.rows(client, settings, converter, date).getFirst().estimate().contribution());
        assertEquals(Status.REVIEW_DUE,
                        PositionTaxEstimates.rows(client, settings, converter, LocalDate.of(2026, 12, 31)).getFirst().status());
    }

    @Test
    public void partialSaleAndRoundTripRequireRevalidation()
    {
        var settings = settings();
        transaction(portfolio, PortfolioTransaction.Type.SELL, 10, date);
        var partial = PositionTaxEstimates.rows(client, settings, converter, date).getFirst();
        assertEquals(Status.BASIS_CHANGED, partial.status());
        assertNull(partial.estimate());
        transaction(portfolio, PortfolioTransaction.Type.BUY, 10, date);
        assertEquals(Status.BASIS_CHANGED, PositionTaxEstimates.rows(client, settings, converter, date).getFirst().status());
    }

    @Test
    public void closedPositionDoesNotKeepAnOldLatentTax()
    {
        var settings = settings();
        transaction(portfolio, PortfolioTransaction.Type.SELL, 100, date);
        var row = PositionTaxEstimates.rows(client, settings, converter, date).getFirst();
        assertEquals(Status.CLOSED, row.status());
        assertNull(row.estimate());
    }

    @Test
    public void paymentDoesNotChangeEstimateOrInvalidateBasis()
    {
        var settings = settings();
        account.addTransaction(new AccountTransaction(date.atStartOfDay(), "EUR", 500_00, security,
                        AccountTransaction.Type.TAXES));
        var row = PositionTaxEstimates.rows(client, settings, converter, date).getFirst();
        assertEquals(Status.READY, row.status());
        assertEquals(500_00, row.estimate().contribution());
        assertEquals(500_00, account.getTransactions().getFirst().getAmount());
    }

    @Test
    public void capitalFlowsInvalidateBaseEvenWithoutAChangeInShares()
    {
        var settings = settings();
        account.addTransaction(new AccountTransaction(date.atStartOfDay(), "EUR", 100_00, security,
                        AccountTransaction.Type.CAPITAL_CALL));
        assertEquals(Status.BASIS_CHANGED, PositionTaxEstimates.rows(client, settings, converter, date).getFirst().status());
    }

    @Test
    public void missingConfigurationAndMissingOrFutureQuotesAreNotZeroTax()
    {
        var empty = new Settings(1, Map.of());
        assertEquals(Status.NOT_CONFIGURED, PositionTaxEstimates.rows(client, empty, converter, date).getFirst().status());
        security.removeAllPrices();
        assertEquals(Status.UNAVAILABLE, PositionTaxEstimates.rows(client, settings(), converter, date).getFirst().status());
        security.addPrice(new SecurityPrice(date.plusDays(1), 120 * Values.Quote.factor()));
        assertEquals(Status.UNAVAILABLE, PositionTaxEstimates.rows(client, settings(), converter, date).getFirst().status());
    }

    @Test
    public void foreignCurrencyUsesCurrentFxButKeepsEurTaxBasis()
    {
        security.setCurrencyCode("USD");
        var fx = fx(new ExchangeRate(date, new BigDecimal("0.9")));
        var row = PositionTaxEstimates.rows(client, settings(), fx, date).getFirst();
        assertEquals(10_800_00, row.estimate().marketValue());
        assertEquals(200_00, row.estimate().contribution());
        assertEquals(Status.UNAVAILABLE, PositionTaxEstimates.rows(client, settings(),
                        fx(new ExchangeRate(LocalDate.MIN, BigDecimal.ONE)), date).getFirst().status());
    }

    private CurrencyConverter fx(ExchangeRate rate)
    {
        return new CurrencyConverter()
        {
            public String getTermCurrency() { return "EUR"; }
            public ExchangeRate getRate(LocalDate day, String currency) { return rate; }
            public CurrencyConverter with(String currency) { return this; }
        };
    }

    @Test
    public void settingsSurviveXmlAndBinaryRoundTrips() throws Exception
    {
        var settings = settings();
        TaxEstimateSettings.save(client, settings, null);
        var xmlCopy = ClientFactory.duplicate(client);
        assertEquals(settings, TaxEstimateSettings.load(xmlCopy));
        assertEquals(Status.READY, PositionTaxEstimates.rows(xmlCopy, TaxEstimateSettings.load(xmlCopy), converter, date).getFirst().status());
        var file = temporary.newFile("tax-estimates.portfolio");
        ClientFactory.saveAs(client, file, null, EnumSet.of(SaveFlag.BINARY, SaveFlag.COMPRESSED));
        var binaryCopy = ClientFactory.load(file, null, new NullProgressMonitor());
        assertEquals(settings, TaxEstimateSettings.load(binaryCopy));
        assertEquals(Status.READY, PositionTaxEstimates.rows(binaryCopy, TaxEstimateSettings.load(binaryCopy), converter, date).getFirst().status());
    }

    @Test
    public void malformedOrNewerDataAndConcurrentEditsAreNeverSilentlyReplaced()
    {
        var settings = settings();
        TaxEstimateSettings.save(client, settings, null);
        assertThrows(IllegalArgumentException.class, () -> TaxEstimateSettings.save(client, settings, null));
        var json = client.getProperty(TaxEstimateSettings.PROPERTY);
        client.setProperty(TaxEstimateSettings.PROPERTY, json.replace("\"version\":1", "\"version\":2"));
        assertThrows(IllegalArgumentException.class, () -> TaxEstimateSettings.load(client));
        client.setProperty(TaxEstimateSettings.PROPERTY, "{}");
        assertThrows(IllegalArgumentException.class, () -> TaxEstimateSettings.save(client, settings, "{}"));
        assertEquals("{}", client.getProperty(TaxEstimateSettings.PROPERTY));
    }

    @Test
    public void absentAmountsAndRatesNeverDefaultToZero()
    {
        TaxEstimateSettings.save(client, settings(), null);
        var original = client.getProperty(TaxEstimateSettings.PROPERTY);
        for (String invalid : new String[] { original.replace("\"rateBps\":2500,", ""),
                        original.replace("\"amount\":1000000,", ""),
                        original.replace("\"rateBps\":2500", "\"rateBps\":2500.1") })
        {
            client.setProperty(TaxEstimateSettings.PROPERTY, invalid);
            assertThrows(IllegalArgumentException.class, () -> TaxEstimateSettings.load(client));
        }
    }

    @Test
    public void futureMovementsDoNotInvalidateTodaysBasis()
    {
        var settings = settings();
        transaction(portfolio, PortfolioTransaction.Type.SELL, 10, date.plusDays(1));
        assertNotNull(PositionTaxEstimates.rows(client, settings, converter, date).getFirst().estimate());
        assertEquals(Status.BASIS_CHANGED, PositionTaxEstimates.rows(client, settings, converter, date.plusDays(1)).getFirst().status());
    }
}
