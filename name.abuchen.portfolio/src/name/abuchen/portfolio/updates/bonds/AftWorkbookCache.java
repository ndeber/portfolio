package name.abuchen.portfolio.updates.bonds;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import name.abuchen.portfolio.updates.bonds.BondSources.AftPublication;

/** The official URL and bytes are replaced together, after validation. The gzip CRC covers both. */
final class AftWorkbookCache
{
    private final Path directory;

    AftWorkbookCache(Path directory)
    {
        this.directory = directory;
    }

    private static String prefix(boolean euroIndex)
    {
        return euroIndex ? "oatei-" : "oati-";
    }

    private List<Path> publications(boolean euroIndex) throws IOException
    {
        if (Files.notExists(directory))
            return List.of();
        String pattern = prefix(euroIndex) + "[0-9]{4}-[0-9]{2}\\.gz";
        try (var entries = Files.list(directory))
        {
            return entries.filter(p -> p.getFileName().toString().matches(pattern)).sorted(Comparator.reverseOrder()).toList();
        }
    }

    private Path path(boolean euroIndex, String url) throws IOException
    {
        return directory.resolve(prefix(euroIndex) + BondSources.aftPublicationMonth(url, euroIndex) + ".gz");
    }

    AftPublication read(boolean euroIndex) throws IOException
    {
        var files = publications(euroIndex);
        if (files.isEmpty())
            return null;
        var file = files.getFirst();
        try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(file))))
        {
            String url = input.readUTF();
            if (!file.equals(path(euroIndex, url)))
                throw new IOException("Provenance du cache AFT invalide.");
            byte[] bytes = input.readNBytes(20_000_001);
            if (bytes.length == 0 || bytes.length > 20_000_000)
                throw new IOException("Taille du cache AFT invalide.");
            return new AftPublication(url, bytes, null, true);
        }
    }

    void save(boolean euroIndex, AftPublication publication) throws IOException
    {
        Path target = path(euroIndex, publication.file());
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".aft-", ".tmp");
        try
        {
            try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))
            {
                output.writeUTF(publication.file());
                output.write(publication.bytes());
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            for (Path file : publications(euroIndex))
                if (file.compareTo(target) < 0)
                    Files.deleteIfExists(file);
        }
        finally
        {
            Files.deleteIfExists(temp);
        }
    }
}
