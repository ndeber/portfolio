package name.abuchen.portfolio.tax;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;
import name.abuchen.portfolio.tax.TaxEnvelopeProfiles.Pea;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class TaxEnvelopeProfilesTest
{
    private final LocalDate opening = LocalDate.of(2024, 1, 24);
    private final LocalDate end = LocalDate.of(2026, 10, 6);
    private final Client client = new Client();
    private final Portfolio pea = new Portfolio("PEA Saxo");
    private final Account cash = new Account("PEA espèces");
    private final Security security = new Security("ETF", "EUR");
    private final TestCurrencyConverter converter = new TestCurrencyConverter();

    public TaxEnvelopeProfilesTest()
    {
        client.setBaseCurrency("EUR"); cash.setCurrencyCode("EUR");
        client.addPortfolio(pea); client.addAccount(cash); client.addSecurity(security); pea.setReferenceAccount(cash);
        cash.addTransaction(new AccountTransaction(opening.atStartOfDay(), "EUR", 10_000_00, null, AccountTransaction.Type.DEPOSIT));
        security.addPrice(new SecurityPrice(opening, 100L * Values.Quote.factor()));
        security.addPrice(new SecurityPrice(end, 120L * Values.Quote.factor()));
        trade(security, PortfolioTransaction.Type.BUY, opening, 100, 10_000_00);
        save(1860);
    }

    private BuySellEntry trade(Security s, PortfolioTransaction.Type type, LocalDate date, long shares, long amount)
    {
        var entry = new BuySellEntry(pea, cash);
        entry.setType(type); entry.setDate(date.atTime(12, 0)); entry.setSecurity(s);
        entry.setShares(shares * Values.Share.factor()); entry.setCurrencyCode("EUR"); entry.setAmount(amount); entry.insert();
        return entry;
    }

    private void save(int bps)
    {
        var profile = new Pea("PEA Saxo", pea.getUUID(), cash.getUUID(), opening.toString(), bps, true, true, end.toString(), "Test");
        TaxEnvelopeProfiles.save(client, new TaxEnvelopeProfiles.Settings(1, Map.of(pea.getUUID(), profile)), client.getProperty(TaxEnvelopeProfiles.PROPERTY));
    }

    private TaxPerformance.Result result()
    {
        return TaxPerformance.calculate(client, TaxScope.of(client, new PortfolioClientFilter(pea, cash), null), converter,
                        Interval.of(opening.minusDays(1), end));
    }

    @Test
    public void initialAndFutureTitlesInheritWithoutPersistingIndividualBases()
    {
        var initial = TaxEnvelopeProfiles.effectiveSettings(client);
        assertEquals(1, initial.positions().size());
        var basis = initial.positions().values().iterator().next();
        assertEquals(0, basis.amount()); assertEquals(0, basis.shares());
        assertEquals(opening.minusDays(1).toString(), basis.validatedOn());
        assertEquals(TaxEstimateSettings.Profile.PEA, basis.profile());
        var other = new Security("Nouvel ETF", "EUR"); client.addSecurity(other);
        trade(other, PortfolioTransaction.Type.BUY, opening.plusDays(1), 10, 100_00);
        assertEquals(2, TaxEnvelopeProfiles.effectiveSettings(client).positions().size());
        assertTrue(TaxEstimateSettings.load(client).positions().isEmpty());
    }

    @Test
    public void fullyClosedPositionKeepsItsVirtualTaxInThePea()
    {
        trade(security, PortfolioTransaction.Type.SELL, end.minusDays(1), 100, 12_000_00);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(1_628_00), r.after().gain());
        assertEquals(Long.valueOf(372_00), r.latent()); assertEquals(Long.valueOf(0), r.debt());
        assertEquals(Long.valueOf(11_628_00), r.netValue());
        assertTrue(TaxEnvelopeProfiles.effectiveSettings(client).positions().containsKey(TaxEstimateSettings.key(pea.getUUID(), security.getUUID())));
    }

    @Test
    public void rateChangedOnceUpdatesAllInheritedPositions()
    {
        assertEquals(Long.valueOf(1_628_00), result().after().gain());
        save(1720);
        assertEquals(Long.valueOf(1_656_00), result().after().gain());
        assertEquals(1720, TaxEnvelopeProfiles.effectiveSettings(client).positions().values().iterator().next().rateBps());
    }

    @Test
    public void existingManualOverrideWinsAndOtherEnvelopesAreUntouched()
    {
        var key = TaxEstimateSettings.key(pea.getUUID(), security.getUUID());
        var inherited = TaxEnvelopeProfiles.effectiveSettings(client).positions().get(key);
        var manual = new TaxEstimateSettings.Basis(TaxEstimateSettings.Profile.EXEMPT, 0, inherited.losses(), 0, 0,
                        inherited.validatedOn(), null, inherited.movements(), "Exception");
        TaxEstimateSettings.save(client, new TaxEstimateSettings.Settings(1, Map.of(key, manual)), null);
        assertEquals(manual, TaxEnvelopeProfiles.effectiveSettings(client).positions().get(key));
        assertFalse(TaxEnvelopeProfiles.inherited(client, pea, key));
        var pme = new Portfolio("PEA PME");client.addPortfolio(pme);
        pme.addTransaction(new PortfolioTransaction(opening.atStartOfDay(), "EUR", 100_00, security,
                        1L * Values.Share.factor(), PortfolioTransaction.Type.DELIVERY_INBOUND, 0, 0));
        assertFalse(TaxEnvelopeProfiles.effectiveSettings(client).positions().containsKey(TaxEstimateSettings.key(pme.getUUID(), security.getUUID())));
    }

    @Test
    public void lossesReduceTaxButACompleteLosingPlanDoesNotCreateAReceivable()
    {
        security.addPrice(new SecurityPrice(end, 90L * Values.Quote.factor()));
        var r = result();
        assertEquals(r.detail(), Long.valueOf(-1_000_00), r.after().gain());
        assertEquals(Long.valueOf(0), r.latent());
        var title = TaxPerformance.calculate(client, TaxScope.security(client, new PortfolioClientFilter(pea), security), converter,
                        Interval.of(opening.minusDays(1), end));
        assertEquals(Long.valueOf(-186_00), title.latent());
    }

    @Test
    public void aNewWithdrawalStopsTheScenarioInsteadOfInventingItsFiscalTreatment()
    {
        cash.addTransaction(new AccountTransaction(end.atStartOfDay(), "EUR", 500_00, null, AccountTransaction.Type.REMOVAL));
        assertNull(result().after().gain());
        assertTrue(result().detail().contains("retrait"));
    }

    @Test
    public void datedAccountFeesReduceThePeaTaxableGain()
    {
        cash.addTransaction(new AccountTransaction(end.atStartOfDay(), "EUR", 100_00, null, AccountTransaction.Type.FEES));
        var r = result();
        assertEquals(r.detail(), Long.valueOf(353_40), r.latent());
        assertEquals(Long.valueOf(1_546_60), r.after().gain());
    }

    @Test
    public void dividendsAfterAnInternalSaleStayVirtualAndUseNetReceivedIncome()
    {
        trade(security, PortfolioTransaction.Type.SELL, end.minusDays(1), 100, 12_000_00);
        var dividend = new AccountTransaction(end.atStartOfDay(), "EUR", 900_00, security, AccountTransaction.Type.DIVIDENDS);
        dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("EUR", 100_00)));
        cash.addTransaction(dividend);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(539_40), r.latent());
        assertEquals(Long.valueOf(2_360_60), r.after().gain());
        assertEquals(Long.valueOf(0), r.debt());
    }

    @Test
    public void persistenceRejectsMissingRateAndStaleUpdates()
    {
        String old = client.getProperty(TaxEnvelopeProfiles.PROPERTY);
        save(1720);
        assertThrows(IllegalArgumentException.class, () -> TaxEnvelopeProfiles.save(client, TaxEnvelopeProfiles.load(client), old));
        client.setProperty(TaxEnvelopeProfiles.PROPERTY, old.replace("\"socialRateBps\":1860,", ""));
        assertThrows(IllegalArgumentException.class, () -> TaxEnvelopeProfiles.load(client));
    }

    @Test
    public void changingTheDedicatedCashAccountInvalidatesTheProfile()
    {
        pea.setReferenceAccount(new Account("Autre"));
        assertNull(result().after().gain());
        assertTrue(result().detail().contains("dédié"));
    }
}
