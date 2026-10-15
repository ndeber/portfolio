import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Refresh widget labels on a separate copy without changing financial or fee records. */
public class RefreshSupportCostWidgets {
 public static void main(String[] args)throws Exception {
  var input=Path.of(args[0]);var output=Path.of(args[1]);
  if(Files.exists(output)||input.toRealPath().equals(output.toAbsolutePath()))throw new IllegalArgumentException("New output required");
  var hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input));
  var client=ClientFactory.load(input.toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
  var before=ConfigurePeaEnvelope.fingerprint(client);int count=0;
  var feeAttributes=new HashMap<String,Map<String,Object>>();
  client.getSecurities().forEach(s->feeAttributes.put(s.getUUID(),new HashMap<>(s.getAttributes().getMap())));
  var configs=new ArrayList<Map<String,String>>();
  for(var dashboard:client.getDashboards().toList()) {
   if(dashboard.getId().equals("support-costs-investable-v1"))dashboard.setName("Frais des supports");
   for(var column:dashboard.getColumns())for(var widget:column.getWidgets())if(widget.getType().equals("SUPPORT_COSTS")) {
    configs.add(new HashMap<>(widget.getConfiguration()));
    if(widget.getLabel().startsWith("Frais intégrés"))widget.setLabel("Frais supports");count++;
   }
  }
  if(count==0)throw new IllegalArgumentException("Support widgets missing");
  ClientFactory.exportAs(client,output.toFile(),null,EnumSet.of(SaveFlag.BINARY,SaveFlag.COMPRESSED));
  var reloaded=ClientFactory.load(output.toFile(),null,new org.eclipse.core.runtime.NullProgressMonitor());
  if(!before.equals(ConfigurePeaEnvelope.fingerprint(reloaded)))throw new AssertionError("Financial records changed");
  for(var security:reloaded.getSecurities())if(!feeAttributes.get(security.getUUID()).equals(security.getAttributes().getMap()))throw new AssertionError("Attributes changed");
  var afterConfigs=new ArrayList<Map<String,String>>();
  for(var dashboard:reloaded.getDashboards().toList())for(var column:dashboard.getColumns())for(var widget:column.getWidgets())if(widget.getType().equals("SUPPORT_COSTS"))afterConfigs.add(widget.getConfiguration());
  if(!configs.equals(afterConfigs))throw new AssertionError("Period or scope changed");
  if(!Arrays.equals(hash,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))))throw new AssertionError("Source changed");
  System.out.println("SUPPORT_WIDGET_REFRESH_PASS widgets="+count+"; financial records, fee attributes, periods, scopes and source preserved");
 }
}
