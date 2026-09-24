package name.abuchen.portfolio.updates.bonds;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/** Small read-only table adapter. HSSF handles legacy AFT XLS; XLSX uses cached values, never evaluates formulas. */
public final class BondWorkbook
{
    private BondWorkbook()
    {
    }

    public static List<Map<Integer, Map<String, String>>> read(byte[] bytes) throws IOException
    {
        if (bytes.length > 20_000_000)
            throw new IOException("Fichier de cours trop volumineux.");
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K')
            return xlsx(bytes);
        var sheets = new ArrayList<Map<Integer, Map<String, String>>>();
        try (var book = new HSSFWorkbook(new ByteArrayInputStream(bytes)))
        {
            for (var sheet : book)
            {
                var rows = new LinkedHashMap<Integer, Map<String, String>>();
                for (var row : sheet)
                {
                    var cells = new LinkedHashMap<String, String>();
                    for (var cell : row)
                    {
                        var type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
                        if (type == CellType.NUMERIC)
                            cells.put(column(cell.getColumnIndex()), Double.toString(cell.getNumericCellValue()));
                        else if (type == CellType.STRING)
                            cells.put(column(cell.getColumnIndex()), cell.getStringCellValue());
                    }
                    rows.put(row.getRowNum() + 1, cells);
                }
                sheets.add(rows);
            }
        }
        catch (RuntimeException e)
        {
            throw new IOException("Classeur XLS invalide.", e);
        }
        return sheets;
    }

    private static List<Map<Integer, Map<String, String>>> xlsx(byte[] bytes) throws IOException
    {
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes)))
        {
            int total = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
            {
                var data = zip.readNBytes(64_000_001 - total);
                total += data.length;
                if (total > 64_000_000 || entries.size() > 1000)
                    throw new IOException("Classeur XLSX trop volumineux.");
                if (entries.put(entry.getName(), new String(data, StandardCharsets.UTF_8)) != null)
                    throw new IOException("Entrée XLSX dupliquée.");
            }
        }
        var strings = Jsoup.parse(entries.getOrDefault("xl/sharedStrings.xml", ""), "", Parser.xmlParser()).select("si")
                        .stream().map(e -> e.select("t").stream().map(t -> t.wholeText()).reduce("", String::concat)).toList();
        var sheets = new ArrayList<Map<Integer, Map<String, String>>>();
        for (var entry : entries.entrySet())
        {
            if (!entry.getKey().matches("xl/worksheets/sheet[0-9]+\\.xml"))
                continue;
            var rows = new LinkedHashMap<Integer, Map<String, String>>();
            for (var row : Jsoup.parse(entry.getValue(), "", Parser.xmlParser()).select("row"))
            {
                var cells = new LinkedHashMap<String, String>();
                for (var cell : row.select("c"))
                {
                    var value = cell.selectFirst("v");
                    var text = value == null ? "" : value.text();
                    if ("s".equals(cell.attr("t")) && !text.isEmpty())
                        text = strings.get(Integer.parseInt(text));
                    else if ("inlineStr".equals(cell.attr("t")))
                        text = cell.select("t").stream().map(t -> t.wholeText()).reduce("", String::concat);
                    cells.put(cell.attr("r").replaceAll("[0-9]", ""), text);
                }
                rows.put(Integer.parseInt(row.attr("r")), cells);
            }
            sheets.add(rows);
        }
        if (sheets.isEmpty())
            throw new IOException("Aucune feuille dans le classeur XLSX.");
        return sheets;
    }

    public static LocalDate date(String serial)
    {
        return LocalDate.of(1899, 12, 30).plusDays(new java.math.BigDecimal(serial).longValue());
    }

    private static String column(int index)
    {
        var result = new StringBuilder();
        do
        {
            result.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        }
        while (index >= 0);
        return result.toString();
    }
}
