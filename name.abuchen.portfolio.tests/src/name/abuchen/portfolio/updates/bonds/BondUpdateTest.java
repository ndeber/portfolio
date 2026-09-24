package name.abuchen.portfolio.updates.bonds;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.AttributeType;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.LatestSecurityPrice;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;

public class BondUpdateTest
{
    private final Client client = new Client();
    private final Security bond = new Security("Test bond", "EUR");

    public BondUpdateTest()
    {
        bond.setIsin("DE0001102622");
        bond.setFeed("YAHOO");
        bond.setNote("My original note");
        bond.addPrice(new SecurityPrice(LocalDate.parse("2025-01-02"), 9900000000L));
        client.addSecurity(bond);
    }

    private BondQuote quote(String date, String price)
    {
        var clean = new BigDecimal(price);
        return new BondQuote(LocalDate.parse(date), clean, BigDecimal.ONE, clean.add(BigDecimal.ONE), null, null, "Bundesbank test");
    }

    @Test
    public void requiresExplicitMigrationAndReportsRemovedDates()
    {
        var quotes = List.of(quote("2026-09-18", "100"));
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.prepare(bond, quotes, false));
        var plan = BondUpdate.prepare(bond, quotes, true);
        assertTrue(plan.migration());
        assertEquals(1, plan.removed());
        assertEquals(LocalDate.parse("2025-01-02"), bond.getPrices().getFirst().getDate());
    }

    @Test
    public void appliesToLiveObjectsPreservesUnrelatedDataAndRoundTripsMetadata() throws Exception
    {
        var other = new Security("Other", "EUR");
        client.addSecurity(other);
        var snapshot = ClientFactory.duplicate(client);
        var plan = BondUpdate.prepare(snapshot.getSecurities().getFirst(), List.of(quote("2026-09-18", "100")), true);
        var events = new java.util.ArrayList<String>();
        client.addPropertyChangeListener(e -> events.add(e.getPropertyName()));
        BondUpdate.apply(client, List.of(plan));
        assertSame(bond, client.getSecurities().getFirst());
        assertSame(other, client.getSecurities().get(1));
        assertTrue(bond.getNote().startsWith("My original note"));
        assertEquals("MANUAL", bond.getFeed());
        assertEquals("MANUAL", bond.getLatestFeed());
        assertEquals(10100000000L, bond.getLatest().getValue());
        assertTrue(events.contains("dirty"));
        assertFalse(BondUpdate.managed(snapshot.getSecurities().getFirst()));
        var loaded = ClientFactory.duplicate(client);
        assertTrue(BondUpdate.managed(loaded.getSecurities().getFirst()));
        assertEquals(bond.getAttributes().getMap(), loaded.getSecurities().getFirst().getAttributes().getMap());
    }

    @Test
    public void subsequentUpdatePreservesOlderDirtyPricesAndReplacesAuditOnce()
    {
        BondUpdate.apply(client, List.of(BondUpdate.prepare(bond, List.of(quote("2026-09-18", "100")), true)));
        var next = BondUpdate.prepare(bond, List.of(quote("2026-09-21", "101")), false);
        assertFalse(next.migration());
        assertEquals(0, next.removed());
        BondUpdate.apply(client, List.of(next));
        assertEquals(2, bond.getPrices().size());
        assertEquals(1, bond.getNote().split("XAPA-BOND-PRICES-BEGIN", -1).length - 1);
        assertEquals(10200000000L, bond.getLatest().getValue());
    }

    @Test
    public void outdatedOrPartialPlanCannotMutateAnySecurity()
    {
        var a = BondUpdate.prepare(bond, List.of(quote("2026-09-18", "100")), true);
        var second = new Security("Second", "EUR");
        second.setIsin("DE000BU27014");
        client.addSecurity(second);
        var b = BondUpdate.prepare(second, List.of(quote("2026-09-18", "100")), true);
        second.setNote("changed while fetching");
        var before = BondUpdate.state(bond);
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.apply(client, List.of(a, b)));
        assertEquals(before, BondUpdate.state(bond));
        assertEquals(0, client.getSettings().getAttributeTypes().filter(t -> t.getId().startsWith("xapa.bond")).count());
    }

    @Test
    public void rejectsEmptyOlderConflictingAndUnsupportedData()
    {
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.prepare(bond, List.of(), true));
        bond.setLatest(new LatestSecurityPrice(LocalDate.parse("2026-09-22"), 10000000000L));
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.prepare(bond, List.of(quote("2026-09-21", "99")), true));
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.prepare(bond, List.of(quote("2026-09-22", "99"), quote("2026-09-22", "100")), true));
        bond.setCurrencyCode("USD");
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.prepare(bond, List.of(quote("2026-09-22", "99")), true));
    }

    @Test
    public void incompatibleAttributesAreRejectedBeforeMutation()
    {
        var type = new AttributeType("xapa.bond.price-date");
        type.setType(String.class);
        client.getSettings().addAttributeType(type);
        var before = BondUpdate.state(bond);
        var plan = BondUpdate.prepare(bond, List.of(quote("2026-09-18", "100")), true);
        assertThrows(IllegalArgumentException.class, () -> BondUpdate.apply(client, List.of(plan)));
        assertEquals(before, BondUpdate.state(bond));
    }
}
