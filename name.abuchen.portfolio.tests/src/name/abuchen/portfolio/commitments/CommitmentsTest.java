package name.abuchen.portfolio.commitments;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;

public class CommitmentsTest
{
    private static final LocalDate DATE = LocalDate.of(2026, 9, 25);
    private static final CurrencyConverter EUR = new CurrencyConverter()
    {
        public String getTermCurrency() { return "EUR"; }
        public CurrencyConverter with(String currency) { assertEquals("EUR", currency); return this; }
        public ExchangeRate getRate(LocalDate date, String currency) { return new ExchangeRate(date, currency.equals("USD") ? new BigDecimal("0.9") : BigDecimal.ONE); }
    };
    private Client fixture()
    {
        var c = new Client(); var s = new Security("Fund", "EUR"); c.addSecurity(s); c.addPortfolio(new Portfolio());
        var account = new Account(); account.setCurrencyCode("EUR"); c.addAccount(account);
        return c;
    }
    private PortfolioTransaction buy(Client c, LocalDate date, long amount, String currency)
    {
        var tx = new PortfolioTransaction(); tx.setType(PortfolioTransaction.Type.BUY); tx.setDateTime(date.atStartOfDay());
        tx.setSecurity(c.getSecurities().getFirst()); tx.setCurrencyCode(currency); tx.setAmount(amount); tx.setShares(1000000); c.getPortfolios().getFirst().addTransaction(tx); return tx;
    }
    private AccountTransaction call(Client c, LocalDate date, long amount)
    {
        var tx = new AccountTransaction(); tx.setType(AccountTransaction.Type.CAPITAL_CALL); tx.setDateTime(date.atStartOfDay()); tx.setCurrencyCode("EUR");
        tx.setSecurity(c.getSecurities().getFirst()); tx.setAmount(amount); c.getAccounts().getFirst().addTransaction(tx); return tx;
    }
    private void save(Client c, long total, Long... forecasts)
    { Commitments.save(c, c.getSecurities().getFirst(), total, List.of(forecasts)); }
    @Test public void paidIgnoresDistributionsSalesTransfersFutureOperationsAndFees()
    {
        var c = fixture(); var initial = buy(c, DATE.minusYears(1), 1010000, "EUR"); initial.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 10000)));
        call(c, DATE.minusDays(5), 200000);
        call(c, DATE.minusDays(3), 50000).setType(AccountTransaction.Type.DISTRIBUTION);
        buy(c, DATE.minusDays(2), 300000, "EUR").setType(PortfolioTransaction.Type.SELL);
        buy(c, DATE.minusDays(1), 500000, "EUR").setType(PortfolioTransaction.Type.TRANSFER_IN);
        buy(c, DATE.plusDays(1), 500000, "EUR"); call(c, DATE.plusDays(1), 300000);
        save(c, 2000000, 100000L, 200000L, 300000L, 200000L);
        var row = Commitments.row(c, c.getSecurities().getFirst(), EUR, DATE);
        assertEquals(Long.valueOf(1200000), row.paid()); assertEquals(Long.valueOf(800000), row.remaining()); assertEquals(Long.valueOf(0), row.gap());
        c.getAccounts().getFirst().setRetired(true); assertEquals(row, Commitments.row(c, c.getSecurities().getFirst(), EUR, DATE));
    }
    @Test public void transactionEditsImmediatelyChangeDerivedValuesAndDoNotShiftYears() throws Exception
    {
        var c = fixture(); buy(c, DATE.minusYears(1), 1000000, "EUR"); var tx = call(c, DATE, 200000);
        save(c, 2000000, 800000L, 0L, 0L, 0L); var security = c.getSecurities().getFirst();
        assertEquals(Long.valueOf(800000), Commitments.row(c, security, EUR, DATE).remaining());
        tx.setAmount(300000); assertEquals(Long.valueOf(700000), Commitments.row(c, security, EUR, DATE).remaining());
        var future = Commitments.row(c, security, EUR, DATE.plusYears(1)); assertEquals(Long.valueOf(800000), future.forecast().getFirst());
        assertTrue(future.warnings().stream().anyMatch(w -> w.contains("2026") && w.contains("échue")));
        assertFalse(security.getAttributes().getMap().containsKey(Commitments.PAID));
        var copy = ClientFactory.duplicate(c); assertEquals(future.remaining(), Commitments.row(copy, copy.getSecurities().getFirst(), EUR, DATE.plusYears(1)).remaining());
    }
    @Test public void historicalFxAndRecordedEurCountervalue()
    {
        var c = fixture(); buy(c, DATE.minusYears(1), 1000000, "USD"); save(c, 2000000, 0L, 0L, 0L, 0L);
        assertEquals(Long.valueOf(900000), Commitments.row(c, c.getSecurities().getFirst(), EUR, DATE).paid());
        var tx = call(c, DATE, 100000); tx.setCurrencyCode("USD");
        tx.addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE, Money.of("USD", 100000), Money.of("EUR", 80000), new BigDecimal("1.25")));
        assertEquals(Long.valueOf(980000), Commitments.row(c, c.getSecurities().getFirst(), EUR, DATE).paid());
    }
    @Test public void unavailableRatesDoNotSilentlyUseOneToOne()
    {
        var c = fixture(); buy(c, DATE.minusYears(1), 1000000, "USD"); save(c, 2000000, 0L, 0L, 0L, 0L);
        var missing = new CurrencyConverter()
        {
            public String getTermCurrency() { return "EUR"; }
            public CurrencyConverter with(String currency) { return this; }
            public ExchangeRate getRate(LocalDate date, String currency) { return new ExchangeRate(LocalDate.MIN, BigDecimal.ONE); }
        };
        var row = Commitments.row(c, c.getSecurities().getFirst(), missing, DATE); assertNull(row.paid()); assertNull(row.remaining());
        assertFalse(Commitments.summary(c, missing, DATE).complete());
    }
    @Test public void partialTotalsAndOverCommitmentAreNotReportedAsComplete()
    {
        var c = fixture(); call(c, DATE, 1200000);
        assertFalse(Commitments.summary(c, EUR, DATE).complete());
        save(c, 1000000, 0L, 0L, 0L, 0L);
        assertEquals(Long.valueOf(-200000), Commitments.row(c, c.getSecurities().getFirst(), EUR, DATE).remaining());
        assertFalse(Commitments.summary(c, EUR, DATE).complete());
        save(c, 2000000, 100000L, 100000L, 0L, 0L);
        assertEquals(600000, Commitments.summary(c, EUR, DATE).gap());
    }
    @Test public void duplicateClassificationsDoNotDoubleCountAndInputValidationIsAtomic()
    {
        var c = fixture(); call(c, DATE, 200000); save(c, 1000000, 200000L, 200000L, 200000L, 200000L);
        for (int i = 0; i < 2; i++)
        {
            var tax = new Taxonomy("Tax" + i); var root = new Classification("root" + i, "Private Equity"); tax.setRootNode(root);
            root.addAssignment(new Classification.Assignment(c.getSecurities().getFirst())); c.addTaxonomy(tax);
        }
        assertEquals(1, Commitments.summary(c, EUR, DATE).rows().size()); assertEquals(1000000, Commitments.summary(c, EUR, DATE).total());
        assertThrows(IllegalArgumentException.class, () -> save(c, -1, 0L, 0L, 0L, 0L));
        assertEquals(Long.valueOf(1000000), Commitments.value(c.getSecurities().getFirst(), Commitments.TOTAL));
        assertThrows(IllegalArgumentException.class, () -> Commitments.save(c, c.getSecurities().getFirst(), null, List.of(1L, 0L, 0L, 0L)));
    }
    @Test public void reservesRespectNestedAssignmentWeightsAndKeepAmbiguousNamesDistinct()
    {
        var c = fixture(); var account = c.getAccounts().getFirst(); var deposit = new AccountTransaction(); deposit.setType(AccountTransaction.Type.DEPOSIT);
        deposit.setDateTime(DATE.minusDays(1).atStartOfDay()); deposit.setCurrencyCode("EUR"); deposit.setAmount(1000000); account.addTransaction(deposit);
        var tax = new Taxonomy("Liquidity"); var root = new Classification("reserve", Commitments.RESERVE_NAME); tax.setRootNode(root); c.addTaxonomy(tax);
        root.addAssignment(new Classification.Assignment(account, 5000)); var child = new Classification(root, "child", "Cash"); root.addChild(child); child.addAssignment(new Classification.Assignment(account, 2500));
        assertEquals(750000, Commitments.reserve(c, Commitments.reserves(c).getFirst(), EUR, DATE));
        var another = new Taxonomy("Other"); another.setRootNode(new Classification("other", Commitments.RESERVE_NAME)); c.addTaxonomy(another);
        assertEquals(2, Commitments.reserves(c).size());
    }
    @Test public void additionalBuysAreFlaggedRatherThanSilentlyAdded()
    {
        var c = fixture(); buy(c, DATE.minusYears(1), 1000000, "EUR"); buy(c, DATE, 200000, "EUR");
        save(c, 2000000, 0L, 0L, 0L, 0L);
        var row = Commitments.row(c, c.getSecurities().getFirst(), EUR, DATE);
        assertEquals(Long.valueOf(1000000), row.paid()); assertTrue(row.warnings().stream().anyMatch(w -> w.startsWith("Plusieurs achats")));
    }
}
