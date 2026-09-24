package name.abuchen.portfolio.updates.bonds;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.updates.PortfolioUpdateCopy;

/** Integration-only: bond updates leave PE, Pilotage and widget identities intact in both file formats. */
public class BondPrivateEquityCompatibilityTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void bondUpdateRoundTripsAlongsidePrivateEquityAndPilotage() throws Exception
    {
        var client = ClientFactory.load(getClass().getResourceAsStream("/name/abuchen/portfolio/updates/elm/elm-pe-demo.xml"));
        var pilotage = client.getTaxonomy("elm-demo-pilotage");
        var categories = pilotage.getAllClassifications().stream().map(c -> c.getId() + ":" + c.getWeight() + ":"
                        + c.getAssignments().stream().map(a -> a.getInvestmentVehicle().getUUID() + ":" + a.getWeight()).toList()).toList();
        var dashboardId = client.getDashboards().findFirst().orElseThrow().getId();
        var peValue = client.getSecurities().getFirst().getSecurityPrice(LocalDate.of(2026, 1, 5)).getValue();
        var bond = new Security("Synthetic bond", "EUR");
        bond.setIsin("DE0001102622");
        client.addSecurity(bond);
        var quote = new BondQuote(LocalDate.of(2026, 9, 18), BigDecimal.valueOf(100), BigDecimal.ONE, BigDecimal.valueOf(101), null, null, "test");
        BondUpdate.apply(client, List.of(BondUpdate.prepare(bond, List.of(quote), false)));
        assertSame(pilotage, client.getTaxonomy(pilotage.getId()));
        for (var extension : List.of(".xml", ".portfolio"))
        {
            var output = temporary.getRoot().toPath().resolve("bonds" + extension);
            PortfolioUpdateCopy.save(client, output, null);
            var loaded = ClientFactory.load(output.toFile(), null, new NullProgressMonitor());
            var loadedBond = BondUpdate.security(loaded, bond.getUUID());
            assertEquals(10100000000L, loadedBond.getLatest().getValue());
            assertEquals(bond.getAttributes().getMap(), loadedBond.getAttributes().getMap());
            assertTrue(BondUpdate.managed(loadedBond));
            assertEquals(categories, loaded.getTaxonomy(pilotage.getId()).getAllClassifications().stream().map(c -> c.getId() + ":" + c.getWeight() + ":"
                            + c.getAssignments().stream().map(a -> a.getInvestmentVehicle().getUUID() + ":" + a.getWeight()).toList()).toList());
            assertEquals(dashboardId, loaded.getDashboards().findFirst().orElseThrow().getId());
            assertEquals(peValue, loaded.getSecurities().getFirst().getSecurityPrice(LocalDate.of(2026, 1, 5)).getValue());
            var transactions = loaded.getAccounts().getFirst().getTransactions();
            assertTrue(transactions.stream().anyMatch(t -> t.getType() == AccountTransaction.Type.CAPITAL_CALL && t.getAmount() == 200000));
            assertTrue(transactions.stream().anyMatch(t -> t.getType() == AccountTransaction.Type.DISTRIBUTION && t.getAmount() == 50000));
        }
    }
}
