package name.abuchen.portfolio.updates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;

import org.eclipse.core.runtime.NullProgressMonitor;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.SaveFlag;

/** Writes and reloads an independent copy before publishing it at a new path. */
public final class PortfolioUpdateCopy
{
    private PortfolioUpdateCopy()
    {
    }

    public static void save(Client snapshot, Path destination, char[] password) throws IOException
    {
        if (Files.exists(destination))
            throw new IOException("Choisissez un nouveau fichier : la destination existe déjà.");

        var filename = destination.getFileName().toString().toLowerCase(Locale.ROOT);
        var flags = EnumSet.noneOf(SaveFlag.class);
        String extension;
        if (filename.endsWith(".portfolio"))
        {
            extension = ".portfolio";
            flags.add(SaveFlag.BINARY);
            if (password != null)
                flags.addAll(EnumSet.of(SaveFlag.ENCRYPTED, SaveFlag.AES256));
        }
        else if (filename.endsWith(".xml") && password == null)
        {
            extension = ".xml";
            flags.add(SaveFlag.XML);
        }
        else if (filename.endsWith(".zip") && password == null)
        {
            extension = ".zip";
            flags.addAll(EnumSet.of(SaveFlag.XML, SaveFlag.COMPRESSED));
        }
        else
        {
            throw new IOException("Format de copie non pris en charge (XML, ZIP ou .portfolio ; chiffrement : .portfolio).");
        }

        var staging = Files.createTempFile(destination.toAbsolutePath().getParent(), ".portfolio-update-", extension);
        try
        {
            ClientFactory.exportAs(snapshot, staging.toFile(), password, flags);
            ClientFactory.load(staging.toFile(), password, new NullProgressMonitor());
            // Deliberately no REPLACE_EXISTING: a concurrent file creation must fail.
            Files.move(staging, destination);
        }
        finally
        {
            Files.deleteIfExists(staging);
        }
    }
}
