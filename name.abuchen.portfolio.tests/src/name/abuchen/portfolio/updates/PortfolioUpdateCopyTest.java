package name.abuchen.portfolio.updates;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Security;

public class PortfolioUpdateCopyTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void copyRoundTripsAllSupportedFormatsWithoutOverwriting() throws Exception
    {
        var client = new Client();
        var security = new Security("Test", "EUR");
        client.addSecurity(security);
        for (String extension : List.of(".xml", ".portfolio", ".zip"))
        {
            var path = temporary.getRoot().toPath().resolve("copy" + extension);
            PortfolioUpdateCopy.save(client, path, null);
            var loaded = ClientFactory.load(path.toFile(), null, new NullProgressMonitor());
            assertEquals(security.getUUID(), loaded.getSecurities().getFirst().getUUID());
            var content = Files.readAllBytes(path);
            assertThrows(java.io.IOException.class, () -> PortfolioUpdateCopy.save(client, path, null));
            assertArrayEquals(content, Files.readAllBytes(path));
        }
    }

    @Test
    public void encryptedCopyStaysEncrypted() throws Exception
    {
        var client = new Client();
        var path = temporary.getRoot().toPath().resolve("encrypted.portfolio");
        var password = "test-only-password".toCharArray();
        PortfolioUpdateCopy.save(client, path, password);
        assertTrue(ClientFactory.isEncrypted(path.toFile()));
        assertNotNull(ClientFactory.load(path.toFile(), password, new NullProgressMonitor()));
        assertThrows(java.io.IOException.class, () -> PortfolioUpdateCopy.save(client,
                        temporary.getRoot().toPath().resolve("encrypted.xml"), password));
    }
}
