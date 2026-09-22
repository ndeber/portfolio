package name.abuchen.portfolio.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.util.EnumSet;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransaction.Type;

@SuppressWarnings("nls")
public class AccountListViewTest
{
    private AccountTransaction transaction(Type type, int day, long amount)
    {
        return new AccountTransaction(LocalDateTime.of(2026, 1, day, 0, 0), "EUR", amount, null, type);
    }

    @Test
    public void capitalFlowsUpdateRunningBalanceInDateOrderAndAfterEdits()
    {
        var account = new Account();
        account.setCurrencyCode("EUR");
        var deposit = transaction(Type.DEPOSIT, 1, 1500000);
        var call = transaction(Type.CAPITAL_CALL, 2, 200000);
        var distribution = transaction(Type.DISTRIBUTION, 3, 50000);
        account.addTransaction(distribution);
        account.addTransaction(deposit);
        account.addTransaction(call);

        var balances = AccountListView.calculateBalances(account);
        assertEquals(3, balances.size());
        assertEquals(1500000, balances.get(deposit).getAmount());
        assertEquals(1300000, balances.get(call).getAmount());
        assertEquals(1350000, balances.get(distribution).getAmount());
        assertEquals("EUR", balances.get(distribution).getCurrencyCode());
        assertEquals(distribution, account.getTransactions().get(0));

        call.setAmount(300000);
        assertEquals(1250000, AccountListView.calculateBalances(account).get(distribution).getAmount());
        account.getTransactions().remove(call);
        balances = AccountListView.calculateBalances(account);
        assertEquals(2, balances.size());
        assertEquals(1550000, balances.get(distribution).getAmount());
    }

    @Test
    public void allTransactionTypesHaveTheExpectedEffectOnCash()
    {
        var debits = EnumSet.of(Type.REMOVAL, Type.INTEREST_CHARGE, Type.FEES, Type.TAXES, Type.BUY,
                        Type.TRANSFER_OUT, Type.CAPITAL_CALL);
        var credits = EnumSet.of(Type.DEPOSIT, Type.INTEREST, Type.DIVIDENDS, Type.FEES_REFUND,
                        Type.TAX_REFUND, Type.SELL, Type.TRANSFER_IN, Type.DISTRIBUTION);
        var covered = EnumSet.copyOf(debits);
        covered.addAll(credits);
        assertEquals(EnumSet.allOf(Type.class), covered);
        for (var type : covered)
        {
            var account = new Account();
            account.setCurrencyCode("EUR");
            var transaction = transaction(type, 1, 200000);
            account.addTransaction(transaction);
            assertEquals(type.name(), debits.contains(type) ? -200000 : 200000,
                            AccountListView.calculateBalances(account).get(transaction).getAmount());
        }
    }

    @Test
    public void emptyOrDeselectedAccountHasNoBalances()
    {
        var account = new Account();
        account.setCurrencyCode("EUR");
        assertTrue(AccountListView.calculateBalances(account).isEmpty());
        assertTrue(AccountListView.calculateBalances(null).isEmpty());
    }
}
