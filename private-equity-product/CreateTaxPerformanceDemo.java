import java.io.File;
import java.time.*;
import java.util.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.tax.*;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.util.Interval;

public class CreateTaxPerformanceDemo {
 static LocalDate start=LocalDate.of(2025,10,6), end=LocalDate.of(2026,10,6);
 static Client c=new Client();static Account cash=new Account("Espèces investissables — fictives");
 static ArrayList<TaxPaymentLinks.Link> links=new ArrayList<>();
 static TaxEstimateSettings.Settings settings=new TaxEstimateSettings.Settings(1,Map.of());
 public static void main(String[] args)throws Exception {
  c.setBaseCurrency("EUR");cash.setCurrencyCode("EUR");c.addAccount(cash);
  cash.addTransaction(new AccountTransaction(start.atStartOfDay(),"EUR",35_000_00,null,AccountTransaction.Type.DEPOSIT));
  var a=position("CTO — fictif","Action A — hausse et vente partielle",120,TaxEstimateSettings.Profile.CTO,null);
  var b=position("Holding — fictive","Fonds B — clôture annuelle",110,TaxEstimateSettings.Profile.HOLDING_ANNUAL,"2026-06-30");
  var d=position("AV — convention fictive","Support C — en perte",90,TaxEstimateSettings.Profile.LIFE_INSURANCE,null);
  b.getTransactions().getFirst().getSecurity().addPrice(new SecurityPrice(LocalDate.of(2026,6,30),105L*Values.Quote.factor()));
  a.getTransactions().getFirst().getSecurity().addPrice(new SecurityPrice(LocalDate.of(2026,9,1),115L*Values.Quote.factor()));
  trade(a,a.getTransactions().getFirst().getSecurity(),PortfolioTransaction.Type.SELL,LocalDate.of(2026,9,1),50,5_750_00);
  pay(b,LocalDate.of(2026,7,15),125_00);
  pay(a,LocalDate.of(2026,9,15),187_50);
  cash.addTransaction(new AccountTransaction(LocalDate.of(2026,4,6).atStartOfDay(),"EUR",1_000_00,null,AccountTransaction.Type.DEPOSIT));
  TaxEstimateSettings.save(c,settings,null);
  TaxPaymentLinks.save(c,new TaxPaymentLinks.Settings(1,links,Map.of()),null);
  var taxonomy=new Taxonomy("Périmètres de suivi");var root=new Classification("tax-demo-root","Patrimoine");taxonomy.setRootNode(root);
  var invest=new Classification(root,"tax-demo-investable","Portefeuille investissable");root.addChild(invest);
  c.getSecurities().forEach(s->invest.addAssignment(new Classification.Assignment(s)));invest.addAssignment(new Classification.Assignment(cash));c.addTaxonomy(taxonomy);
  var dashboard=new Dashboard("tax-demo-dashboard");dashboard.setName("Performance après impôts — investissable");
  for(String[] group:new String[][]{{"AFTER_TAX_ANNUALIZED","AFTER_TAX_EUR"},{"AFTER_TAX_TWR","AFTER_TAX_PERCENT"},{"AFTER_TAX_IRR"}}){
   var column=new Dashboard.Column();dashboard.getColumns().add(column);
   for(String type:group){var widget=new Dashboard.Widget();widget.setType(type);widget.setLabel(switch(type){case "AFTER_TAX_ANNUALIZED"->"Rendement annuel après impôts";case "AFTER_TAX_EUR"->"Gain net (EUR)";case "AFTER_TAX_TWR"->"%TW après impôts";case "AFTER_TAX_PERCENT"->"Gain net (%)";default->"TRI après impôts";});
    widget.getConfiguration().put("DATA_SERIES","Classificationtax-demo-investable");widget.getConfiguration().put("REPORTING_PERIOD","S2025-10-06");column.getWidgets().add(widget);
   }
  }
  c.addDashboard(dashboard);
  var columns=c.getSettings().getConfigurationSet("name.abuchen.portfolio.ui.views.StatementOfAssetsViewer");
  columns.add(new ConfigurationSet.Configuration("tax-v15-net","Après impôts — depuis le 06/10/2025",columns("1","5","tax-after","tax-percent","tax-twr","tax-irr","tax-annualized")));
  columns.add(new ConfigurationSet.Configuration("tax-v15-detail","Fiscalité — détail",columns("1","5","tax-latent","tax-debt","tax-net-value","tax-before","tax-after")));
  columns.add(new ConfigurationSet.Configuration("tax-v15-gross","Valeurs brutes",columns("1","0","5")));
  var now=Instant.now();c.getSecurities().forEach(s->s.setUpdatedAt(now));c.getAccounts().forEach(s->s.setUpdatedAt(now));c.getPortfolios().forEach(s->s.setUpdatedAt(now));
  ClientFactory.saveAs(c,new File(args[0]),null,EnumSet.of(SaveFlag.XML));
  var loaded=ClientFactory.load(new File(args[0]),null,new org.eclipse.core.runtime.NullProgressMonitor());
  var converter=new CurrencyConverterImpl(new ExchangeRateProviderFactory(loaded),"EUR");
  var result=TaxPerformance.calculate(loaded,TaxScope.of(loaded,ClientFilter.NO_FILTER,null),converter,Interval.of(start,end));
  if(!Long.valueOf(1_312_50).equals(result.after().gain()))throw new AssertionError(result);
  System.out.println("DEMO_AFTER_TAX_PASS "+result);
 }
 static Portfolio position(String name,String title,long price,TaxEstimateSettings.Profile profile,String close){
  var p=new Portfolio(name);p.setReferenceAccount(cash);c.addPortfolio(p);var s=new Security(title,"EUR");s.setFeed("MANUAL");c.addSecurity(s);s.addPrice(new SecurityPrice(start,100L*Values.Quote.factor()));s.addPrice(new SecurityPrice(end,price*Values.Quote.factor()));trade(p,s,PortfolioTransaction.Type.BUY,start,100,10_000_00);
  var key=TaxEstimateSettings.key(p.getUUID(),s.getUUID());settings=settings.with(key,new TaxEstimateSettings.Basis(profile,2500,TaxEstimateSettings.LossTreatment.POTENTIAL_SAVING,10_000_00,100L*Values.Share.factor(),start.toString(),close,PositionTaxEstimates.movements(c,p,s,start),"Données fictives ; taux de 25 % purement illustratif. AV : convention par support."));return p;
 }
 static void trade(Portfolio p,Security s,PortfolioTransaction.Type type,LocalDate date,long shares,long amount){var e=new BuySellEntry(p,cash);e.setType(type);e.setDate(date.atTime(12,0));e.setSecurity(s);e.setShares(shares*Values.Share.factor());e.setCurrencyCode("EUR");e.setAmount(amount);e.insert();}
 static void pay(Portfolio p,LocalDate date,long amount){var t=new AccountTransaction(date.atStartOfDay(),"EUR",amount,null,AccountTransaction.Type.TAXES);cash.addTransaction(t);links.add(new TaxPaymentLinks.Link(cash.getUUID(),t.getUUID(),TaxEstimateSettings.key(p.getUUID(),p.getTransactions().getFirst().getSecurity().getUUID()),amount));}
 static String columns(String... ids){var items=new ArrayList<Map<String,Object>>();for(String id:ids){var m=new LinkedHashMap<String,Object>();m.put("id",id);m.put("width",id.equals("1")?305:180);if(Set.of("tax-after","tax-before","tax-percent","tax-twr","tax-irr","tax-annualized").contains(id))m.put("option","S2025-10-06");items.add(m);}return new com.google.gson.Gson().toJson(Map.of("items",items));}
}
