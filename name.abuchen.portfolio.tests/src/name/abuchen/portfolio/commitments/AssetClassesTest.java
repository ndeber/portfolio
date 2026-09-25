package name.abuchen.portfolio.commitments;

import static org.junit.Assert.*;
import org.junit.Test;
import name.abuchen.portfolio.model.*;

@SuppressWarnings("nls")
public class AssetClassesTest
{
    @Test public void groupsNestedAssignmentsAndMarksMixedHoldings()
    {
        var client = new Client(); var security = new Security(); security.setName("Fund"); client.addSecurity(security);
        assertEquals("Non classé", AssetClasses.type(client, security).label());
        var taxonomy = new Taxonomy("Classes d’actifs");
        var root = new Classification(null, "root", "All");
        var pe = new Classification(root, "pe", "Private Equity"); root.addChild(pe);
        var sub = new Classification(pe, "sub", "Funds"); pe.addChild(sub);
        sub.addAssignment(new Classification.Assignment(security, 7000));
        taxonomy.setRootNode(root); client.addTaxonomy(taxonomy);
        assertEquals("PE", AssetClasses.type(client, security).label());
        var vc = new Classification(root, "vc", "Venture Capital"); root.addChild(vc);
        vc.addAssignment(new Classification.Assignment(security, 3000));
        assertEquals("PE (mixte)", AssetClasses.type(client, security).label());
        var cash = new Account("Cash"); assertEquals("Cash", AssetClasses.type(client, cash).label());
        assertTrue(AssetClasses.comparator(client).compare(security, cash) < 0);
        var other = new Taxonomy("Classes d'actifs"); other.setRootNode(new Classification(null, "other", "Other")); client.addTaxonomy(other);
        assertEquals("Non classé", AssetClasses.type(client, security).label());
    }
}
