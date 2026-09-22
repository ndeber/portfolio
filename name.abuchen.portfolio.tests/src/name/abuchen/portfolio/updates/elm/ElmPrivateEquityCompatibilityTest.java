package name.abuchen.portfolio.updates.elm;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.updates.PortfolioUpdateCopy;
import name.abuchen.portfolio.updates.elm.ElmAdjustment.Mapping;
import name.abuchen.portfolio.updates.elm.ElmAdjustment.Options;
import name.abuchen.portfolio.updates.elm.ElmAdjustment.Pilotage;

/** Integration-only regression: optional ELM adjustments coexist with PE operations. */
public class ElmPrivateEquityCompatibilityTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void adjustmentCopiesKeepPrivateEquityTransactionsNavAndDashboard() throws Exception
    {
        var source = ClientFactory.load(getClass().getResourceAsStream("elm-pe-demo.xml"));
        var snapshot = ClientFactory.duplicate(source);
        var options = new Options("elm-demo-security", List.of(new Mapping("elm-demo-allocation", "elm-demo-allocation-0",
                        "elm-demo-allocation-1", "elm-demo-allocation-2")), new Pilotage("elm-demo-pilotage", "elm-demo-pilotage-1"));
        ElmAdjustment.apply(snapshot, ElmAdjustment.prepare(snapshot, options, new ElmAllocation(LocalDate.of(2026, 9, 18), 2883, 1235, 5882)));
        for (var extension : List.of(".xml", ".portfolio"))
        {
            var output = temporary.getRoot().toPath().resolve("elm" + extension);
            PortfolioUpdateCopy.save(snapshot, output, null);
            var loaded = ClientFactory.load(output.toFile(), null, new NullProgressMonitor());
            var transactions = loaded.getAccounts().getFirst().getTransactions();
            assertTrue(transactions.stream().anyMatch(t -> t.getType() == AccountTransaction.Type.CAPITAL_CALL && t.getAmount() == 200000));
            assertTrue(transactions.stream().anyMatch(t -> t.getType() == AccountTransaction.Type.DISTRIBUTION && t.getAmount() == 50000));
            assertEquals(source.getAccounts().getFirst().getTransactions().size(), transactions.size());
            assertEquals(source.getSecurities().getFirst().getSecurityPrice(LocalDate.of(2026, 1, 5)).getValue(),
                            loaded.getSecurities().getFirst().getSecurityPrice(LocalDate.of(2026, 1, 5)).getValue());
            assertEquals(source.getDashboards().findFirst().orElseThrow().getId(), loaded.getDashboards().findFirst().orElseThrow().getId());
            var allocation = ElmAdjustment.taxonomy(loaded, "elm-demo-allocation");
            assertEquals(2883, allocation.getClassificationById("elm-demo-allocation-0").getAssignments().getFirst().getWeight());
            assertEquals(10000, ElmAdjustment.taxonomy(loaded, "elm-demo-pilotage").getClassificationById("elm-demo-pilotage-1")
                            .getAssignments().getFirst().getWeight());
        }
        assertNull(source.getProperty(ElmAdjustment.AUDIT));
    }
}
