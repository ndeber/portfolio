package name.abuchen.portfolio.factors;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class CurrencyProposalsTest
{
    private final LocalDate today=LocalDate.of(2026,9,30);
    private final Client client=new Client();
    private final Portfolio portfolio=new Portfolio();
    public CurrencyProposalsTest(){client.addPortfolio(portfolio);}
    private Security fund(String name,String isin,long shares)
    {
        var s=new Security(name,"EUR");s.setIsin(isin);client.addSecurity(s);
        var type=client.getTaxonomies().stream().filter(t->t.getName().equals("Type d'actif")).findFirst().orElseGet(()->taxonomy("Type d'actif"));
        category(type,"OPCVM").addAssignment(new Classification.Assignment(s));
        var tx=new PortfolioTransaction();tx.setType(PortfolioTransaction.Type.DELIVERY_INBOUND);tx.setSecurity(s);
        tx.setShares(shares*Values.Share.factor());tx.setDateTime(today.minusDays(1).atStartOfDay());tx.setCurrencyCode("EUR");portfolio.addTransaction(tx);
        return s;
    }
    private Taxonomy taxonomy(String name)
    {var t=new Taxonomy(name);t.setRootNode(new Classification(UUID.randomUUID().toString(),name));client.addTaxonomy(t);return t;}
    private Classification category(Taxonomy tax,String name)
    {
        var existing=tax.getAllClassifications().stream().filter(c->c.getName().equals(name)).findFirst();if(existing.isPresent())return existing.get();
        var c=new Classification(tax.getRoot(),UUID.randomUUID().toString(),name);tax.getRoot().addChild(c);return c;
    }
    private CurrencyProposals.Evidence evidence()
    {return new CurrencyProposals.Evidence(Map.of("USD",6000,"JPY",2500,"UNKNOWN",1500),"Test",today,"https://example.test/report","Hypothèse documentée",true);}

    @Test public void scopeIsHeldFundsAndElmEvenUnheldWithTransfersAndRetirement()
    {
        var a=fund("Held","A",10);var b=fund("Exited","B",10);var e=fund("ELM",CurrencyProposals.ELM,0);
        var retired=fund("Retired","R",3);retired.setRetired(true);
        var tx=new PortfolioTransaction();tx.setType(PortfolioTransaction.Type.TRANSFER_OUT);tx.setSecurity(b);tx.setShares(10*Values.Share.factor());tx.setDateTime(today.atStartOfDay());portfolio.addTransaction(tx);
        var future=fund("Future","F",3);portfolio.getTransactions().getLast().setDateTime(today.plusDays(1).atStartOfDay());
        assertEquals(Set.of(a,e),new HashSet<>(CurrencyProposals.scope(client,today)));
        portfolio.setRetired(true);assertEquals(List.of(e),CurrencyProposals.scope(client,today));
    }
    @Test public void previewDoesNotMutateAndApplicationOnlyTouchesSelection() throws Exception
    {
        var a=fund("A","A",1);var b=fund("B","B",1);var tax=CurrencyExposure.ensure(client);
        var eur=category(tax,"EUR");eur.setColor("#123456");eur.setWeight(7500);
        var plan=CurrencyProposals.prepare(client,today,Map.of(a.getUUID(),evidence()));
        assertNull(client.getProperty(CurrencyProposals.AUDIT));
        assertEquals(1,tax.getClassifications(a).size());
        CurrencyProposals.apply(client,plan,Set.of(a.getUUID()));
        assertEquals(1,tax.getClassifications(b).size());
        assertEquals("À renseigner",tax.getClassifications(b).getFirst().getName());
        assertEquals("#123456",eur.getColor());assertEquals(7500,eur.getWeight());
        assertEquals(3,tax.getClassifications(a).size());
        var copied=ClientFactory.duplicate(client);
        var reloaded=CurrencyProposals.JSON.fromJson(com.google.gson.JsonParser.parseString(copied.getProperty(CurrencyProposals.AUDIT)).getAsJsonObject().get(a.getUUID()),CurrencyProposals.Row.class);
        assertEquals(evidence(),reloaded.evidence());
        var values=new EnumMap<Factor,Double>(Factor.class);var coverage=new EnumMap<Factor,Double>(Factor.class);
        String detail=CurrencyExposure.apply(tax,a,values,coverage);
        assertEquals(.6,values.get(Factor.USD_EUR),1e-10);assertTrue(detail.contains("ESTIMATION VALIDÉE"));
        assertEquals(.85,coverage.get(Factor.USD_EUR),1e-10);
        var second=CurrencyProposals.prepare(client,today,Map.of(a.getUUID(),evidence()));
        CurrencyProposals.apply(client,second,Set.of(a.getUUID()));assertEquals(3,tax.getClassifications(a).size());
    }
    @Test public void missingSourceAndUnselectedFundsArePreserved()
    {
        var s=fund("A","A",1);var plan=CurrencyProposals.prepare(client,today,Map.of());
        CurrencyProposals.apply(client,plan,Set.of());assertNull(CurrencyExposure.find(client));
        assertThrows(IllegalArgumentException.class,()->CurrencyProposals.apply(client,plan,Set.of(s.getUUID())));
        assertNull(CurrencyExposure.find(client));
    }
    @Test public void staleAndAmbiguousPlansFailBeforeMutation()
    {
        var s=fund("A","A",1);var t=CurrencyExposure.ensure(client);
        var plan=CurrencyProposals.prepare(client,today,Map.of(s.getUUID(),evidence()));
        category(t,"EUR").setName("Euro");
        assertThrows(IllegalArgumentException.class,()->CurrencyProposals.apply(client,plan,Set.of(s.getUUID())));
        var duplicate=category(t,"Another Euro");duplicate.setKey("factor.fx.EUR");
        var next=CurrencyProposals.prepare(client,today,Map.of(s.getUUID(),evidence()));
        assertThrows(IllegalArgumentException.class,()->CurrencyProposals.apply(client,next,Set.of(s.getUUID())));
        assertEquals(1,t.getClassifications(s).size());assertNull(client.getProperty(CurrencyProposals.AUDIT));
    }
    @Test public void invalidAuditDoesNotCreateTaxonomy()
    {
        var s=fund("A","A",1);client.setProperty(CurrencyProposals.AUDIT,"not json");
        var plan=CurrencyProposals.prepare(client,today,Map.of(s.getUUID(),evidence()));
        assertThrows(RuntimeException.class,()->CurrencyProposals.apply(client,plan,Set.of(s.getUUID())));assertNull(CurrencyExposure.find(client));
    }
    @Test public void geographyNeverProducesACurrencyProposal() throws Exception
    {
        var s=fund("World EUR","WORLD",1);var assets=taxonomy("Classes d'actifs");category(assets,"Actions").addAssignment(new Classification.Assignment(s,8000));
        var regions=taxonomy("Actions - Régions (MSCI)");category(regions,"États-Unis").addAssignment(new Classification.Assignment(s,5000));
        category(regions,"Chine").addAssignment(new Classification.Assignment(s,3000));category(regions,"Other").addAssignment(new Classification.Assignment(s,2000));
        var e=new CurrencySources().fetch(client,s,today,()->false);assertTrue(e.weights().isEmpty());assertNull(e.date());
        s.setName("World EUR hedged");assertTrue(new CurrencySources().fetch(client,s,today,()->false).weights().isEmpty());
    }
    @Test public void roundingDoesNotNormalizeUnknownFractions()
    {
        assertEquals(Map.of("USD",3333,"EUR",3333,"UNKNOWN",3334),CurrencySources.points(Map.of("USD",.33333,"EUR",.33333)));
        assertThrows(IllegalArgumentException.class,()->CurrencySources.points(Map.of("USD",1.01)));
        assertThrows(IllegalArgumentException.class,()->CurrencySources.points(Map.of("USD",Double.NaN)));
    }
    private String elm() throws Exception
    {try(var in=getClass().getResourceAsStream("elm-currencies.csv")){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
    @Test public void elmUsesActualUnderlyingValuesNotUsdListingOrTargets() throws Exception
    {
        var e=CurrencySources.parseElm(elm(),CurrencySources.ELM_PAGE,today);
        assertEquals(today,e.date());assertFalse(e.estimated());assertTrue(e.weights().isEmpty());
        assertTrue(e.note().contains("Ventilations par devise des sous-jacents"));
        assertEquals(e.weights(),CurrencySources.parseElm(elm().replace("29.17%","99.99%"),CurrencySources.ELM_PAGE,today).weights());
    }
    @Test public void malformedElmDatesAndDuplicateLinesAreRejected() throws Exception
    {
        String csv=elm();assertThrows(java.io.IOException.class,()->CurrencySources.parseElm(csv,CurrencySources.ELM_PAGE,today.minusDays(1)));
        assertThrows(java.io.IOException.class,()->CurrencySources.parseElm(csv+csv.lines().skip(1).findFirst().get()+"\n",CurrencySources.ELM_PAGE,today));
        assertThrows(java.io.IOException.class,()->CurrencySources.parseElm(csv.replaceFirst("09/30/2026","09/29/2026"),CurrencySources.ELM_PAGE,today));
    }
}
