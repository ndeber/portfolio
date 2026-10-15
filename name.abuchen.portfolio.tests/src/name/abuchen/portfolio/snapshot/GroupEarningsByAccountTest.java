package name.abuchen.portfolio.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.ClientPerformanceSnapshot.CategoryType;

@SuppressWarnings("nls")
public class GroupEarningsByAccountTest
{
    private Client privateEquityClient() throws Exception
    {
        return ClientFactory.load(getClass().getResourceAsStream("/name/abuchen/portfolio/updates/elm/elm-pe-demo.xml"));
    }

    private ClientPerformanceSnapshot snapshot(Client client)
    {
        return new ClientPerformanceSnapshot(client, new TestCurrencyConverter(), LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 5));
    }

    @Test
    public void standaloneCapitalFlowsDoNotBecomeEarnings() throws Exception
    {
        for (var type : new AccountTransaction.Type[] { AccountTransaction.Type.CAPITAL_CALL,
                        AccountTransaction.Type.DISTRIBUTION })
        {
            var client = privateEquityClient();
            var account = client.getAccounts().getFirst();
            account.getTransactions().removeIf(t -> t.getType().isCapitalFlow() && t.getType() != type);
            assertTrue(account.getTransactions().stream().anyMatch(t -> t.getType() == type));
            account.getTransactions().stream().filter(t -> t.getType().isCapitalFlow())
                            .forEach(t -> assertNull(t.getCrossEntry()));

            var snapshot = snapshot(client);
            assertTrue(new GroupEarningsByAccount(snapshot).getItems().isEmpty());
            assertEquals(Money.of("EUR", 0), snapshot.getValue(CategoryType.EARNINGS));
        }
    }

    @Test
    public void capitalFlowsLeaveDividendsInterestFeesAndTaxesUnchanged() throws Exception
    {
        var client = privateEquityClient();
        var account = client.getAccounts().getFirst();
        var date = LocalDateTime.of(2026, 1, 4, 12, 0);
        var dividend = new AccountTransaction(date, "EUR", 88_00, client.getSecurities().getFirst(),
                        AccountTransaction.Type.DIVIDENDS);
        dividend.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 10_00)));
        dividend.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 2_00)));
        account.addTransaction(dividend);
        account.addTransaction(new AccountTransaction(date, "EUR", 9_00, null, AccountTransaction.Type.INTEREST));
        account.addTransaction(new AccountTransaction(date, "EUR", 5_00, null, AccountTransaction.Type.FEES));
        account.addTransaction(new AccountTransaction(date, "EUR", 3_00, null, AccountTransaction.Type.TAXES));

        var snapshot = snapshot(client);
        var items = new GroupEarningsByAccount(snapshot).getItems();
        assertEquals(1, items.size());
        var item = items.getFirst();
        assertEquals(Money.of("EUR", 100_00), item.getDividends());
        assertEquals(Money.of("EUR", 9_00), item.getInterest());
        assertEquals(Money.of("EUR", 7_00), item.getFees());
        assertEquals(Money.of("EUR", 13_00), item.getTaxes());
        assertEquals(Money.of("EUR", 109_00), item.getSum());
        assertEquals(snapshot.getValue(CategoryType.EARNINGS), item.getSum());
        assertEquals(snapshot.getValue(CategoryType.FEES), item.getFees());
        assertEquals(snapshot.getValue(CategoryType.TAXES), item.getTaxes());
    }
}
