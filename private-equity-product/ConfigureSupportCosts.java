import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import com.google.gson.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.supportcosts.SupportCosts;
import name.abuchen.portfolio.tax.*;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;

/** Add disclosed support costs to a distinct copy, preserving all financial records. */
public class ConfigureSupportCosts {
 public static void main(String[] args)throws Exception {
  var input=Path.of(args[0]);var output=Path.of(args[1]);
  if(Files.exists(output)||input.toRealPath().equals(output.toAbsolutePath()))throw new IllegalArgumentException("New output required");
  var hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input));
  var client=ClientFactory.load(input.toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
  var before=ConfigurePeaEnvelope.fingerprint(client);SupportCosts.installAttributes(client);
  var matches=client.getTaxonomies().stream().flatMap(t->t.getAllClassifications().stream()).filter(n->n.getName().equalsIgnoreCase("Portefeuille Investissable")).toList();
  if(matches.size()!=1)throw new IllegalArgumentException("Unique investable classification required");
  var classification=matches.getFirst();var scope=TaxScope.of(client,ClientFilter.NO_FILTER,classification);
  var scoped=new HashSet<String>();scope.positions().keySet().forEach(k->scoped.add(k.substring(k.indexOf('/')+1)));
  var catalog=JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonArray();
  var zero=Set.of("Ethereum","Bitcoin","Solana","Gold 1 oz");int configured=0,missing=0;
  var direct=new HashSet<String>();
  client.getTaxonomies().stream().filter(t->t.getName().equals("Type d'actif")).flatMap(t->t.getAllClassifications().stream())
   .filter(n->n.getName().equals("Action")||n.getName().equals("Obligation")).flatMap(n->n.getAssignments().stream())
   .filter(a->a.getWeight()>0&&a.getInvestmentVehicle() instanceof Security)
   .map(a->(Security)a.getInvestmentVehicle())
   .filter(s->!java.util.regex.Pattern.compile("\\b(PUT|CALL|TURBO|WARRANT|CERTIFICAT)\\b",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s.getName()).find())
   .forEach(s->direct.add(s.getUUID()));
  var feeHistory=name.abuchen.portfolio.updates.yields.BondYields.history(client);
  var report=new JsonArray();
  var types=client.getSettings().getAttributeTypes().filter(a->a.getTarget()==Security.class).collect(java.util.stream.Collectors.toMap(AttributeType::getId,a->a));
  for(var s:client.getSecurities()) {
   if(!scoped.contains(s.getUUID()))continue;
   var evidence=catalog.asList().stream().map(JsonElement::getAsJsonObject).filter(o->o.get("isin").getAsString().equals(s.getIsin())).findFirst();
   long shares=client.getPortfolios().stream().filter(p->scope.positions().containsKey(TaxEstimateSettings.key(p.getUUID(),s.getUUID()))).mapToLong(p->PositionTaxEstimates.sharesAt(p,s,LocalDate.of(2026,10,13))).sum();
   String source="",note="";Double rate=null,extra=null;
   if(evidence.isPresent()) {
    var e=evidence.get();rate=e.get("ter").getAsDouble();extra=e.has("extra")?e.get("extra").getAsDouble():null;
    source=e.get("source").getAsString();note=e.get("note").getAsString();
   }else if(zero.contains(s.getName())||direct.contains(s.getUUID())) {
    rate=0d;source="Nature du support dans le portefeuille : détention directe, sans fonds interposé.";
    note="0 % de frais de fonds intégrés au prix. Stockage, plateforme, réseau, courtage et frais d'enveloppe exclus.";
   }else {
    var existing=SupportCosts.annualRate(client,s);
    var peers=client.getSecurities().stream().filter(p->p==s||(s.getIsin()!=null&&s.getIsin().equals(p.getIsin()))).toList();
    var fees=peers.stream().map(p->name.abuchen.portfolio.updates.yields.BondYields.attribute(client,p,"Frais")).filter(Objects::nonNull).findFirst();
    if(existing!=null){rate=existing;source="Attribut TER existant dans la sauvegarde du 13/10/2026";note="Taux existant conservé ; source et date du taux initial à revalider.";}
    else if(fees.isPresent()){
     rate=fees.get();var observation=feeHistory.stream().filter(o->o.fees()!=null&&peers.stream().anyMatch(p->p.getUUID().equals(o.id()))).max(Comparator.comparing(o->o.date()));
     source=observation.map(o->o.source()).orElse("Attribut Frais existant dans la sauvegarde du 13/10/2026");
     note="Taux repris des frais de fonds déjà renseignés pour YTM-Frais"+observation.map(o->" au "+o.date()).orElse("")+". Part, frais variables et ancienneté documentaire à revalider ; aucune addition de frais d'enveloppe.";
    }
    else note="À renseigner à partir du DIC de la part exacte. Aucun zéro implicite.";
   }
   if(rate!=null){s.getAttributes().put(SupportCosts.ter(client),rate);configured++;}else missing++;
   if(extra!=null)s.getAttributes().put(types.get(SupportCosts.EXTRA),extra);
   s.getAttributes().put(types.get(SupportCosts.SOURCE),source);s.getAttributes().put(types.get(SupportCosts.NOTE),note);
   if(rate!=null)s.getAttributes().put(types.get(SupportCosts.CHECKED),"2026-10-13");
   var row=new JsonObject();row.addProperty("uuid",s.getUUID());row.addProperty("name",s.getName());row.addProperty("isin",s.getIsin());row.addProperty("held",shares>0);
   if(rate!=null)row.addProperty("ter",rate);if(extra!=null)row.addProperty("extra",extra);row.addProperty("source",source);row.addProperty("note",note);report.add(row);
  }
  var dashboard=new Dashboard("support-costs-investable-v1");dashboard.setName("Frais des supports");
  for(var period:List.of("X","L1Y0","Y2025")) {
   var column=new Dashboard.Column();dashboard.getColumns().add(column);
   var widget=new Dashboard.Widget();widget.setType("SUPPORT_COSTS");widget.setLabel("Frais supports");
   widget.getConfiguration().put("DATA_SERIES","Classification"+classification.getId());widget.getConfiguration().put("REPORTING_PERIOD",period);column.getWidgets().add(widget);
  }
  client.addDashboard(dashboard);
  var configurations=client.getSettings().getConfigurationSet("name.abuchen.portfolio.ui.views.StatementOfAssetsViewer");
  var columns=new JsonArray();
  for(var entry:List.of(new String[]{"1","300"},new String[]{"5","170"},new String[]{"attribute$ter","160"},new String[]{"attribute$"+SupportCosts.EXTRA,"160"},new String[]{"attribute$"+SupportCosts.CHECKED,"150"},new String[]{"attribute$"+SupportCosts.SOURCE,"300"},new String[]{"attribute$"+SupportCosts.NOTE,"400"})){
   var item=new JsonObject();item.addProperty("id",entry[0]);item.addProperty("width",Integer.parseInt(entry[1]));columns.add(item);
  }
  var config=new JsonObject();config.add("items",columns);
  configurations.add(new ConfigurationSet.Configuration("support-costs-investable-v1","Frais des supports",config.toString()));
  ClientFactory.exportAs(client,output.toFile(),null,EnumSet.of(SaveFlag.BINARY,SaveFlag.COMPRESSED));
  var reloaded=ClientFactory.load(output.toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
  if(!before.equals(ConfigurePeaEnvelope.fingerprint(reloaded)))throw new AssertionError("Financial records changed");
  if(!Arrays.equals(hash,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))))throw new AssertionError("Source changed");
  if(reloaded.getDashboards().noneMatch(d->d.getId().equals(dashboard.getId())))throw new AssertionError("Dashboard missing");
  Files.writeString(output.resolveSibling("Frais-supports-sources.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
  System.out.println("SUPPORT_COST_COPY_PASS configured="+configured+" missing="+missing+" current="+report.asList().stream().map(JsonElement::getAsJsonObject).filter(o->o.get("held").getAsBoolean()).count()+"; financial fingerprint and original hash unchanged");
 }
}
