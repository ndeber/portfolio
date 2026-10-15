import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.tax.*;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;
import name.abuchen.portfolio.util.Interval;

/** Apply confirmed assumptions to a distinct local copy; never overwrite the input. */
public class ConfigurePeaEnvelope {
 public static void main(String[] args)throws Exception {
  if(args.length!=6)throw new IllegalArgumentException("input output portfolio-name opening-date rate-bps profile-name");
  var source=Path.of(args[0]);var target=Path.of(args[1]);
  if(Files.exists(target)||source.toAbsolutePath().equals(target.toAbsolutePath()))throw new IllegalArgumentException("A new output file is required");
  var sourceHash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
  var c=ClientFactory.load(source.toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
  var before=fingerprint(c);
  var matches=c.getPortfolios().stream().filter(p->p.getName().equals(args[2])).toList();
  if(matches.size()!=1)throw new IllegalArgumentException("One exact portfolio match required");
  var p=matches.getFirst();var opening=LocalDate.parse(args[3]);int rate=Integer.parseInt(args[4]);
  var profile=new TaxEnvelopeProfiles.Pea(args[5],p.getUUID(),p.getReferenceAccount().getUUID(),opening.toString(),rate,true,true,LocalDate.now().toString(),
   "Date d'ouverture, historique complet, résidence fiscale française et hypothèse de retrait après cinq ans confirmés par l'utilisateur. Taux actuel de scénario, modifiable ; impots.gouv.fr consulté le 06/10/2026.");
  TaxEnvelopeProfiles.save(c,TaxEnvelopeProfiles.load(c).with(profile),c.getProperty(TaxEnvelopeProfiles.PROPERTY));
  String dashboardId="tax-envelope-"+p.getUUID();
  if(c.getDashboards().noneMatch(d->d.getId().equals(dashboardId))){
   var dashboard=new Dashboard(dashboardId);dashboard.setName(args[5]+" — après impôts");
   for(String[] group:new String[][]{{"AFTER_TAX_ANNUALIZED","AFTER_TAX_EUR"},{"AFTER_TAX_TWR","AFTER_TAX_PERCENT"},{"AFTER_TAX_IRR"}}){
    var column=new Dashboard.Column();dashboard.getColumns().add(column);
    for(String type:group){var widget=new Dashboard.Widget();widget.setType(type);widget.setLabel(switch(type){case "AFTER_TAX_ANNUALIZED"->"Rendement annuel net — PEA";case "AFTER_TAX_EUR"->"Gain net (EUR)";case "AFTER_TAX_TWR"->"%TW après impôts";case "AFTER_TAX_PERCENT"->"Gain net (%)";default->"TRI après impôts";});
     widget.getConfiguration().put("DATA_SERIES","[+]Portfolio"+p.getUUID());widget.getConfiguration().put("REPORTING_PERIOD","S"+opening.minusDays(1));column.getWidgets().add(widget);
    }
   }c.addDashboard(dashboard);
  }
  var columns=c.getSettings().getConfigurationSet("name.abuchen.portfolio.ui.views.StatementOfAssetsViewer");
  var items=new ArrayList<Map<String,Object>>();
  for(String id:List.of("1","5","tax-after","tax-percent","tax-twr","tax-irr","tax-annualized")){var m=new LinkedHashMap<String,Object>();m.put("id",id);m.put("width",id.equals("1")?305:180);if(id.startsWith("tax-"))m.put("option","S"+opening.minusDays(1));items.add(m);}
  columns.add(0,new ConfigurationSet.Configuration(dashboardId,"PEA — net depuis l'ouverture",new com.google.gson.Gson().toJson(Map.of("items",items))));
  var filters=c.getSettings().getConfigurationSet(ConfigurationSet.WellKnownConfigurationSets.CLIENT_FILTER_SELECTION);
  String selected=p.getUUID()+","+p.getReferenceAccount().getUUID();
  filters.lookup("StatementOfAssetsView").ifPresentOrElse(f->f.setData(selected),()->filters.add(new ConfigurationSet.Configuration("StatementOfAssetsView","",selected)));
  ClientFactory.exportAs(c,target.toFile(),null,EnumSet.of(SaveFlag.BINARY,SaveFlag.COMPRESSED));
  var reloaded=ClientFactory.load(target.toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
  if(!Arrays.equals(sourceHash,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))))throw new AssertionError("Input changed");
  if(!before.equals(fingerprint(reloaded)))throw new AssertionError("Financial records changed");
  var rp=reloaded.getPortfolios().stream().filter(q->q.getUUID().equals(p.getUUID())).findFirst().orElseThrow();
  var effective=TaxEnvelopeProfiles.effectiveSettings(reloaded);var manual=TaxEstimateSettings.load(reloaded);
  long titles=rp.getTransactions().stream().map(Transaction::getSecurity).distinct().count();
  long inherited=effective.positions().keySet().stream().filter(k->k.startsWith(p.getUUID()+"/")&&!manual.positions().containsKey(k)).count();
  Files.writeString(target.resolveSibling("PEA-Saxo.profil.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(TaxEnvelopeProfiles.load(reloaded)));
  System.out.println("PEA_PROFILE_COPY_PASS: titles="+titles+", inherited="+inherited+", maturity="+profile.maturity()+", input unchanged; financial fingerprint unchanged");
  var result=TaxPerformance.calculate(reloaded,TaxScope.of(reloaded,new PortfolioClientFilter(rp,rp.getReferenceAccount()),null),new CurrencyConverterImpl(new ExchangeRateProviderFactory(reloaded),"EUR"),Interval.of(opening.minusDays(1),LocalDate.now()));
  System.out.println("PEA_RESULT "+result);
  for(var s:rp.getTransactions().stream().map(Transaction::getSecurity).distinct().toList())System.out.println("PEA_SECURITY "+s.getName()+" "+s.getCurrencyCode()+" "+PositionTaxEstimates.sharesAt(rp,s,LocalDate.now()));
 }
 static String fingerprint(Client c){
  var rows=new ArrayList<String>();
  c.getPortfolios().forEach(p->{rows.add("P:"+p.getUUID()+":"+p.getName()+":"+(p.getReferenceAccount()==null?"":p.getReferenceAccount().getUUID()));p.getTransactions().forEach(t->rows.add(p.getUUID()+":"+t.getType()+":"+transaction(t)));});
  c.getAccounts().forEach(a->{rows.add("A:"+a.getUUID()+":"+a.getName()+":"+a.getCurrencyCode());a.getTransactions().forEach(t->rows.add(a.getUUID()+":"+t.getType()+":"+transaction(t)));});
  c.getSecurities().forEach(s->{rows.add("S:"+s.getUUID()+":"+s.getName()+":"+s.getCurrencyCode());s.getPricesIncludingLatest().forEach(p->rows.add(s.getUUID()+":"+p.getDate()+":"+p.getValue()));});
  Collections.sort(rows);return String.join("\n",rows);
 }
 static String transaction(Transaction t){return t.getUUID()+":"+t.getDateTime()+":"+t.getCurrencyCode()+":"+t.getAmount()+":"+t.getShares()+":"+(t.getSecurity()==null?"":t.getSecurity().getUUID())+":"+t.getUnits().map(u->u.getType()+":"+u.getAmount()+":"+u.getForex()+":"+u.getExchangeRate()).sorted().toList();}
}
